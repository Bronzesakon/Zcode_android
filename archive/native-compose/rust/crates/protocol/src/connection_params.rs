//! 对应 `Reference_code/lib/protocol/connection_params.dart`——ZCode 网页远程控制连接 URL 的解析。
//!
//! 目标 URL 形如：
//! `https://zcode.z.ai/remote/v4?sid=...&hash=...&t=...&mid=...&name=...&app_version=...&theme=...`
//!
//! 对应网页端 bundle 里的 `zC()`；中继 WebSocket 地址对应 `Jc()` / `pen.connect()`。
//! 本模块纯逻辑、同步、无 I/O（契约见 `PORTING.md`）。

use std::collections::HashMap;
use std::fmt;

use crate::error::ProtocolError;

/// 最小 URI 实现，只覆盖本模块用到的 Dart `Uri` 行为。
///
/// 对齐的 Dart 行为（`Reference_code/lib/protocol/connection_params.dart` 依赖到的全部）：
/// - `Uri.parse` 对 scheme / host 做小写化（`_makeScheme` / `_makeHost`）；
/// - 显式写出的默认端口被抹掉（http:80 / https:443，`_makePort` 与 `_SimpleUri` 快路径一致）；
/// - `Uri.queryParameters` 走 `Uri.splitQueryString`：**重复键后者胜**、空键段被丢弃、
///   无 `=` 的段取空串、`+` 解码为空格、`%XX` 按 UTF-8 解码；
/// - `Uri.toString()` 的拼接顺序 `scheme://host[:port]path[?query][#fragment]`。
///
/// PORT-NOTE: 该类型应由主线提升到公共模块（`relay_client` / `zemote_client` 同样需要
/// host / port / scheme）。本 crate 未依赖 `url` crate（`Cargo.toml` 不可改），故自带最小实现。
///
/// PORT-NOTE: 本实现**不**做 Dart `Uri.parse` 的百分号归一化（把非法字符转成 `%XX`）、
/// 不做 path 的 `.` / `..` 段消除、不保留 userinfo。前者对 `queryParameters` 的可观测结果
/// 无影响（`%XX` 会被再解码回来）；后两者本模块用不到（`source.path` 从未被读取）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Uri {
    scheme: String,
    host: String,
    port: Option<u16>,
    path: String,
    /// 归一化后的 query 文本（不含 `?`）。`None` 表示没有 query。
    query: Option<String>,
    fragment: Option<String>,
    /// 对应 Dart `Uri.queryParameters`（`late final` 缓存的那一份）。
    query_parameters: HashMap<String, String>,
}

