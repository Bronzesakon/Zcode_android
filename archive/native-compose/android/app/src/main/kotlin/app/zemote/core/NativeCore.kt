package app.zemote.core

/**
 * Rust 协议核心（`libzemote_ffi.so`）的 JNI 面。
 *
 * 签名严格对应 `docs/FFI.md` §2 的符号表——**不要改动**。JNI 符号名由类名 + 方法名拼出，
 * 改包名/方法名会让 Rust 侧的 `Java_app_zemote_core_NativeCore_*` 全部失配。
 *
 * 所有方法都不抛异常（Rust 侧 `catch_unwind`），失败通过返回信封
 * （`{"ok":false,"error":{...},"events":[...]}`）表达；**入参出参都是 JSON 字符串**。
 *
 * 线程约定（契约 §11）：同一个 `handle` 上的调用**不可重入**。上层请统一走
 * [app.zemote.engine.ZemoteEngine]——它把所有 native 调用收敛到一个专用单线程 dispatcher。
 * 本对象只做转发，不承担调度责任。
 *
 * 注意：JVM 单测**不要**碰这个对象（`System.loadLibrary` 在宿主 JVM 上会失败），
 * 引擎逻辑一律通过 [NativeBridge] / `FakeNativeBridge` 测试。
 */
object NativeCore {

    init {
        System.loadLibrary("zemote_ffi")
    }

    /** 版本探针。返回 `{"ok":true,"abi":1,"protocolVersion":"0.1.0","features":[...]}`。 */
    external fun nativeAbiVersion(): String

    /** 建会话。返回 `{"ok":true,"handle":N,"events":[...]}`。 */
    external fun nativeCreate(configJson: String): String

    /** 喂输入取事件。`handle` 是 [nativeCreate] 返回的 u64。 */
    external fun nativeStep(handle: Long, inputJson: String, nowMs: Long): String

    /** 发起操作（bootstrap / open_bridge / …）。返回 `operation` 令牌供关联 `completed`。 */
    external fun nativeCall(handle: Long, commandJson: String, nowMs: Long): String

    /** 读状态快照（目前只有 `{"query":"snapshot"}`）。 */
    external fun nativeQuery(handle: Long, queryJson: String): String

    /** 销毁会话。对已销毁的 handle 再调用是幂等失败（不 panic）。 */
    external fun nativeDispose(handle: Long, nowMs: Long): String
}
