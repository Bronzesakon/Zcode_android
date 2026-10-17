import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release signing. Local builds read android/key.properties (git-ignored);
// CI provides the same values via GitHub Secrets as env vars so every
// published APK shares ONE signature and can overwrite-install.
//
// The material is THIS project's own keystore (scratch/zemote-release.p12, alias
// zemote) — deliberately not shared with android-shell or upstream zemote, since
// a shared signature only means anything for the same applicationId, and
// app.zemote is its own app. See android/key.properties.example.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

fun propOrEnv(name: String): String? =
    keystoreProperties.getProperty(name) ?: System.getenv("ANDROID_${name.uppercase()}")

android {
    namespace = "app.zemote"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "app.zemote"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            val storeFileProp = propOrEnv("storeFile")
            // Resolved against the android/ module (rootProject), which is where
            // key.properties lives: locally the path is ../scratch/…, and CI
            // writes an absolute path into $RUNNER_TEMP.
            val storeFileResolved = storeFileProp?.takeIf { it.isNotBlank() }
                ?.let { rootProject.file(it) }
            if (storeFileResolved?.exists() == true) {
                keyAlias = propOrEnv("keyAlias")
                keyPassword = propOrEnv("keyPassword")
                storeFile = storeFileResolved
                storePassword = propOrEnv("storePassword")
                // Explicit, because a PKCS12 store named .jks would otherwise be
                // read as legacy JKS and fail to load.
                storeType = propOrEnv("storeType") ?: when {
                    storeFileResolved.name.endsWith(".p12", true) ||
                        storeFileResolved.name.endsWith(".pfx", true) -> "PKCS12"
                    else -> "JKS"
                }
            } else if (storeFileResolved != null) {
                // A fresh clone has no scratch/ (it is git-ignored): say so here,
                // where the cause is obvious, instead of failing later inside the
                // signing task.
                logger.warn(
                    "WARNING: keystore $storeFileResolved not found — the release build will " +
                        "be signed with the debug key. See android/key.properties.example."
                )
            }
        }
    }

    buildTypes {
        release {
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null && releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            } else {
                // Fall back to debug keys so `flutter build apk` still works
                // when no keystore is configured.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