impl Uri {
    /// 对应 Dart `Uri.parse`。
    ///
    /// Dart 在格式非法时抛 `FormatException`；本函数返回 `Err(ProtocolError::InvalidFrame)`，
    /// 由调用方（[`ZemoteConnectionParams::parse`]）映射成 `None`。
    pub fn parse(raw: &str) -> Result<Uri, ProtocolError> {
        // 1) fragment 先切（`#` 之后的 `?` 属于 fragment）。
        let (rest, fragment) = match raw.find('#') {
            Some(i) => (&raw[..i], Some(raw[i + 1..].to_string())),
            None => (raw, None),
        };
        // 2) 再切 query。
        let (rest, query) = match rest.find('?') {
            Some(i) => (&rest[..i], Some(rest[i + 1..].to_string())),
            None => (rest, None),
        };
        // 3) scheme：第一个 `:` 之前且不含 `/` 才可能是 scheme。
        let (scheme, rest) = match rest.find(':') {
            Some(i) if !rest[..i].contains('/') => {
                let scheme = make_scheme(&rest[..i])?;
                (scheme, &rest[i + 1..])
            }
            _ => (String::new(), rest),
        };
        // 4) authority 与 path。只有 `//` 开头才有 authority。
        let (host, port, path) = match rest.strip_prefix("//") {
            Some(after) => {
                let auth_end = after.find('/').unwrap_or(after.len());
                let authority = &after[..auth_end];
                let path = &after[auth_end..];
                // PORT-NOTE: userinfo 解析后丢弃——Dart 的 `source.userInfo` 在本模块从不被读取。
                let authority = match authority.rfind('@') {
                    Some(i) => &authority[i + 1..],
                    None => authority,
                };
                let (host, port) = split_host_port(authority, &scheme)?;
                (host, port, path.to_string())
            }
            None => (String::new(), None, rest.to_string()),
        };
        let query_parameters = match &query {
            Some(q) => split_query_string(q)?,
            None => HashMap::new(),
        };
        // PORT-NOTE: 未收口的 URI 差异（都不在既有测试覆盖范围内，故保持宽松/不猜测）：
        // 1) Dart 非快路径的 `_normalize` 会把 query 里「不在 `_queryCharMask` 且属通用分隔符」的
        //    字符判为 FormatException("Invalid character")，`parse` 随之返回 null；query 里可达的
        //    只有 `[` / `]`（`?`/`#` 已被切走，`:`/`/`/`@` 在 mask 内）。但 Dart 的
        //    `_SimpleUri` 快路径不归一化 query，可能直接接受。本实现取宽松分支（接受），
        //    避免对合法输入 fail-closed。
        // 2) Dart 的 query 归一化会把 `"`/`<`/`>`/`\`/`^`/反引号/`{`/`|`/`}`/空白/控制字符
        //    百分号转义，`queryParameters` 再解回来——最终字符串与本实现的「原样透传」一致。
        // 3) Dart 的 `Uri.parse` 会做 path 的 `.`/`..` 段消除（`_removeDotSegments`）；本实现不做。
        //    `source.path` 在本模块从不被读取，只影响 `source.to_string()`（无断言）。
        Ok(Uri {
            scheme,
            host,
            port,
            path,
            query,
            fragment,
            query_parameters,
        })
    }

    /// 对应 Dart `Uri(scheme:, host:, port:, path:)` 构造 + `_makeScheme` / `_makeHost` /
    /// `_makePort` 的归一化。用于构造中继地址（本模块只按 `/ws` 使用）。
    pub fn from_parts(scheme: &str, host: &str, port: Option<u16>, path: &str) -> Uri {
        let scheme = scheme.to_ascii_lowercase();
        let port = normalize_port(port, &scheme);
        Uri {
            scheme,
            host: host.to_ascii_lowercase(),
            port,
            path: path.to_string(),
            query: None,
            fragment: None,
            query_parameters: HashMap::new(),
        }
    }

    /// 对应 Dart `Uri.replace(queryParameters: {...})`：用参数表重建 query，其余分量不变。
    pub fn with_query_parameters(&self, parameters: &[(&str, &str)]) -> Uri {
        let mut query = String::new();
        for (key, value) in parameters {
            if !query.is_empty() {
                query.push('&');
            }
            // 对应 `_makeQueryFromParametersDefault.writeParameter`：空值只写键、不写 `=`。
            query.push_str(&encode_query_component(key));
            if !value.is_empty() {
                query.push('=');
                query.push_str(&encode_query_component(value));
            }
        }
        let query_parameters = split_query_string(&query).unwrap_or_default();
        Uri {
            query: Some(query),
            query_parameters,
            ..self.clone()
        }
    }

    /// 对应 Dart `Uri.scheme`（已小写化）。
    pub fn scheme(&self) -> &str {
        &self.scheme
    }

    /// 对应 Dart `Uri.host`（已小写化；IPv6 保留方括号）。
    pub fn host(&self) -> &str {
        &self.host
    }

    /// 对应 Dart `Uri.port`：无显式端口时为 `None`（Dart 那里会回落到 scheme 默认端口）。
    pub fn port(&self) -> Option<u16> {
        self.port
    }

    /// 对应 Dart `Uri.hasPort`：**只**反映 URL 里是否显式写了端口。
    pub fn has_port(&self) -> bool {
        self.port.is_some()
    }

    /// 对应 Dart `Uri.path`。
    pub fn path(&self) -> &str {
        &self.path
    }

    /// 对应 Dart `Uri.query`（不含 `?`；无 query 时为 `None`）。
    pub fn query(&self) -> Option<&str> {
        self.query.as_deref()
    }

