//! JNI 符号表（契约 `docs/FFI.md` §2、§12）。
//!
//! Kotlin 侧：`object NativeCore`，库名 `zemote_ffi`（`System.loadLibrary("zemote_ffi")`）。
//!
//! **第二参是 `jobject` 还是 `jclass`？** Kotlin 的 `object` 里写 `external fun` 而不加
//! `@JvmStatic` 时，生成的是**实例方法**，JVM 传进来的是那个单例对象（`jobject`）；加了
//! `@JvmStatic` 才是静态方法（`jclass`）。两种写法产生的**符号名完全相同**，而本模块对第二参
//! 只做占位、从不使用（参数名统一前缀 `_`），JNI 里 `jclass` 与 `jobject` 也只是同一个指针的
//! 不同语义标签 —— 所以两种 Kotlin 写法都能正确链接。
//!
//! 结论：**不要**在这里对第二参做任何类操作（`FindClass`/`GetStaticMethodID` 之类）。真需要
//! 类引用时自己 `env.find_class(...)` 拿，别依赖这个参数的类型标签。
//!
//! | JNI 符号 | Kotlin 签名 | JNI 描述符 |
//! |---|---|---|
//! | `nativeAbiVersion` | `(): String` | `()Ljava/lang/String;` |
//! | `nativeCreate` | `(String): String` | `(Ljava/lang/String;)Ljava/lang/String;` |
//! | `nativeStep` | `(Long, String, Long): String` | `(JLjava/lang/String;J)Ljava/lang/String;` |
//! | `nativeCall` | `(Long, String, Long): String` | `(JLjava/lang/String;J)Ljava/lang/String;` |
//! | `nativeQuery` | `(Long, String): String` | `(JLjava/lang/String;)Ljava/lang/String;` |
//! | `nativeDispose` | `(Long, Long): String` | `(JJ)Ljava/lang/String;` |
//!
//! Kotlin 的 `Long` 是 JNI 的 `jlong`（描述符 `J`），**不是**对象类型。
//!
//! ## 两个硬性约定
//!
//! 1. **不抛异常**（§12）：每个入口都用 [`std::panic::catch_unwind`] 兜住，失败通过返回信封
//!    表达。这正是 `Cargo.toml` 刻意保留 `panic = "unwind"` 的原因——`abort` 会让整个应用
//!    SIGABRT，而 unwind 能转成一次失败调用。
//! 2. 本模块**只做搬运**：`jstring` ↔ `String` 的转换 + `catch_unwind`，业务全在
//!    [`crate::registry`]。于是主机侧单测（不启动 JVM）能覆盖除符号绑定外的全部行为。
//!
//! `catch_unwind` 的闭包要求 `UnwindSafe`，而它捕获了 `&mut JNIEnv`。这里用
//! [`AssertUnwindSafe`] 是合理的：panic 之后我们**不信任**被毒化的状态，而是丢弃本次调用的
//! 结果、只返回失败信封；`JNIEnv` 由 JVM 拥有，本模块也不缓存它的任何东西。

// JNI 的 C 符号名必须逐字匹配（`Java_app_zemote_core_NativeCore_nativeStep`），
// 该命名规范与 Rust 的 snake_case 约定冲突，故整模块关闭该 lint。
#![allow(non_snake_case)]

use std::any::Any;
use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::jlong;
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::Value;

use crate::codec;
use crate::registry;

/// `nativeAbiVersion()` → `()Ljava/lang/String;`
#[no_mangle]
pub extern "system" fn Java_app_zemote_core_NativeCore_nativeAbiVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    finish(&mut env, catch_unwind(AssertUnwindSafe(registry::abi_version)))
}

/// `nativeCreate(String)` → `(Ljava/lang/String;)Ljava/lang/String;`
#[no_mangle]
pub extern "system" fn Java_app_zemote_core_NativeCore_nativeCreate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    config_json: JString<'local>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        match read_string(&mut env, &config_json, "configJson") {
            Ok(config_json) => registry::create(&config_json),
            Err(envelope) => envelope,
        }
    }));
    finish(&mut env, outcome)
}

/// `nativeStep(long, String, long)` → `(JLjava/lang/String;J)Ljava/lang/String;`
#[no_mangle]
pub extern "system" fn Java_app_zemote_core_NativeCore_nativeStep<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    input_json: JString<'local>,
    now_ms: jlong,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        match read_string(&mut env, &input_json, "inputJson") {
            Ok(input_json) => registry::step(handle as u64, &input_json, now_ms),
            Err(envelope) => envelope,
        }
    }));
    finish(&mut env, outcome)
}

