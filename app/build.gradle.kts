import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// ======================================================================
// 项目属性开关（可用 -Pxxx=yyy 或 gradle.properties 注入）：
//   fgogotran.builtinTtsModel
//       打包到 APK assets/sherpa_builtin_models/<id>/ 里的内置模型。
//       默认 "vits-zh-ll"（官方中文多音色 113MB，方案 A 映射齐全）。
//       取值：vits-zh-ll / vits-zh-fanchen-C / kokoro-multi-v1_1-int8 / none
//   fgogotran.includeSherpaRuntime
//       是否启用 sherpa-onnx-android AAR（JNI 推理引擎）。默认 true。
//       若只想用云端 Azure TTS，可设为 false 以减小 APK 体积。
// ======================================================================
val builtinTtsModel = (project.findProperty("fgogotran.builtinTtsModel") as? String)
    ?.takeIf { it.isNotBlank() } ?: "vits-zh-ll"
val includeSherpaRuntime = (project.findProperty("fgogotran.includeSherpaRuntime") as? String)
    ?.toBooleanStrictOrNull() ?: true

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

    // generated assets：内置 Sherpa 模型由 Gradle 下载后放在这里
    sourceSets {
        getByName("main").assets.srcDir(
            "$buildDir/generated/assets/sherpa_builtin"
        )
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
        // 不要压缩 onnx / voices.bin / dict / tokens 等大文件，否则运行时 AssetManager.openFd 会失败
        noCompress += listOf(
            "onnx", "bin", "pb", "txt", "dict", "fst", "data", "so"
        )
    }

    packaging {
        // sherpa-onnx AAR 自带 libonnxruntime.so，与 PaddleOCR 引入的 onnxruntime-android 重复
        jniLibs.pickFirsts += listOf(
            "lib/arm64-v8a/libonnxruntime.so",
            "lib/armeabi-v7a/libonnxruntime.so",
            "lib/x86_64/libonnxruntime.so",
            "lib/x86/libonnxruntime.so"
        )
    }
}

// ======================================================================
// 内置 TTS 模型：下载（带缓存）→ 解压并剥离 wrapper 目录 → 拷贝到 generated assets
// 执行时机：自动挂到 mergeDebugAssets / mergeReleaseAssets 之前
// ======================================================================
data class BuiltinModelSpec(val id: String, val url: String, val approxBytes: Long)

val builtinCatalogBuiltin = mapOf(
    "vits-zh-ll" to BuiltinModelSpec(
        id = "vits-zh-ll",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-vits-zh-ll.tar.bz2",
        approxBytes = 113L * 1024 * 1024
    ),
    "vits-zh-fanchen-C" to BuiltinModelSpec(
        id = "vits-zh-fanchen-C",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-fanchen-C.tar.bz2",
        approxBytes = 114L * 1024 * 1024
    ),
    "kokoro-multi-v1_1-int8" to BuiltinModelSpec(
        id = "kokoro-multi-v1_1-int8",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2",
        approxBytes = 140L * 1024 * 1024
    ),
    "piper-zh_CN-xiao_ya-medium" to BuiltinModelSpec(
        id = "piper-zh_CN-xiao_ya-medium",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-xiao_ya-medium.tar.bz2",
        approxBytes = 58L * 1024 * 1024
    )
)

val sherpaCacheDir = rootProject.file(".gradle/sherpa-tts-cache").apply { mkdirs() }
val sherpaGeneratedAssetsDir = file("$buildDir/generated/assets/sherpa_builtin")
val builtinSpec = builtinCatalogBuiltin[builtinTtsModel]