    /// 对应 Dart `Uri.fragment`（不含 `#`）。
    pub fn fragment(&self) -> Option<&str> {
        self.fragment.as_deref()
    }

    /// 对应 Dart `Uri.queryParameters`：**重复键后者胜**。
    pub fn query_parameters(&self) -> &HashMap<String, String> {
        &self.query_parameters
    }

    /// 按**原始 query 顺序**返回解码后的键值对（重复键后者胜，但保留首次出现的位置）。
    ///
    /// 对应 Dart `Map` 的插入序语义：`uri.replace(queryParameters: ...)` 依赖它，日志里
    /// 的参数顺序才能与线上 URL 一致。`query_parameters()` 是 `HashMap`，顺序随机，不能用于此。
    pub fn query_parameter_pairs(&self) -> Vec<(String, String)> {
        let Some(query) = self.query.as_deref() else {
            return Vec::new();
        };
        let mut pairs: Vec<(String, String)> = Vec::new();
        for element in query.split('&') {
            if element.is_empty() {
                continue;
            }
            let (raw_key, raw_value) = match element.find('=') {
                Some(0) => continue,
                Some(i) => (&element[..i], &element[i + 1..]),
                None => (element, ""),
            };
            let key = decode_query_component(raw_key).unwrap_or_else(|_| raw_key.to_string());
            let value = decode_query_component(raw_value).unwrap_or_else(|_| raw_value.to_string());
            match pairs.iter_mut().find(|(existing, _)| *existing == key) {
                Some(slot) => slot.1 = value,
                None => pairs.push((key, value)),
            }
        }
        pairs
    }
}

/// 对应 Dart `Uri.toString()` 的分量拼接。
impl fmt::Display for Uri {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}://{}", self.scheme, self.host)?;
        if let Some(port) = self.port {
            write!(f, ":{port}")?;
        }
        write!(f, "{}", self.path)?;
        if let Some(query) = &self.query {
            write!(f, "?{query}")?;
        }
        if let Some(fragment) = &self.fragment {
            write!(f, "#{fragment}")?;
        }
        Ok(())
    }
}

/// 对应 Dart `_makeScheme`：首字符必须是字母，其余只能是字母/数字/`+`/`-`/`.`；结果小写化。
fn make_scheme(raw: &str) -> Result<String, ProtocolError> {
    let invalid = |msg: &str| ProtocolError::InvalidFrame(format!("uri scheme: {msg}"));
    if raw.is_empty() {
        // Dart：`_fail(uri, start, "Invalid empty scheme")`。
        return Err(invalid("empty scheme"));
    }
    let mut chars = raw.chars();
    let first = chars.next().unwrap();
    if !first.is_ascii_alphabetic() {
        // Dart：`_fail(..., "Scheme not starting with alphabetic character")`。
        return Err(invalid("scheme not starting with alphabetic character"));
    }
    for c in chars {
        if !(c.is_ascii_alphanumeric() || c == '+' || c == '-' || c == '.') {
            // Dart：`_fail(..., "Illegal scheme character")`。
            return Err(invalid("illegal scheme character"));
        }
    }
    // PORT-NOTE: Dart 的 `_SimpleUri` 快路径下 `_computeScheme` 对非 http/https/file/package
    // 的 scheme 返回原文（不保证小写），非快路径走 `_makeScheme` 才小写化。本实现统一小写
    // （与 Dart 文档一致）；只有 `HTTPS://` 这类大写 scheme 输入会与快路径产生差异，非测试覆盖项。
    Ok(raw.to_ascii_lowercase())
}

