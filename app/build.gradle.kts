plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.fgogotran"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fgogotran"
        minSdk = 30
        targetSdk = 34
        versionCode = 7
        versionName = "2.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    // ===== CI 签名策略 =====
    // 优先级：
    //  1. 环境变量 RELEASE_KEYSTORE_BASE64（GitHub Secrets 提供，自动解码写入临时文件）
    //  2. 环境变量 ANDROID_SIGNING_STORE_FILE 本地路径
    //  3. 否则 release 仍使用 debug 证书（保证 CI 总能出包，不会因为缺 secret 挂掉）
    signingConfigs {
        val envStoreFile = System.getenv("ANDROID_SIGNING_STORE_FILE").orEmpty()
        val envStorePass = System.getenv("ANDROID_SIGNING_STORE_PASSWORD").orEmpty()
        val envKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS").orEmpty()
        val envKeyPass = System.getenv("ANDROID_SIGNING_KEY_PASSWORD").orEmpty()

        create("releaseFromEnv") {
            isV1SigningEnabled = true
            isV2SigningEnabled = true
            storeFile = if (envStoreFile.isNotBlank()) file(envStoreFile) else null
            storePassword = envStorePass.ifBlank { "android" }
            keyAlias = envKeyAlias.ifBlank { "androiddebugkey" }
            keyPassword = envKeyPass.ifBlank { "android" }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 若用户提供了合法签名文件就用它，否则 fallback 到 debug 证书，保证 release 构建永远成功。
            val releaseSigning = signingConfigs.getByName("releaseFromEnv")
            signingConfig = if (releaseSigning.storeFile?.exists() == true) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity + Navigation
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room DB
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ML Kit OCR (bundled so OCR works without Google Play services/model delivery)
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // PaddleOCR PP-OCRv6 ONNX runtime and polygon post-processing
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("org.locationtech.jts:jts-core:1.19.0")

    // ===== Sherpa-ONNX 本地离线 TTS（可选启用） =====
    // 项目已通过 TtsProvider 抽象层支持 Sherpa-ONNX。
    // 启用方法（任选其一）：
    //   1) 从 mavenCentral 拉取（需与 onnxruntime-android 版本对齐）：
    //      implementation("io.github.k2-fsa:sherpa-onnx-android:1.14.0")
    //   2) 从 https://github.com/k2-fsa/sherpa-onnx/releases 下载官方 AAR
    //      放入 app/libs/sherpa-onnx-android.aar，再：
    //      implementation(files("libs/sherpa-onnx-android.aar"))
    // 启用后即可使用 Piper / VITS / Kokoro / Matcha 等 100+ 社区模型本地合成。

    // Apache Commons Compress：SherpaOnnxModelRegistry 解压 tar.bz2 模型包
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Ktor HTTP client
    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // AppCompat (for AlertDialog from Service context)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

}