val downloadSherpaBuiltinModel = tasks.register("downloadSherpaBuiltinModel") {
    group = "fgogotran"
    description = "下载内置 Sherpa-ONNX TTS 模型归档 (model=$builtinTtsModel)"

    onlyIf {
        val enabled = builtinTtsModel.lowercase() != "none"
        if (!enabled) {
            logger.lifecycle("[SherpaBuiltin] fgogotran.builtinTtsModel=none，跳过内置模型下载")
        }
        enabled
    }

    val spec = builtinSpec
    doFirst {
        checkNotNull(spec) {
            "未知的 fgogotran.builtinTtsModel=$builtinTtsModel；允许的值：${builtinCatalogBuiltin.keys} + none"
        }
    }

    if (spec != null) {
        val archive = File(sherpaCacheDir, "$builtinTtsModel.archive")
        inputs.property("modelId", spec.id)
        inputs.property("url", spec.url)
        inputs.property("minBytes", spec.approxBytes)
        outputs.file(archive)

        doLast {
            if (archive.isFile && archive.length() > (spec.approxBytes * 70 / 100)) {
                logger.lifecycle("[SherpaBuiltin] 复用缓存 ${archive.path} (${archive.length()} bytes)")
                return@doLast
            }
            logger.lifecycle("[SherpaBuiltin] 开始下载 ${spec.url}")
            archive.parentFile.mkdirs()
            val tmp = File(archive.path + ".part")
            URI.create(spec.url).toURL().openStream().use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            check(tmp.length() > (spec.approxBytes * 50 / 100)) {
                "下载后文件太小 (${tmp.length()} bytes)，可能失败；URL=${spec.url}"
            }
            check(tmp.renameTo(archive)) {
                "无法将临时文件重命名为 ${archive.path}"
            }
            logger.lifecycle("[SherpaBuiltin] 下载完成 (${archive.length()} bytes)")
        }
    }
}

val prepareSherpaBuiltinAssets = tasks.register("prepareSherpaBuiltinAssets", Copy::class.java) {
    group = "fgogotran"
    description = "解压内置 Sherpa-ONNX 本地 TTS 模型到 generated assets (model=$builtinTtsModel)"

    onlyIf { builtinTtsModel.lowercase() != "none" }

    dependsOn(downloadSherpaBuiltinModel)
    val spec = builtinSpec
    val archive = File(sherpaCacheDir, "$builtinTtsModel.archive")

    inputs.file(archive)
    outputs.dir(sherpaGeneratedAssetsDir)

    if (spec != null) {
        // 解压 + 剥离顶层目录，输出到 assets/sherpa_builtin_models/<id>/
        from(
            tarTree(resources.bzip2(archive)).matching {
                eachFile {
                    val segments = path.split('/', limit = 2)
                    path = if (segments.size == 2) {
                        "sherpa_builtin_models/${spec.id}/" + segments[1]
                    } else {
                        "sherpa_builtin_models/${spec.id}/$path"
                    }
                }
                includeEmptyDirs = false
            }
        )
        into(sherpaGeneratedAssetsDir)
    }
}

tasks.whenTaskAdded {
    val name = this.name
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        this.dependsOn(prepareSherpaBuiltinAssets)
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

    // ===== Sherpa-ONNX 本地离线 TTS（默认开启推理引擎 + 默认内置中文 zh-ll 模型） =====
    // 关闭方式（二选一）：
    //   1) 仅关闭 JNI 引擎，仍保留 Provider 抽象：-Pfgogotran.includeSherpaRuntime=false
    //   2) 仅不内置模型（保留引擎，支持手动下载）：   -Pfgogotran.builtinTtsModel=none
    if (includeSherpaRuntime) {
        implementation("com.github.k2-fsa:sherpa-onnx:1.13.5") {
            // Android 只需要 AAR；桌面 JVM/JNI 传递依赖会导致类重复与 so 冲突
            exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
            exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-native-lib-linux-aarch64")
            exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-native-lib-linux-x64")
            exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-native-lib-osx-aarch64")
            exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-native-lib-osx-x64")
            exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-native-lib-win-arm64")
            exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-native-lib-win-x64")
        }
    } else {
        logger.lifecycle("[SherpaBuiltin] includeSherpaRuntime=false，跳过 sherpa-onnx-android AAR 引入")
    }
    // 可选：本地 AAR 方案（比 maven 拉取更稳，避免版本对不齐时断网重试）
    //   下载 https://github.com/k2-fsa/sherpa-onnx/releases 下的 sherpa-onnx-android-*.aar
    //   重命名为 libs/sherpa-onnx-android.aar  →  改走下面这行：
    // implementation(files("libs/sherpa-onnx-android.aar"))

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