/// 对应 Dart `_makeHost`（reg-name 分支）+ `_makePort`：host 小写化，端口去默认值。
fn split_host_port(authority: &str, scheme: &str) -> Result<(String, Option<u16>), ProtocolError> {
    // 方括号 IPv6：Dart 的 `Uri.host` 保留方括号，端口在 `]` 之后。
    let (host, port_text) = if authority.starts_with('[') {
        match authority.find(']') {
            Some(end) => {
                let host = &authority[..=end];
                let rest = &authority[end + 1..];
                match rest.strip_prefix(':') {
                    Some(port) => (host, Some(port)),
                    None if rest.is_empty() => (host, None),
                    // Dart：`_fail(..., "Missing end `]` to match `[` in host")` / 非法 authority。
                    None => {
                        return Err(ProtocolError::InvalidFrame(format!(
                            "uri authority: unexpected text after host: {rest}"
                        )))
                    }
                }
            }
            None => {
                return Err(ProtocolError::InvalidFrame(
                    "uri authority: missing end `]` to match `[` in host".to_string(),
                ))
            }
        }
    } else {
        match authority.rfind(':') {
            Some(i) => (&authority[..i], Some(&authority[i + 1..])),
            None => (authority, None),
        }
    };

    let port = match port_text {
        // 空端口（`host:`）在 Dart 两条路径下都落到「没有端口」。
        None | Some("") => None,
        Some(text) => {
            let digits = text
                .parse::<u32>()
                .map_err(|_| ProtocolError::InvalidFrame(format!("invalid port: {text}")))?;
            // PORT-NOTE: Dart 的 `_SimpleUri` 快路径对端口不做范围检查（`https://h:99999/x`
            // 能解析成功），`_Uri.notSimple` 也不查；本实现把 > 65535 当非法输入返回 Err
            // （`parse` 因此返回 None）。非测试覆盖项，属于有意的安全收紧。
            Some(u16::try_from(digits).map_err(|_| {
                ProtocolError::InvalidFrame(format!("port out of range: {text}"))
            })?)
        }
    };
    // PORT-NOTE: 未加方括号的 IPv6 字面量（如 `https://::1:8080/x`）本实现会当成非法端口；
    // Dart 的 `_makeHost(strictIPv6: false)` 会把它识别成 IPv6 主机。非测试覆盖项。
    Ok((host.to_ascii_lowercase(), normalize_port(port, scheme)))
}

/// 对应 Dart `_makePort`：显式写出的默认端口（http:80 / https:443）在解析时被抹掉。
fn normalize_port(port: Option<u16>, scheme: &str) -> Option<u16> {
    match port {
        Some(80) if scheme == "http" => None,
        Some(443) if scheme == "https" => None,
        other => other,
    }
}

/// 对应 Dart `Uri.splitQueryString`（`Uri.queryParameters` 的实现）。
///
/// 逐条对齐：按 `&` 切段；取段内**第一个** `=`；空段丢弃；`=` 打头（空键）的段整段丢弃；
/// 无 `=` 的段值为空串；键与值都走 [`decode_query_component`]；同名键**后者覆盖前者**。
fn split_query_string(query: &str) -> Result<HashMap<String, String>, ProtocolError> {
    let mut map = HashMap::new();
    for element in query.split('&') {
        match element.find('=') {
            None => {
                if !element.is_empty() {
                    map.insert(decode_query_component(element)?, String::new());
                }
            }
            Some(0) => {}
            Some(i) => {
                let key = decode_query_component(&element[..i])?;
                let value = decode_query_component(&element[i + 1..])?;
                // PORT-NOTE: 这里**后者胜**（Dart `splitQueryString` 用 `map[key] = value` 覆盖）。
                // 注意与 `Uri.queryParametersAll`（首者胜、返回 List）区分——本模块用的是前者。
                map.insert(key, value);
            }
        }
    }
    Ok(map)
}

