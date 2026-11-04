//! 对应 `Reference_code/lib/protocol/proof.dart`。
//!
//! 认证响应（`auth_response`）里的一次性证明。`relay_client.dart` 在收到
//! `auth_challenge` 后，用挑战里的 `nonce` 现算一个 `proof` 回给中继端：
//!
//! ```text
//! proof = base64url_nopad(HMAC-SHA256(key: utf8(passHash),
//!                                     msg: utf8('$nonce|$role|$deviceSid')))
//! ```
//!
//! 对应网页端的 `aen()` / `ien()`。**线上格式一个字节都不能变**：密钥是
//! `passHash` 的 UTF-8 字节（不是 hex 解码后的字节），消息用 `|` 裸拼接且不做转义，
//! 输出是去 padding 的 base64url。
//!
//! 本模块纯逻辑、无 I/O：不解析 URL、不读配置，`passHash` / `nonce` 由调用方传入。

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use hmac::{Hmac, Mac};
use sha2::Sha256;

/// HMAC-SHA256 实例类型，对应 Dart `Hmac(sha256, ...)`。
type HmacSha256 = Hmac<Sha256>;

/// 对应 Dart `calculateProof`（网页端 `aen()` / `ien()`）。
///
/// /// // PORT-NOTE: 签名映射——Dart 的具名必填参数
/// /// `{required String passHash, required String nonce, required String role,
/// /// required String deviceSid}` 在 Rust 里落成**位置参数** `&str`（PORTING.md 的
/// /// 命名映射表：`String` → `String` / `&str`）。取 `&str` 是为了让调用方既能传
/// /// `&params.pass_hash` 也能传字面量；若 FFI（UniFFI）需要 owned，由宿主层 `to_string()`。
/// /// 参数顺序与 Dart 声明顺序一致，便于对照。
///
/// - `pass_hash`：连接参数里的 `passHash`（`params.passHash`）。
///   **按 UTF-8 原文当密钥**，Dart 侧是 `utf8.encode(passHash)`，不做任何解码。
/// - `nonce`：`auth_challenge` 帧里的 `nonce`。Dart 调用点（`relay_client.dart`）
///   对缺失值回退成 `''`，故空串是合法输入。
///
/// // PORT-NOTE: 「`frame['nonce'] as String? ?? ''`」这个回退写在
/// // `relay_client.dart` 的调用点里，**不在 `proof.dart`**；移植 `relay_client` 时
/// // 必须原样带上，否则 `nonce` 缺失时会与本模块的 `&str` 签名对不上。
/// // 本模块只负责「给定 4 个字符串算 proof」，不替调用方兜底。
/// - `role`：本端角色，`relay_client.dart` 恒传 `'terminal'`。
/// - `device_sid`：连接参数里的 `deviceSid`，与 `auth_response` 的
///   `device_sid` 字段同源。
///
/// 返回 43 字符的 base64url 字符串（SHA-256 是 32 字节 → 44 字符含 1 个 `=`，
/// 剥掉后 43）。Dart 侧无失败路径，故这里也**不返回 `Result`**：HMAC 接受任意
/// 长度（含空）的密钥，`new_from_slice` 对 `Hmac<Sha256>` 不会返回 `Err`。
///
/// ```
/// # use zemote_protocol::proof::calculate_proof;
/// let p = calculate_proof("secret", "nonce", "terminal", "sid");
/// assert_eq!(p.len(), 43);
/// assert!(!p.contains('='));
/// ```
pub fn calculate_proof(pass_hash: &str, nonce: &str, role: &str, device_sid: &str) -> String {
    // Dart: final hmac = Hmac(sha256, utf8.encode(passHash));
    // PORT-NOTE: `expect` 不可达：`Hmac<Sha256>` 的 `new_from_slice` 接受任意长度密钥
    // （Dart 的 `Hmac(sha256, ...)` 同样不校验密钥长度）。故本函数保持 infallible、
    // 不返回 `Result`——Dart 侧也没有失败路径。若后续 hmac 版本改为校验密钥长度，
    // 这里要跟着改签名，别默默 panic。
    let mut mac = HmacSha256::new_from_slice(pass_hash.as_bytes())
        .expect("HMAC-SHA256 接受任意长度的密钥，此分支不可达");
    // Dart: '$nonce|$role|$deviceSid'
    // PORT-NOTE: 裸拼接，字段内出现 '|' **不转义、不歧义消解**。这是 Dart 的既有语义
    // （故 `nonce='a', role='b|c'` 与 `nonce='a|b', role='c'` 会撞出同一个 proof）。
    // 照抄行为：线上对端就是这么算的，这里"顺手修好"反而会认证失败。
    // 见测试 `pipe_join_is_ambiguous_like_dart`。
    mac.update(nonce.as_bytes());
    mac.update(b"|");
    mac.update(role.as_bytes());
    mac.update(b"|");
    mac.update(device_sid.as_bytes());
    let digest = mac.finalize().into_bytes();

    // Dart: base64Url.encode(digest.bytes).replaceAll('=', '')
    // PORT-NOTE: 依赖差异——Dart 的 `base64Url.encode` 会先加 '=' 再由 `replaceAll` 删掉；
    // Rust 的 `URL_SAFE_NO_PAD` 直接不产出 padding。两者逐字符等价（同一字母表
    // A-Za-z0-9-_，同一 32 字节 → 43 字符结果），无需再补一次 `trim_end_matches('=')`。
    URL_SAFE_NO_PAD.encode(digest)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 来自 Dart 测试 `Reference_code/test/widget_test.dart` 的用例
    /// `'proof is hmac-sha256 base64url'`（`passHash: 'secret'`, `nonce: 'nonce'`,
    /// `role: 'terminal'`, `deviceSid: 'sid'`）。
    ///
    /// 该 Dart 用例只断言「非空、不含 `=`、不含 `+`、不含 `/`」，**没有固定值**；
    /// 下面的具体期望值由 Python 独立算出（见各测试注释）。
    #[test]
    fn widget_test_vector() {
        let proof = calculate_proof("secret", "nonce", "terminal", "sid");

        // Dart 用例的四条断言，逐条搬运。
        assert!(!proof.is_empty());
        assert!(!proof.contains('='));
        assert!(!proof.contains('+'));
        assert!(!proof.contains('/'));

        // 期望值由 Python 独立算出：
        //   hmac.new(b'secret', b'nonce|terminal|sid', hashlib.sha256).digest()
        //   -> 0612b67559f75e0bf480b7a3ecf18755dcc81b2ce7bb0e5af06c33fe797db4fa
        //   标准 base64 为 BhK2dVn3Xgv0gLej7PGHVdzIGyznuw5a8Gwz/nl9tPo=
        //   （含 '/'，故本用例同时覆盖 URL-safe 替换；urlsafe 后为 ..._nl9tPo=）
        assert_eq!(proof, "BhK2dVn3Xgv0gLej7PGHVdzIGyznuw5a8Gwz_nl9tPo");
    }

    /// 字段内出现 `|`：Dart 用裸拼接、不转义，故 `nonce: 'a|b'` 与
    /// `role: 'c'` 拼出的消息是 `'a|b|c|d'`（4 段）。
    /// 期望值由 Python 独立算出（同上算法）：
    ///   b'p' / b'a|b|c|d' -> 977667c27986cc84991b81e727309cc0196b4a21886f7c54ab29d3d7ef046a3c
    ///   标准 base64 含 '+' -> url-safe 后该位变 '-'
    #[test]
    fn field_containing_pipe() {
        let proof = calculate_proof("p", "a|b", "c", "d");
        assert_eq!(proof, "l3ZnwnmGzISZG4HnJzCcwBlrSiGIb3xUqynT1-8Eajw");
        // 证明这个向量确实压到了 URL-safe 替换（标准 base64 是 ...T1+8Eajw=）。
        assert!(proof.contains('-'));
        assert!(!proof.contains('+'));
    }

    /// 摘要在标准 base64 下同时出现 `+` 和 `/`：这两个字节必须被替换成 `-` 和 `_`，
    /// 否则与 Dart `base64Url` 的输出不一致。
    /// 期望值由 Python 独立算出：
    ///   b'k1' / b'n|r|s' -> 0f52edae08201d4335e7eb23d3397b75e3300ac1ec6f520bc375a0ff171d40ef
    ///   标准 base64 = D1LtrgggHUM15+sj0zl7deMwCsHsb1ILw3Wg/xcdQO8=（+ 与 / 都在）
    #[test]
    fn url_safe_alphabet_substitution() {
        let proof = calculate_proof("k1", "n", "r", "s");
        assert_eq!(proof, "D1LtrgggHUM15-sj0zl7deMwCsHsb1ILw3Wg_xcdQO8");
        assert!(proof.contains('-'));
        assert!(proof.contains('_'));
        assert!(!proof.contains('+'));
        assert!(!proof.contains('/'));
    }

    /// padding 必须被剥掉：SHA-256 摘要 32 字节 → 标准 base64 是 44 字符含 1 个 `=`，
    /// Dart 的 `.replaceAll('=', '')` 之后恒为 43 字符。
    /// 期望值由 Python 独立算出：b'' / b'||' ->
    ///   4dab6b44fb16061b681e85920573f7ec49b9ded5d70385648eaf60005ea99cdc
    ///   -> TatrRPsWBhtoHoWSBXP37Em53tXXA4Vkjq9gAF6pnNw=（去掉 '=' 即下面的值）
    #[test]
    fn padding_is_stripped() {
        let proof = calculate_proof("", "", "", "");
        assert_eq!(proof, "TatrRPsWBhtoHoWSBXP37Em53tXXA4Vkjq9gAF6pnNw");
        assert_eq!(proof.len(), 43);
        assert!(!proof.contains('='));
    }

    /// 任意输入的输出都是 43 字符、且只含 base64url 字母表。
    #[test]
    fn output_is_always_43_chars_of_base64url_alphabet() {
        let cases: [(&str, &str, &str, &str); 5] = [
            ("secret", "nonce", "terminal", "sid"),
            ("", "", "", ""),
            ("HASH=", "n1", "terminal", "SID123"),
            ("密码", "随机", "terminal", "SID"),
            ("k1", "n", "r", "s"),
        ];
        for (pass_hash, nonce, role, device_sid) in cases {
            let proof = calculate_proof(pass_hash, nonce, role, device_sid);
            assert_eq!(
                proof.len(),
                43,
                "输入 {pass_hash:?}/{nonce:?}/{role:?}/{device_sid:?} 长度异常：{proof}"
            );
            assert!(
                proof
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'),
                "输入 {pass_hash:?}/{nonce:?}/{role:?}/{device_sid:?} 含非 base64url 字符：{proof}"
            );
        }
    }

    /// 空 `nonce` 是合法输入（Dart 调用点对缺失的 `frame['nonce']` 回退成 `''`）。
    /// 期望值由 Python 独立算出：
    ///   b'HASH=' / b'n1|terminal|SID123' ->
    ///   b24e85132bc5f183d1e6b35e4e27e667d2ace44e967e2caa95ceae4720d520c3
    ///   -> sk6FEyvF8YPR5rNeTifmZ9Ks5E6Wfiyqlc6uRyDVIMM=（去 '='）
    #[test]
    fn pass_hash_with_equals_and_realistic_sid() {
        let proof = calculate_proof("HASH=", "n1", "terminal", "SID123");
        assert_eq!(proof, "sk6FEyvF8YPR5rNeTifmZ9Ks5E6Wfiyqlc6uRyDVIMM");

        // nonce 为空串（挑战帧没有 nonce 时的回退值）也必须能算出来。
        let empty_nonce = calculate_proof("HASH=", "", "terminal", "SID123");
        assert_eq!(empty_nonce.len(), 43);
    }

    /// 非 ASCII 走 UTF-8 字节（Dart `utf8.encode`），不是 UTF-16 / Latin-1。
    /// 期望值由 Python 独立算出（对 `'密码'` / `'随机|terminal|SID'` 取 UTF-8 编码）：
    ///   d5fe9bf6f006a95ff27c720f59e934540d1170d7d36cddd36cbf68f08fe667a6
    ///   -> 1f6b9vAGqV_yfHIPWek0VA0RcNfTbN3TbL9o8I_mZ6Y
    ///   （标准 base64 含 '/'，url-safe 后该位变 '_'）
    #[test]
    fn non_ascii_uses_utf8_bytes() {
        let proof = calculate_proof("密码", "随机", "terminal", "SID");
        assert_eq!(proof, "1f6b9vAGqV_yfHIPWek0VA0RcNfTbN3TbL9o8I_mZ6Y");
        assert!(!proof.contains('/'));
    }

    /// 与 Dart 行为一致的歧义：消息是裸拼接，故不同的字段切分会撞到同一个 proof。
    /// 这里固定住这个「缺陷」以防移植时被"顺手修好"——修了就和线上不一致了。
    ///
    /// `nonce: 'a'`, `role: 'b|c'`, `deviceSid: 'd'` 与
    /// `nonce: 'a|b'`, `role: 'c'`, `deviceSid: 'd'` 都拼出 `'a|b|c|d'`。
    /// 期望值由 Python 独立算出：b'k' / b'a|b|c|d' -> ...（见 `field_containing_pipe` 的
    /// 同消息向量 `'l3ZnwnmGzISZG4HnJzCcwBlrSiGIb3xUqynT1-8Eajw'`，密钥同为单字节 'p'/'k' 不同，
    /// 故此处只断言两条路径互相相等，不断言具体值）。
    #[test]
    fn pipe_join_is_ambiguous_like_dart() {
        // 两边都拼出 'a|b|c|d'。
        let left = calculate_proof("k", "a", "b|c", "d");
        let right = calculate_proof("k", "a|b", "c", "d");
        assert_eq!(left, right);

        // 但字段切分真的不同时（消息不同），proof 必须不同。
        assert_ne!(left, calculate_proof("k", "a", "b", "d"));
    }

    /// 密钥与消息的分工不能搞反（HMAC 不是对称的），否则上面的向量会同时"看起来对"。
    #[test]
    fn key_and_message_are_not_interchangeable() {
        assert_ne!(
            calculate_proof("secret", "nonce", "terminal", "sid"),
            calculate_proof("nonce", "secret", "terminal", "sid")
        );
    }
}
