// 版本在这里集中声明：插件版本只写一次，模块里 apply 不带版本。
// 选型依据（2026-10-16 实测）：AGP 8.13.2 + Kotlin 2.3.20 + Gradle 9.1.0 这套在本机
// 已被旧工程验证能跑起来（JDK 只有 DevEco JBR 25 的情况下也能启动构建）。
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    // Kotlin 2.x 起 Compose 编译器随 Kotlin 发版，版本必须与 Kotlin 一致。
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    // 序列化插件的版本同样必须与 Kotlin 一致（FFI 边界与持久化都用 kotlinx-serialization）。
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
}