/// 对应 Dart `Uri.decodeQueryComponent` → `_Uri._uriDecode(..., plusToSpace: true)`。
///
/// `%XX` 按字节解码后整体按 UTF-8 还原；`+` 变空格；其余字符原样保留。
///
/// PORT-NOTE: 非法/截断的 `%XX`（如 `%zz`、结尾的 `%2`）在 Dart 侧**不抛错**——
/// `_normalize` 走 `_normalizeEscape` 返回 `"%"`，于是被改写成 `%25`，解码回来就是字面 `%`。
/// 本实现照抄：非法转义只吐出 `%` 本身并前进 1 字节（不是 3）。只有非法 UTF-8 才失败
/// （Dart 那里 `utf8.decode` 抛 `FormatException`，且因为 `_get` 在 try 块之外会直接抛出
/// `parse`；本实现改为返回 `Err` → `parse` 返回 `None`，不 panic）。
fn decode_query_component(raw: &str) -> Result<String, ProtocolError> {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' => {
                // 合法转义：前进 3；否则按字面 `%` 处理（对应 Dart 的 `%25` 改写）。
                match (bytes.get(i + 1).and_then(|b| hex_value(*b)), bytes.get(i + 2).and_then(|b| hex_value(*b))) {
                    (Some(hi), Some(lo)) => {
                        out.push(hi * 16 + lo);
                        i += 3;
                    }
                    _ => {
                        out.push(b'%');
                        i += 1;
                    }
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            byte => {
                // PORT-NOTE: Dart 在 parse 阶段会把非 ASCII 字符百分号转义，解码时再按 UTF-8
                // 还原成同一字符；本实现不做那次往返，逐字节原样搬运，最终字符串一致。
                out.push(byte);
                i += 1;
            }
        }
    }
    String::from_utf8(out)
        .map_err(|e| ProtocolError::InvalidFrame(format!("invalid utf-8 in query: {e}")))
}

/// 对应 Dart `_hexCharPairToByte` 的单字符版本（0-9 / a-f / A-F）。
fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

/// 对应 Dart `Uri.encodeQueryComponent`（`_uriEncode(_unreservedMask, ..., spaceToPlus: true)`）。
///
/// 只有 RFC 3986 的 unreserved（`A-Za-z0-9-._~`）原样输出；空格变 `+`；其余字符（含非 ASCII
/// 的 UTF-8 字节）转成**大写**十六进制的 `%XX`。
fn encode_query_component(raw: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(raw.len());
    for byte in raw.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(byte as char)
            }
            b' ' => out.push('+'),
            other => {
                out.push('%');
                out.push(HEX[(other >> 4) as usize] as char);
                out.push(HEX[(other & 0x0f) as usize] as char);
            }
        }
    }
    out
}

/// 对应 Dart `int.tryParse(source)`（省略 radix 的调用形态）。
///
/// 允许可选 `+` / `-` 号；允许 `0x` 十六进制前缀（Dart 文档：省略 radix 时数字可以 `0x` 开头）；
/// 其余按十进制。溢出（超出 `i64`）返回 `None`。
///
/// PORT-NOTE: 调用点（`_get`）已经 `trim()` 过，所以这里不需要（也刻意不）容忍首尾空白——
/// Dart 的 `int.parse` 本身不容忍空白。Dart 对超长十六进制字面量有「按无符号数重新解释」的
/// 行为（`0xFFFFFFFFFFFFFFFF` → -1），本实现未复刻（时间戳用不到），溢出统一返回 `None`。
fn parse_int_like_dart(raw: &str) -> Option<i64> {
    let (negative, digits) = match raw.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, raw.strip_prefix('+').unwrap_or(raw)),
    };
    let (radix, digits) = match digits.strip_prefix("0x") {
        Some(rest) => (16u32, rest),
        None => (10u32, digits),
    };
    if digits.is_empty() {
        return None;
    }
    let mut value: i64 = 0;
    for c in digits.chars() {
        let digit = c.to_digit(radix)? as i64;
        value = value.checked_mul(radix as i64)?.checked_add(digit)?;
    }
    if negative {
        value.checked_neg()
    } else {
        Some(value)
    }
}

