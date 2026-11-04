package app.zemote.core

/**
 * FFI 边界的可测试抽象。
 *
 * 存在的唯一理由：JVM 单测加载不了 `.so`（`System.loadLibrary("zemote_ffi")` 只对 arm64
 * Android 有效）。引擎的全部逻辑都必须能靠 `FakeNativeBridge` 在宿主 JVM 上跑起来，
 * 所以引擎**只依赖本接口**，绝不直接引用 [NativeCore]。
 *
 * 所有方法都返回**原始 JSON 字符串**（契约 §3 的返回信封），解析交给引擎层。
 * 所有方法都是同步阻塞调用；调用方负责把它们收敛到单线程（契约 §11）。
 */
interface NativeBridge {

    /** 对应 `nativeAbiVersion()`：`{"ok":true,"abi":1,...}`。 */
    fun abiVersion(): String

    /** 对应 `nativeCreate(configJson)`：`{"ok":true,"handle":N,"events":[...]}`。 */
    fun create(configJson: String): String

    /** 对应 `nativeStep(handle, inputJson, nowMs)`。 */
    fun step(handle: Long, inputJson: String, nowMs: Long): String

    /** 对应 `nativeCall(handle, commandJson, nowMs)`。 */
    fun call(handle: Long, commandJson: String, nowMs: Long): String

    /** 对应 `nativeQuery(handle, queryJson)`。 */
    fun query(handle: Long, queryJson: String): String

    /** 对应 `nativeDispose(handle, nowMs)`。 */
    fun dispose(handle: Long, nowMs: Long): String
}

/** 真实实现：逐字转发到 [NativeCore]。 */
class JniNativeBridge : NativeBridge {
    override fun abiVersion(): String = NativeCore.nativeAbiVersion()

    override fun create(configJson: String): String = NativeCore.nativeCreate(configJson)

    override fun step(handle: Long, inputJson: String, nowMs: Long): String =
        NativeCore.nativeStep(handle, inputJson, nowMs)

    override fun call(handle: Long, commandJson: String, nowMs: Long): String =
        NativeCore.nativeCall(handle, commandJson, nowMs)

    override fun query(handle: Long, queryJson: String): String =
        NativeCore.nativeQuery(handle, queryJson)

    override fun dispose(handle: Long, nowMs: Long): String =
        NativeCore.nativeDispose(handle, nowMs)
}
