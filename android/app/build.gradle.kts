import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------------
// 发布签名
//
// 本地：android/key.properties（已 gitignore）→ ../scratch/zemote-release.p12
// CI  ：workflow 从 GitHub Secrets 写出同名的 key.properties
//
// 这份密钥是**本项目专属**的（别名 zemote），不复用 android-shell、也不复用上游
// zemote 的——共用签名只对同一个 applicationId 有意义。见 android/key.properties.example。
// ---------------------------------------------------------------------------
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

fun propOrEnv(name: String): String? =
    keystoreProperties.getProperty(name) ?: System.getenv("ANDROID_${name.uppercase()}")

android {
    namespace = "app.zemote"
    compileSdk = 36

    // 显式声明 NDK 版本，否则 AGP 用自己内置的默认版本去找 NDK，找不到就只会打印
    // "Unable to strip the following libraries" 然后把 .so 原样打包 —— 本地与 CI 行为不一致
    // （CI 装了 28.2.13676358，本地也有同一份）。声明后 AGP 会用它 strip 掉 .so 的调试段。
    // 这个版本号必须与 `.github/workflows/android.yml` 的 NDK_VERSION 一致。
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.zemote"
        minSdk = 26
        targetSdk = 36

        // 版本号沿用旧工程的"构建时间"方案（CI 通过环境变量注入）：
        //   versionCode = yyMMddHH（UTC+8），时钟只向前走，保证每次都能覆盖安装
        //   versionName = "<base>-dev.<yyyyMMdd.HHmm>"（分支构建）/ "<base>"（tag 构建）
        // 本地构建用下面的兜底值。
        versionCode = System.getenv("ZCODE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ZCODE_VERSION_NAME") ?: "0.1.0-local"

        // 只出 arm64-v8a 单 ABI：Rust .so 只交叉编译这一份，现有真机也全是 arm64。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val storeFileProp = propOrEnv("storeFile")
            val storeFileResolved = storeFileProp?.takeIf { it.isNotBlank() }
                ?.let { rootProject.file(it) }
            if (storeFileResolved?.exists() == true) {
                keyAlias = propOrEnv("keyAlias")
                keyPassword = propOrEnv("keyPassword")
                storeFile = storeFileResolved
                storePassword = propOrEnv("storePassword")
                storeType = propOrEnv("storeType") ?: when {
                    storeFileResolved.name.endsWith(".p12", true) ||
                        storeFileResolved.name.endsWith(".pfx", true) -> "PKCS12"
                    else -> "JKS"
                }
            } else if (storeFileResolved != null) {
                logger.warn(
                    "WARNING: keystore $storeFileResolved not found — release 构建将回退 debug 签名。" +
                        " 见 android/key.properties.example。"
                )
            }
        }
    }

    buildTypes {
        release {
            val releaseSigning = signingConfigs.findByName("release")
            signingConfig = if (releaseSigning?.storeFile != null) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // 界面层要用的 lucide 对应图标（PushPin / Archive / Palette / Folder / AttachFile…）
    // 都在 extended 里；release 构建由 R8 裁掉未引用项。
    implementation("androidx.compose.material:material-icons-extended")

    // 扫码配对：CameraX 预览 + ML Kit bundled 条码识别（不依赖 Google Play 服务）。
    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // 中继 WebSocket 客户端 —— 宿主侧的 `RelayTransport` 实现（FFI 契约里的 ws_* 动作）。
    //
    // 刻意停在 4.x：okhttp 5.x 会传递引入 `okhttp-android`（一个 AAR），它要求
    // compileSdk >= 37，而本项目是 36 —— 会直接在 `:app:checkDebugAarMetadata` 失败。
    // 4.12.0 是纯 JAR，不参与 AAR 元数据检查，也省得 CI 多装一个 platform。
    // 我们只用到 OkHttpClient / Request / WebSocket / WebSocketListener，4.x 全都有。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 引擎的事件循环、定时器、以及 UI 层的状态流。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // FFI 边界的信封与事件、持久化都用它。**不要改用 org.json**：那个类在 JVM 单测里是空壳
    // （返回 null），会逼着引入 Robolectric。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // 设备列表与设置的持久化。
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