/// 对应 Dart `ZemoteConnectionParams`（网页端 `zC()` 的解析结果）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ZemoteConnectionParams {
    /// 对应 `deviceSid`（query `sid`，必填）。
    pub device_sid: String,
    /// 对应 `passHash`（query `hash`）。
    ///
    /// 3.16.1 起远控链接改为 OAuth JWT 鉴权，`hash` 不再出现；旧链接仍可能带它，
    /// 所以这里是 `Option`。新旧两条认证路径由 [`Self::auth_token`] 是否存在区分。
    pub pass_hash: Option<String>,
    /// 对应 `timestamp`（query `t`）。旧链接必填；3.16.1 起不再下发，故为 `Option`。
    pub timestamp: Option<i64>,
    /// OAuth JWT（`zcodejwttoken`），由宿主登录后注入，**不来自 URL**。
    ///
    /// 存在时 relay 走 `wss://<host>/api/v1/remote/ws2?token=<jwt>&mid=…` 的 token 鉴权路径，
    /// 不再需要 `auth_challenge` / HMAC proof。字段**绝不**进入任何快照或日志。
    pub auth_token: Option<String>,
    /// 对应 `deviceMid`（query `mid`，可选）。
    pub device_mid: Option<String>,
    /// 对应 `deviceName`（query `name`，可选）。
    pub device_name: Option<String>,
    /// 对应 `appVersion`（query `app_version`，可选）。
    pub app_version: Option<String>,
    /// 对应 `theme`（query `theme`，可选）。
    pub theme: Option<String>,
    /// 对应 `source`：原始 URI（已归一化）。
    pub source: Uri,
}

impl ZemoteConnectionParams {
    /// 对应 Dart `ZemoteConnectionParams.parse`（网页端 `zC()`）。
    ///
    /// 要求 scheme 为 `https` / `wss`，且 `sid` 存在。`hash` / `t` 是旧链接的可选字段：
    /// 3.16.1 起远控链接只带 `sid` / `mid` / `name` / `app_version`，认证改由宿主注入
    /// [`Self::auth_token`]。`t` 若存在仍必须是可解析的整数（非法值视为无效链接）。
    pub fn parse(raw: &str) -> Option<ZemoteConnectionParams> {
        // Dart 先 `Uri.parse(raw.trim())`，解析失败（FormatException）返回 null。
        let uri = Uri::parse(raw.trim()).ok()?;
        let device_sid = get_param(&uri, "sid")?;
        let pass_hash = get_param(&uri, "hash");
        let timestamp = match get_param(&uri, "t") {
            Some(raw) => Some(parse_int_like_dart(&raw)?),
            None => None,
        };
        if uri.scheme != "https" && uri.scheme != "wss" {
            return None;
        }
        Some(ZemoteConnectionParams {
            device_sid,
            pass_hash,
            timestamp,
            auth_token: None,
            device_mid: get_param(&uri, "mid"),
            device_name: get_param(&uri, "name"),
            app_version: get_param(&uri, "app_version"),
            theme: get_param(&uri, "theme"),
            source: uri,
        })
    }

    /// 对应 Dart `relayWsUri`（网页端 `Jc()` / `pen.connect()`）。
    ///
    /// - **token 模式**（3.16.1+）：`wss://<host>/api/v1/remote/ws2?token=…&mid=…`
    ///   （网页端 `Id()`，`validation-*.js`；token 由 OAuth 登录取得）。
    /// - **legacy 模式**：`wss://<host>/ws?mid=…`，配合 `auth_challenge` / HMAC proof。
    pub fn relay_ws_uri(&self) -> Uri {
        let scheme = if self.uri_scheme_is_secure() { "wss" } else { "ws" };
        // 对应 `port: source.hasPort ? source.port : null`：没有显式端口时不带端口。
        let path = if self.auth_token.is_some() {
            "/api/v1/remote/ws2"
        } else {
            "/ws"
        };
        let base = Uri::from_parts(
            scheme,
            &self.source.host,
            if self.source.has_port() {
                self.source.port()
            } else {
                None
            },
            path,
        );
        let mut parameters: Vec<(&str, &str)> = Vec::new();
        if let Some(token) = &self.auth_token {
            parameters.push(("token", token.as_str()));
        }
        if let Some(mid) = &self.device_mid {
            parameters.push(("mid", mid.as_str()));
        }
        if parameters.is_empty() {
            base
        } else {
            base.with_query_parameters(&parameters)
        }
    }

    /// 给配对层展示/去重用的 relay URI：**不含 token**。
    ///
    /// `dryRun` 的 `parsed.relayUri` 会回给 Kotlin，不能把 JWT 带出去。
    pub fn relay_ws_uri_for_display(&self) -> Uri {
        let scheme = if self.uri_scheme_is_secure() { "wss" } else { "ws" };
        // 无 hash 的新链接即使还没登录，也按 token 模式展示（登录后才会真正建连）。
        let path = if self.auth_token.is_some() || self.pass_hash.is_none() {
            "/api/v1/remote/ws2"
        } else {
            "/ws"
        };
        let base = Uri::from_parts(
            scheme,
            &self.source.host,
            if self.source.has_port() {
                self.source.port()
            } else {
                None
            },
            path,
        );
        match &self.device_mid {
            None => base,
            Some(mid) => base.with_query_parameters(&[("mid", mid.as_str())]),
        }
    }