/// `nativeCall(long, String, long)` → `(JLjava/lang/String;J)Ljava/lang/String;`
#[no_mangle]
pub extern "system" fn Java_app_zemote_core_NativeCore_nativeCall<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    command_json: JString<'local>,
    now_ms: jlong,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        match read_string(&mut env, &command_json, "commandJson") {
            Ok(command_json) => registry::call(handle as u64, &command_json, now_ms),
            Err(envelope) => envelope,
        }
    }));
    finish(&mut env, outcome)
}

/// `nativeQuery(long, String)` → `(JLjava/lang/String;)Ljava/lang/String;`
#[no_mangle]
pub extern "system" fn Java_app_zemote_core_NativeCore_nativeQuery<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query_json: JString<'local>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        match read_string(&mut env, &query_json, "queryJson") {
            Ok(query_json) => registry::query(handle as u64, &query_json),
            Err(envelope) => envelope,
        }
    }));
    finish(&mut env, outcome)
}

/// `nativeDispose(long, long)` → `(JJ)Ljava/lang/String;`
#[no_mangle]
pub extern "system" fn Java_app_zemote_core_NativeCore_nativeDispose<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now_ms: jlong,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| registry::dispose(handle as u64, now_ms)));
    finish(&mut env, outcome)
}

// ===========================================================================
// 搬运与兜底
// ===========================================================================

/// 把 Java 字符串读成 Rust `String`。失败返回**已经组好的** `badInput` 信封。
fn read_string(env: &mut JNIEnv, value: &JString, field: &str) -> Result<String, Value> {
    env.get_string(value)
        .map(String::from)
        .map_err(|error| codec::bad_input(format!("读取 {field} 失败: {error}")))
}

/// 收口：`Value` → JSON 文本 → `jstring`；panic 则换成 §12 的 `panic` 信封。
///
/// 返回 null 只在 `new_string` 失败（JVM 侧 OOM 等）时发生——那时也没有更好的办法。
fn finish(env: &mut JNIEnv, outcome: Result<Value, Box<dyn Any + Send>>) -> jstring {
    let envelope = match outcome {
        Ok(envelope) => envelope,
        Err(payload) => codec::panic_envelope(panic_message(payload.as_ref())),
    };
    // `Value` 的序列化不会失败；兜底串保证任何情况下都返回合法 JSON。
    let text = serde_json::to_string(&envelope).unwrap_or_else(|_| FALLBACK_ENVELOPE.to_string());
    match env.new_string(text) {
        Ok(text) => text.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 极端兜底：连信封都序列化不出来时的常量返回。
const FALLBACK_ENVELOPE: &str =
    r#"{"ok":false,"error":{"kind":"panic","message":"envelope serialization failed"},"events":[]}"#;

/// 从 `catch_unwind` 的载荷里取一段可读文本。
///
/// §12：日志可以详细，但**不**把路径/内部结构原样透给用户可见文案——这里只取 panic 自带的
/// 文本（`&str` / `String`），其它载荷一律给固定串。
fn panic_message(payload: &(dyn Any + Send)) -> String {
    if let Some(text) = payload.downcast_ref::<&str>() {
        return (*text).to_string();
    }
    if let Some(text) = payload.downcast_ref::<String>() {
        return text.clone();
    }
    "unknown panic payload".to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// §12：panic 载荷的两种常见形态都能取到文本。
    #[test]
    fn panic_message_reads_both_payload_shapes() {
        let text: &str = "boom";
        assert_eq!(panic_message(&text), "boom");
        assert_eq!(panic_message(&String::from("boom")), "boom");
        assert_eq!(panic_message(&42_u32), "unknown panic payload");
    }

    /// §12：捕获到的 panic 必须变成 `{"ok":false,"error":{"kind":"panic",...}}`，
    /// 且 `events` 键存在（§3 不省略键）。
    #[test]
    fn caught_panic_becomes_a_panic_envelope() {
        let outcome: Result<Value, Box<dyn Any + Send>> =
            catch_unwind(AssertUnwindSafe(|| -> Value { panic!("模拟核心内部 panic") }));
        let envelope = match outcome {
            Ok(value) => value,
            Err(payload) => codec::panic_envelope(panic_message(payload.as_ref())),
        };
        assert_eq!(envelope["ok"], serde_json::json!(false));
        assert_eq!(envelope["error"]["kind"], serde_json::json!("panic"));
        assert_eq!(envelope["error"]["message"], serde_json::json!("模拟核心内部 panic"));
        assert!(envelope.get("events").is_some());
    }

    /// 兜底串本身必须是合法 JSON（否则 Kotlin 侧解析会炸）。
    #[test]
    fn fallback_envelope_is_valid_json() {
        let parsed: Value = serde_json::from_str(FALLBACK_ENVELOPE).expect("必须是合法 JSON");
        assert_eq!(parsed["error"]["kind"], serde_json::json!("panic"));
    }
}
