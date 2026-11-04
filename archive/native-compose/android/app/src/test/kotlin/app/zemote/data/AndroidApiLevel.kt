package app.zemote.data

import java.lang.reflect.Field

/**
 * 把 JVM 单测里的 `android.os.Build.VERSION.SDK_INT` 抬到 26。
 *
 * ## 为什么需要（不是绕过被测代码）
 *
 * DataStore 的 `FileMoves.atomicMoveTo`（`datastore-core` 的 FileMoves.android.kt）按 SDK 分支：
 *
 * ```
 * if (Build.VERSION.SDK_INT >= 26) Api26Impl.move(src, dst)   // Files.move(REPLACE_EXISTING)
 * else                             src.renameTo(dst)          // 老路径
 * ```
 *
 * JVM 单测跑的是 android.jar 桩，`SDK_INT` 恒为 0，于是走 `renameTo`。而
 * **Windows + JDK 17**（本机 Gradle 单测用的就是 `E:\AndroidSdk\jdk-17`）的
 * `File.renameTo` 在**目标文件已存在**时返回 `false`（JDK 25 会替换成功）——表现就是
 * DataStore「第一次写盘成功、第二次起必失败：Unable to rename xxx.tmp」。
 *
 * 本工程 `minSdk = 26`，真机上 `SDK_INT` 永远 ≥ 26，走的**就是** `Files.move` 那条分支。
 * 所以把桩值抬到 26 是让单测跑**生产分支**；不这么做，单测跑的反而是真机上永远到不了的
 * 老分支。
 *
 * ## 实现
 *
 * `SDK_INT` 在桩里是 `static final int`：先试 `Field.setInt`（非 final 时可写），
 * 不行就用 `sun.misc.Unsafe` 写静态字段（JDK 17 对 static final 只认这条路）。
 * 两种都失败就直接抛，不静默——静默会让 DataStore 的写盘测试莫名其妙地红。
 */
internal object AndroidApiLevel {

    const val TARGET_SDK = 26

    fun forceApi26() = setSdkInt(TARGET_SDK)

    private fun setSdkInt(value: Int) {
        val field = Class.forName("android.os.Build\$VERSION").getField("SDK_INT")
        field.isAccessible = true
        try {
            field.setInt(null, value)
            check(field.getInt(null) == value) { "SDK_INT 写入后读回不一致" }
            return
        } catch (_: Throwable) {
            // static final：走 Unsafe
        }
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod(
            "putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(unsafe, base, offset, value)
        check(field.getInt(null) == value) { "SDK_INT 写入后读回不一致（Unsafe 路径）" }
    }
}