    /// 对应 Dart `uriSchemeIsSecure`：`source.scheme` 是 `https` 或 `wss`。
    ///
    /// 注意 Dart 侧曾是「非 http/ws 即安全」的写法（被修掉的 bug），现在只有这两个 scheme 算安全。
    pub fn uri_scheme_is_secure(&self) -> bool {
        self.source.scheme == "https" || self.source.scheme == "wss"
    }
}

/// 对应 Dart `ZemoteConnectionParams._get`：取 query 值 → `trim()` → 空串视为不存在。
fn get_param(uri: &Uri, key: &str) -> Option<String> {
    let value = uri.query_parameters.get(key)?.trim();
    if value.is_empty() {
        None
    } else {
        Some(value.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 测试辅助：按 Dart 测试里直接构造 `ZemoteConnectionParams(...)` 的方式造一个实例。
    fn params_with_source(raw: &str) -> ZemoteConnectionParams {
        ZemoteConnectionParams {
            device_sid: "sid".to_string(),
            pass_hash: Some("hash".to_string()),
            timestamp: Some(1),
            auth_token: None,
            device_mid: None,
            device_name: None,
            app_version: None,
            theme: None,
            source: Uri::parse(raw).expect("source uri should parse"),
        }
    }

    // ---- query 参数顺序（Dart Map 插入序） ----

    #[test]
    fn query_parameter_pairs_preserve_wire_order_and_last_wins() {
        let uri = Uri::parse("wss://zcode.z.ai/api/v1/remote/ws2?token=JWT&mid=MID-1&token=NEW")
            .expect("uri should parse");
        assert_eq!(
            uri.query_parameter_pairs(),
            vec![
                ("token".to_string(), "NEW".to_string()),
                ("mid".to_string(), "MID-1".to_string()),
            ]
        );
    }

    // ---- group uriSchemeIsSecure（6 条） ----

    #[test]
    fn uri_scheme_is_secure_https_is_secure() {
        assert!(params_with_source("https://zcode.z.ai/remote").uri_scheme_is_secure());
    }

    #[test]
    fn uri_scheme_is_secure_wss_is_secure() {
        assert!(params_with_source("wss://zcode.z.ai/ws").uri_scheme_is_secure());
    }

    #[test]
    fn uri_scheme_is_secure_http_is_not_secure() {
        assert!(!params_with_source("http://zcode.z.ai/remote").uri_scheme_is_secure());
    }

    #[test]
    fn uri_scheme_is_secure_ws_is_not_secure() {
        assert!(!params_with_source("ws://zcode.z.ai/ws").uri_scheme_is_secure());
    }

    #[test]
    fn uri_scheme_is_secure_ftp_is_not_secure() {
        // 原测试名："ftp → not secure (was bug)"。
        assert!(!params_with_source("ftp://bad.example.com/remote").uri_scheme_is_secure());
    }

    #[test]
    fn uri_scheme_is_secure_unknown_scheme_is_not_secure() {
        assert!(!params_with_source("file:///etc/passwd").uri_scheme_is_secure());
    }

    // ---- group relayWsUri（4 条） ----

    #[test]
    fn relay_ws_uri_https_becomes_wss() {
        let params = ZemoteConnectionParams::parse(
            "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1&mid=m",
        )
        .expect("should parse");
        assert_eq!(params.relay_ws_uri().to_string(), "wss://zcode.z.ai/ws?mid=m");
    }

    #[test]
    fn relay_ws_uri_http_is_rejected() {
        let params =
            ZemoteConnectionParams::parse("http://localhost:3000/remote/v4?sid=s&hash=h&t=1");
        assert!(params.is_none());
    }

    #[test]
    fn relay_ws_uri_port_is_preserved() {
        let params = ZemoteConnectionParams::parse(
            "https://zcode.z.ai:8443/remote/v4?sid=s&hash=h&t=1",
        )
        .expect("should parse");
        assert_eq!(params.relay_ws_uri().to_string(), "wss://zcode.z.ai:8443/ws");
    }

    #[test]
    fn relay_ws_uri_without_mid_has_no_query() {
        let params =
            ZemoteConnectionParams::parse("https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1")
                .expect("should parse");
        assert_eq!(params.relay_ws_uri().to_string(), "wss://zcode.z.ai/ws");
    }

    // ---- group parse edge cases（7 条） ----

    #[test]
    fn parse_missing_sid_returns_none() {
        assert!(ZemoteConnectionParams::parse("https://zcode.z.ai/remote/v4?hash=h&t=1").is_none());
    }

    #[test]
    fn parse_missing_hash_is_allowed() {
        // 3.16.1：新链接不再带 hash，认证由 OAuth token 承担。
        let params = ZemoteConnectionParams::parse("https://zcode.z.ai/remote/v4?sid=s&t=1")
            .expect("should parse");
        assert!(params.pass_hash.is_none());
    }

    #[test]
    fn parse_missing_t_is_allowed() {
        // 3.16.1：新链接不再带 t。
        let params = ZemoteConnectionParams::parse("https://zcode.z.ai/remote/v4?sid=s&hash=h")
            .expect("should parse");
        assert!(params.timestamp.is_none());
    }

    #[test]
    fn parse_new_3161_link_without_hash_and_t() {
        let params = ZemoteConnectionParams::parse(
            "https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=SID&mid=MID&name=Mac",
        )
        .expect("should parse");
        assert_eq!(params.device_sid, "SID");
        assert_eq!(params.device_mid.as_deref(), Some("MID"));
        assert_eq!(params.device_name.as_deref(), Some("Mac"));
        assert_eq!(params.app_version.as_deref(), Some("3.16.1"));
        assert!(params.pass_hash.is_none());
        assert!(params.timestamp.is_none());
        assert!(params.auth_token.is_none());
    }

    #[test]
    fn relay_ws_uri_token_mode_uses_ws2_and_token() {
        let mut params = ZemoteConnectionParams::parse(
            "https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=SID&mid=MID",
        )
        .expect("should parse");
        params.auth_token = Some("JWT".to_string());
        assert_eq!(
            params.relay_ws_uri().to_string(),
            "wss://zcode.z.ai/api/v1/remote/ws2?token=JWT&mid=MID"
        );
    }

    #[test]
    fn relay_ws_uri_for_display_omits_token() {
        let mut params = ZemoteConnectionParams::parse(
            "https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=SID&mid=MID",
        )
        .expect("should parse");
        params.auth_token = Some("JWT".to_string());
        assert_eq!(
            params.relay_ws_uri_for_display().to_string(),
            "wss://zcode.z.ai/api/v1/remote/ws2?mid=MID"
        );
    }

    #[test]
    fn parse_invalid_timestamp_returns_none() {
        assert!(
            ZemoteConnectionParams::parse("https://zcode.z.ai/remote/v4?sid=s&hash=h&t=abc")
                .is_none()
        );
    }

    #[test]
    fn parse_trims_query_values() {
        let params =
            ZemoteConnectionParams::parse("https://zcode.z.ai/remote/v4?sid= SID &hash= HASH &t=1")
                .expect("should parse");
        assert_eq!(params.device_sid, "SID");
        assert_eq!(params.pass_hash.as_deref(), Some("HASH"));
    }

    #[test]
    fn parse_empty_optional_fields_are_none() {
        let params = ZemoteConnectionParams::parse(
            "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1&mid=&name=",
        )
        .expect("should parse");
        assert!(params.device_mid.is_none());
        assert!(params.device_name.is_none());
    }

    #[test]
    fn parse_theme() {
        let params = ZemoteConnectionParams::parse(
            "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1&theme=dark",
        )
        .expect("should parse");
        assert_eq!(params.theme.as_deref(), Some("dark"));
    }
}
