//! 会话注册表（契约 `docs/FFI.md` §11）。
//!
//! * 全局 `static` + `Mutex`：`HashMap<handle, Arc<Mutex<Session>>>`；
//! * handle 是 `u64`，**从 1 开始单调递增**，进程内唯一；
//! * 每个 handle 的操作在**该会话自己的锁**内串行执行，不同 handle 之间无共享可变状态；
//! * 锁中毒（某次调用 panic 导致）用 [`PoisonError::into_inner`] 恢复，
//!   **不让会话永久不可用**（§11）。
//!
//! 这里是 JNI 入口真正调用的地方：`jni_bridge.rs` 只做 `jstring` ↔ `String` 的搬运与
//! `catch_unwind`，业务全在本模块。于是主机侧单测可以不启动 JVM 直接覆盖全部行为。

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock, PoisonError};

use serde_json::Value;

use crate::codec;
use crate::session::{self, Session};

/// 会话注册表：handle → 会话。
///
/// 内层 `Arc<Mutex<Session>>` 让「查表」与「执行」分两步：查表锁在拿到 `Arc` 后立刻释放，
/// 于是**同一 handle 的调用串行、不同 handle 的调用可并行**（§11），且不会有人持着全局锁
/// 跑核心状态机。
static REGISTRY: OnceLock<Mutex<HashMap<u64, Arc<Mutex<Session>>>>> = OnceLock::new();

/// 下一个待分配的 handle。从 1 开始（§5：`handle` 是 u64，进程内唯一、单调递增）。
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

/// 取（必要时初始化）注册表。
fn registry() -> &'static Mutex<HashMap<u64, Arc<Mutex<Session>>>> {
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 拿注册表锁，中毒则恢复（§11）。
fn registry_guard() -> MutexGuard<'static, HashMap<u64, Arc<Mutex<Session>>>> {
    registry().lock().unwrap_or_else(PoisonError::into_inner)
}

/// §4：`nativeAbiVersion()`。不需要 handle。
pub fn abi_version() -> Value {
    codec::abi_envelope()
}

/// §5：`nativeCreate(configJson)`。
///
/// 支持 `"dryRun": true`：此时**只解析 URL、不建会话、不占 handle**，返回
/// `{"ok":true,"dryRun":true,"handle":null,"events":[],"parsed":{...}}`。这是为了消灭 Kotlin
/// 配对层里那份「重复实现的 query 解码语义」（键解码、重复键后者胜、`+` → 空格）——
/// 让 Rust 权威地解析一次，两端就不可能漂移。
pub fn create(config_json: &str) -> Value {
    let config = match session::parse_config(config_json) {
        Ok(config) => config,
        Err(error) => return codec::bad_input(error.message),
    };
    if config.dry_run {
        // 不碰 `NEXT_HANDLE`、不碰注册表。
        return codec::ok_dry_run(session::parsed_view(&config));
    }
    let (session, events) = session::build(config);
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    registry_guard().insert(handle, Arc::new(Mutex::new(session)));
    codec::ok_handle(handle, events)
}

/// §6：`nativeStep(handle, inputJson, nowMs)`。
pub fn step(handle: u64, input_json: &str, now_ms: i64) -> Value {
    with_session(handle, |session| session.step(input_json, now_ms))
}

/// §8：`nativeCall(handle, commandJson, nowMs)`。
pub fn call(handle: u64, command_json: &str, now_ms: i64) -> Value {
    with_session(handle, |session| session.call(command_json, now_ms))
}

/// §9：`nativeQuery(handle, queryJson)`。
pub fn query(handle: u64, query_json: &str) -> Value {
    with_session(handle, |session| session.query(query_json))
}

/// §10：`nativeDispose(handle, nowMs)`。
///
/// 先**从注册表移除**再销毁：这样即使销毁过程中 panic，该 handle 也不会被二次销毁
/// （再调用会走「未知 handle」的 `invalidState`，符合 §10 的「幂等失败，不 panic」）。
pub fn dispose(handle: u64, now_ms: i64) -> Value {
    let Some(session) = registry_guard().remove(&handle) else {
        return unknown_handle(handle);
    };
    let mut guard = session.lock().unwrap_or_else(PoisonError::into_inner);
    guard.dispose(now_ms)
}

/// 取会话并在**它自己的锁**内执行；handle 不存在（或已销毁）时返回 `invalidState`。
///
/// §10：对已销毁的 handle 再调用是**幂等失败**，不是 panic。
fn with_session<F>(handle: u64, run: F) -> Value
where
    F: FnOnce(&mut Session) -> Value,
{
    // 先克隆出 `Arc` 再释放注册表锁，避免持有全局锁跑状态机。
    let session = registry_guard().get(&handle).cloned();
    let Some(session) = session else {
        return unknown_handle(handle);
    };
    let mut guard = session.lock().unwrap_or_else(PoisonError::into_inner);
    run(&mut guard)
}

/// §10 的失败信封：`invalidState`，不 panic。
fn unknown_handle(handle: u64) -> Value {
    codec::err(
        "invalidState",
        format!("handle {handle} 不存在或已销毁"),
        Vec::new(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    use serde_json::json;

    const URL: &str = "https://zcode.z.ai/remote/v4?sid=SID123&hash=HASH%3D&t=1785734607338&mid=MID-1";
    const CONFIG: &str = r#"{"url":"https://zcode.z.ai/remote/v4?sid=SID123&hash=HASH%3D&t=1785734607338&mid=MID-1","nowMs":1}"#;

    /// 串行化**会分配 handle** 的测试：`NEXT_HANDLE` 是进程级的，并发跑会让
    /// 「计数器有没有前进」这类断言变成噪声。持有它期间可以确定性地读 `NEXT_HANDLE`。
    fn handle_serial() -> MutexGuard<'static, ()> {
        static LOCK: Mutex<()> = Mutex::new(());
        // 某个测试断言失败会毒化这把锁；其余测试照样要能跑。
        LOCK.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn create_session() -> u64 {
        let envelope = create(CONFIG);
        assert_eq!(envelope["ok"], json!(true));
        envelope["handle"].as_u64().expect("handle 必须是 u64")
    }

    /// §5：handle 进程内唯一、单调递增（`NEXT_HANDLE` 的初值是 1，故第一个 handle 就是 1）。
    #[test]
    fn handles_are_unique_and_monotonically_increasing() {
        let _serial = handle_serial();
        let first = create_session();
        let second = create_session();
        assert!(first >= 1);
        assert_eq!(second, first + 1);
        assert_ne!(first, second);
        // 分配后计数器必须正好推进一格。
        assert_eq!(NEXT_HANDLE.load(Ordering::Relaxed), second + 1);
        // 收尾，避免污染同进程其它测试的 handle 序列。
        assert_eq!(dispose(first, 0)["ok"], json!(true));
        assert_eq!(dispose(second, 0)["ok"], json!(true));
    }

    /// §5：创建信封带 `handle` 与 `events`。
    #[test]
    fn create_envelope_carries_handle_and_events() {
        let _serial = handle_serial();
        let envelope = create(CONFIG);
        assert_eq!(envelope["ok"], json!(true));
        assert!(envelope["handle"].is_u64());
        assert!(envelope["events"].is_array());
        dispose(envelope["handle"].as_u64().unwrap(), 0);
    }

    /// §5 的 `"dryRun": true`：只解析 URL，**不建会话、不占 handle**。
    ///
    /// 这是为了消灭 Kotlin 配对层里那份「重复实现的 query 解码语义」（键解码、重复键后者胜、
    /// `+` → 空格）——让 Rust 权威地解析一次，两端就不可能漂移。
    #[test]
    fn dry_run_parses_without_creating_a_session_or_handle() {
        let _serial = handle_serial();
        let before = NEXT_HANDLE.load(Ordering::Relaxed);

        let envelope = create(&format!(r#"{{"url":"{URL}","nowMs":1,"dryRun":true}}"#));

        assert_eq!(envelope["ok"], json!(true));
        assert_eq!(envelope["dryRun"], json!(true));
        assert_eq!(envelope["handle"], json!(null));
        assert_eq!(envelope["events"], json!([]));
        assert_eq!(
            envelope["parsed"],
            json!({
                "deviceSid": "SID123",
                "deviceMid": "MID-1",
                "deviceName": null,
                "appVersion": null,
                "theme": null,
                "timestamp": 1785734607338_i64,
                "host": "zcode.z.ai",
                "scheme": "https",
                "relayUri": "wss://zcode.z.ai/ws?mid=MID-1",
            })
        );

        // 不占 handle：计数器没有前进（本测试持串行锁，故这是确定性的）。
        assert_eq!(NEXT_HANDLE.load(Ordering::Relaxed), before);
        // 也没注册会话：那个「本该被分配」的 handle 上什么都没有。
        let step = step(before, r#"{"type":"start"}"#, 0);
        assert_eq!(step["ok"], json!(false));
        assert_eq!(step["error"]["kind"], json!("invalidState"));
    }

    /// `dryRun` 返回的 `parsed` 与建会话后 `nativeQuery` 的 `params` **逐字段一致**。
    #[test]
    fn dry_run_parsed_matches_snapshot_params() {
        let _serial = handle_serial();
        let overrides = r#""appVersion":"9.9.9","deviceName":"Pixel","theme":"dark","mid":"MID-9""#;

        let dry = create(&format!(r#"{{"url":"{URL}",{overrides},"dryRun":true}}"#));
        assert_eq!(dry["ok"], json!(true));

        let handle = create(&format!(r#"{{"url":"{URL}",{overrides}}}"#))["handle"]
            .as_u64()
            .expect("handle 必须是 u64");
        let params = query(handle, r#"{"query":"snapshot"}"#)["snapshot"]["params"].clone();

        for key in [
            "deviceSid",
            "deviceMid",
            "deviceName",
            "appVersion",
            "theme",
            "timestamp",
        ] {
            assert_eq!(dry["parsed"][key], params[key], "字段 {key} 必须与 snapshot.params 一致");
        }
        // 额外三个字段只在 dryRun 里给（配对层要用它们做去重键与展示）。
        assert_eq!(dry["parsed"]["host"], json!("zcode.z.ai"));
        assert_eq!(dry["parsed"]["scheme"], json!("https"));
        assert_eq!(dry["parsed"]["relayUri"], json!("wss://zcode.z.ai/ws?mid=MID-9"));
        assert!(params.get("host").is_none(), "snapshot.params 不该多出 host");

        // 安全约定：dryRun 的返回值里同样不能出现 passHash。
        let text = serde_json::to_string(&dry).expect("必须能序列化");
        assert!(!text.contains("HASH="), "dryRun 泄漏了 passHash: {text}");
        assert!(!text.contains("passHash"), "dryRun 出现了 passHash 键: {text}");

        dispose(handle, 0);
    }

    /// `dryRun` 缺省或为 false 时行为**完全不变**；`dryRun` 也要校验 URL。
    #[test]
    fn dry_run_false_is_the_unchanged_path_and_true_still_validates_url() {
        let _serial = handle_serial();

        let normal = create(&format!(r#"{{"url":"{URL}","dryRun":false}}"#));
        assert!(normal["handle"].is_u64(), "dryRun=false 必须照常建会话");
        assert!(normal.get("dryRun").is_none());
        dispose(normal["handle"].as_u64().unwrap(), 0);

        // 解析失败仍是 badInput，且不占 handle。
        let before = NEXT_HANDLE.load(Ordering::Relaxed);
        let broken = create(r#"{"url":"http://zcode.z.ai/remote/v4?sid=S","dryRun":true}"#);
        assert_eq!(broken["ok"], json!(false));
        assert_eq!(broken["error"]["kind"], json!("badInput"));
        assert_eq!(NEXT_HANDLE.load(Ordering::Relaxed), before);

        // 3.16.1 新链接（无 hash/t）在 dryRun 下只校验 sid：配对页登录前就能验证链接。
        let modern = create(
            r#"{"url":"https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=S&mid=M","dryRun":true}"#,
        );
        assert_eq!(modern["ok"], json!(true));
        assert_eq!(modern["parsed"]["relayUri"], json!("wss://zcode.z.ai/api/v1/remote/ws2?mid=M"));
        assert!(modern["handle"].is_null(), "dryRun 不该占 handle");
    }

    #[test]
    fn create_with_bad_config_returns_bad_input_and_allocates_nothing() {
        let _serial = handle_serial();
        let before = NEXT_HANDLE.load(Ordering::Relaxed);
        let envelope = create(r#"{"url":"nonsense"}"#);
        assert_eq!(envelope["ok"], json!(false));
        assert_eq!(envelope["error"]["kind"], json!("badInput"));
        assert!(envelope.get("handle").is_none(), "失败时不该有 handle");
        assert_eq!(NEXT_HANDLE.load(Ordering::Relaxed), before);
    }

    /// §10：对已销毁的 handle 再调用 → `invalidState`，**不是 panic**。
    #[test]
    fn dispose_twice_is_invalid_state_not_panic() {
        let _serial = handle_serial();
        let handle = create_session();
        assert_eq!(dispose(handle, 0)["ok"], json!(true));

        let second = dispose(handle, 0);
        assert_eq!(second["ok"], json!(false));
        assert_eq!(second["error"]["kind"], json!("invalidState"));
        assert_eq!(second["events"], json!([]));
        assert!(second["error"]["message"]
            .as_str()
            .unwrap()
            .contains("已销毁"));
    }

    /// §10：销毁之后 `step` / `call` / `query` 一律 `invalidState`（handle 已从注册表移除）。
    #[test]
    fn every_entry_point_on_a_disposed_handle_is_invalid_state() {
        let _serial = handle_serial();
        let handle = create_session();
        dispose(handle, 0);

        for envelope in [
            step(handle, r#"{"type":"start"}"#, 0),
            call(handle, r#"{"method":"bootstrap"}"#, 0),
            query(handle, r#"{"query":"snapshot"}"#),
        ] {
            assert_eq!(envelope["ok"], json!(false));
            assert_eq!(envelope["error"]["kind"], json!("invalidState"));
            assert_eq!(envelope["events"], json!([]));
        }
    }

    /// 从未分配过的 handle（例如 0 或很大的数）同样是 `invalidState`。
    #[test]
    fn unknown_handle_is_invalid_state() {
        for handle in [0_u64, 999_999_999] {
            let envelope = step(handle, r#"{"type":"start"}"#, 0);
            assert_eq!(envelope["error"]["kind"], json!("invalidState"));
            assert_eq!(query(handle, r#"{"query":"snapshot"}"#)["error"]["kind"], json!("invalidState"));
        }
    }

    /// §11：不同 handle 互不影响（各自独立的状态机）。
    #[test]
    fn handles_are_isolated_from_each_other() {
        let _serial = handle_serial();
        let first = create_session();
        let second = create_session();

        let started = step(first, r#"{"type":"start"}"#, 0);
        let connect = started["events"]
            .as_array()
            .unwrap()
            .iter()
            .find(|event| event["type"] == json!("ws_connect"))
            .expect("第一个会话应有 ws_connect");
        assert_eq!(connect["generation"], json!(1));

        // 第二个会话没被驱动过，仍是 idle。
        assert_eq!(
            query(second, r#"{"query":"snapshot"}"#)["snapshot"]["relay"]["state"],
            json!("idle")
        );
        assert_eq!(
            query(first, r#"{"query":"snapshot"}"#)["snapshot"]["relay"]["state"],
            json!("connecting")
        );

        dispose(first, 0);
        dispose(second, 0);
    }

    /// §11：注册表锁中毒后仍可继续用（`PoisonError::into_inner` 恢复）。
    ///
    /// 这里直接人为毒化注册表锁：在另一个线程里 panic 掉一次持锁操作。
    #[test]
    fn poisoned_registry_lock_is_recovered() {
        let _serial = handle_serial();
        // 毒化全局注册表锁。
        let poisoned = std::thread::spawn(|| {
            let _guard = registry().lock().expect("首次加锁应成功");
            panic!("故意毒化注册表锁");
        })
        .join();
        assert!(poisoned.is_err(), "线程应当 panic");

        // 中毒之后仍必须能正常创建与销毁（而不是永久不可用）。
        let handle = create_session();
        assert_eq!(step(handle, r#"{"type":"start"}"#, 0)["ok"], json!(true));
        assert_eq!(dispose(handle, 0)["ok"], json!(true));
    }

    /// §4：`abiVersion` 的形状（`events` / `abi` / `protocolVersion` / `features`）。
    ///
    /// §13 接完后 `features` 如实反映能力——Kotlin 侧据此判断该 .so 是否含对话层。
    #[test]
    fn abi_version_shape() {
        let envelope = abi_version();
        assert_eq!(envelope["ok"], json!(true));
        // §3：每个 native 方法都带 `events`。
        assert_eq!(envelope["events"], json!([]));
        assert_eq!(envelope["abi"], json!(2));
        assert_eq!(envelope["protocolVersion"], json!("0.1.0"));
        assert_eq!(envelope["features"], json!(["conversation", "automation"]));
    }
}
