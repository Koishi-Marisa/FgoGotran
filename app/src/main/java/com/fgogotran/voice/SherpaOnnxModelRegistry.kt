package com.fgogotran.voice

import android.content.Context
import android.os.StatFs
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sherpa-ONNX 本地 TTS 模型注册表。
 *
 * 职责：
 *  - 提供内置推荐模型清单（中文、日文、英文，多音色）
 *  - 管理下载、解压、校验（sha256）与安装目录
 *  - 枚举已安装模型供 UI 与角色映射使用
 *
 * 目录布局：
 *  <filesDir>/sherpa_tts/
 *      registry.json                已安装模型清单（持久化）
 *      models/<modelId>/
 *          model.onnx
 *          tokens.txt
 *          ...
 */
@Singleton
class SherpaOnnxModelRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private val tag = "SherpaModel"
    private val rootDir: File by lazy { File(context.filesDir, "sherpa_tts").also { it.mkdirs() } }
    private val modelsDir: File by lazy { File(rootDir, "models").also { it.mkdirs() } }
    private val registryFile: File by lazy { File(rootDir, "installed.json") }

    /** 下载/安装协程作用域：应用级，不随 UI/页面销毁而取消。 */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** App 内下载用 HTTP 客户端（大文件不设请求超时）。 */
    private val httpClient: HttpClient by lazy {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000L
                requestTimeoutMillis = Long.MAX_VALUE // 模型包可能数百 MB，不设整体超时
                socketTimeoutMillis = 300_000L // 5 分钟无数据才判定超时（慢网下大文件下载）
            }
        }
    }

    // ==================================================================
    // 内置推荐模型清单（可按需扩展，社区模型由用户导入文件实现）
    // ==================================================================
    fun builtinCatalog(): List<SherpaOnnxModelManifest> = listOf(
        // ===== 中文多 speaker（方案 A 首选）=====
        SherpaOnnxModelManifest(
            modelId = "vits-zh-fanchen-C",
            displayName = "中文超多音色 (fanchen-C · 187人)",
            modelType = SherpaModelType.VITS_PLAIN,
            language = "zh",
            speakerCount = 187,
            sampleRate = 16000,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-fanchen-C.tar.bz2",
            archiveSizeBytes = 114L * 1024 * 1024,
            unpackedSizeBytes = 240L * 1024 * 1024,
            sha256 = "",
            notes = "社区贡献，187 种男女老少音色；方案 A 首选，内置 60+ FGO 角色映射"
        ),
        SherpaOnnxModelManifest(
            modelId = "vits-zh-ll",
            displayName = "中文多音色 (zh-ll · 5人 · 官方推荐)",
            modelType = SherpaModelType.VITS_PLAIN,
            language = "zh",
            speakerCount = 5,
            sampleRate = 16000,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-vits-zh-ll.tar.bz2",
            archiveSizeBytes = 113L * 1024 * 1024,
            unpackedSizeBytes = 230L * 1024 * 1024,
            sha256 = "",
            notes = "Sherpa 官方推荐中文模型，5 种音色稳定，约 113MB；默认内置"
        ),

        // ===== 中文单 speaker（Piper 社区，体积更小）=====
        SherpaOnnxModelManifest(
            modelId = "piper-zh_CN-huayan-medium",
            displayName = "中文女声 (Piper · 华研)",
            modelType = SherpaModelType.PIPER_VITS,
            language = "zh",
            speakerCount = 1,
            sampleRate = 22050,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-huayan-medium.tar.bz2",
            archiveSizeBytes = 64L * 1024 * 1024,
            unpackedSizeBytes = 140L * 1024 * 1024,
            sha256 = "",
            notes = "Piper 中文女声，约 64MB（含 espeak-ng-data，开箱即用）"
        ),
        SherpaOnnxModelManifest(
            modelId = "piper-zh_CN-chaowen-medium",
            displayName = "中文男声 (Piper · 超稳)",
            modelType = SherpaModelType.PIPER_VITS,
            language = "zh",
            speakerCount = 1,
            sampleRate = 22050,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-chaowen-medium.tar.bz2",
            archiveSizeBytes = 58L * 1024 * 1024,
            unpackedSizeBytes = 130L * 1024 * 1024,
            sha256 = "",
            notes = "Piper 中文男声，约 58MB"
        ),
        SherpaOnnxModelManifest(
            modelId = "piper-zh_CN-xiao_ya-medium",
            displayName = "中文少女声 (Piper · 小雅)",
            modelType = SherpaModelType.PIPER_VITS,
            language = "zh",
            speakerCount = 1,
            sampleRate = 22050,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-xiao_ya-medium.tar.bz2",
            archiveSizeBytes = 58L * 1024 * 1024,
            unpackedSizeBytes = 130L * 1024 * 1024,
            sha256 = "",
            notes = "Piper 中文少女/萝莉音色，约 58MB"
        ),

        // ===== 中文单角色 VITS（社区微调，音色 1:1 对应二次元角色）=====
        SherpaOnnxModelManifest(
            modelId = "vits-zh-single-keqing",
            displayName = "单角色女声 (刻晴音色)",
            modelType = SherpaModelType.VITS_PLAIN,
            language = "zh",
            speakerCount = 1,
            sampleRate = 22050,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-keqing.tar.bz2",
            archiveSizeBytes = 115L * 1024 * 1024,
            unpackedSizeBytes = 240L * 1024 * 1024,
            sha256 = "",
            notes = "单角色：刻晴风格女声（社区微调，适合高冷系 Saber/凛 类气质）"
        ),
        SherpaOnnxModelManifest(
            modelId = "vits-zh-single-bronya",
            displayName = "单角色女声 (布洛妮娅音色)",
            modelType = SherpaModelType.VITS_PLAIN,
            language = "zh",
            speakerCount = 1,
            sampleRate = 22050,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-bronya.tar.bz2",
            archiveSizeBytes = 115L * 1024 * 1024,
            unpackedSizeBytes = 240L * 1024 * 1024,
            sha256 = "",
            notes = "单角色：布洛妮娅风格女声（冷淡/三无声线，适合童谣/AI系角色）"
        ),
        SherpaOnnxModelManifest(
            modelId = "vits-zh-single-theresa",
            displayName = "单角色少女声 (德丽莎音色)",
            modelType = SherpaModelType.VITS_PLAIN,
            language = "zh",
            speakerCount = 1,
            sampleRate = 22050,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-theresa.tar.bz2",
            archiveSizeBytes = 115L * 1024 * 1024,
            unpackedSizeBytes = 240L * 1024 * 1024,
            sha256 = "",
            notes = "单角色：德丽莎风格少女声（幼齿/萝莉声线，适合幼贞、伊莉雅）"
        ),

        // ===== 中英混合 MeloTTS =====
        SherpaOnnxModelManifest(
            modelId = "melo-tts-zh_en",
            displayName = "中英混合 (MeloTTS)",
            modelType = SherpaModelType.VITS_PLAIN,
            language = "zh_en",
            speakerCount = 1,
            sampleRate = 44100,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2",
            archiveSizeBytes = 159L * 1024 * 1024,
            unpackedSizeBytes = 350L * 1024 * 1024,
            sha256 = "",
            notes = "中英混读自然，Master 台词夹杂术语/外来语也能读顺"
        ),

        // ===== Kokoro-82M：多语言高音质（FP32 / INT8 两档）=====
        SherpaOnnxModelManifest(
            modelId = "kokoro-multi-v1_1",
            displayName = "多语言高音质 (Kokoro-82M v1.1 · FP32)",
            modelType = SherpaModelType.KOKORO_82M,
            language = "multi",
            speakerCount = 103,
            sampleRate = 24000,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2",
            archiveSizeBytes = 348L * 1024 * 1024,
            unpackedSizeBytes = 850L * 1024 * 1024,
            sha256 = "",
            notes = "Kokoro 官方 v1.1 FP32，103 音色含中文女声 zf_* / 男声 zm_*（3~102），音质接近商业 TTS；体积大（~348MB），高端机推荐"
        ),
        SherpaOnnxModelManifest(
            modelId = "kokoro-multi-v1_1-int8",
            displayName = "多语言高音质 (Kokoro-82M v1.1 · INT8 量化)",
            modelType = SherpaModelType.KOKORO_82M,
            language = "multi",
            speakerCount = 103,
            sampleRate = 24000,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2",
            archiveSizeBytes = 140L * 1024 * 1024,
            unpackedSizeBytes = 400L * 1024 * 1024,
            sha256 = "",
            notes = "Kokoro v1.1 INT8 量化版，体积仅 140MB，质量略损于 FP32；中端机推荐"
        ),
        SherpaOnnxModelManifest(
            modelId = "kokoro-multi-v1_0",
            displayName = "多语言高音质 (Kokoro-82M v1.0 · FP32)",
            modelType = SherpaModelType.KOKORO_82M,
            language = "multi",
            speakerCount = 53,
            sampleRate = 24000,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
            archiveSizeBytes = 333L * 1024 * 1024,
            unpackedSizeBytes = 800L * 1024 * 1024,
            sha256 = "",
            notes = "Kokoro v1.0 老版（53 音色），兼容性更好"
        )
    )

    // ==================================================================
    // 安装与下载
    // ==================================================================

    /**
     * 启动时执行：若 APK assets 里携带了 `sherpa_builtin_models/<id>/` 的内置模型，
     * 则把其内容异步复制到应用私有 filesDir（等同 installFromArchive 的效果）。
     * 幂等：每个 modelId 有自己的 `.asset-installed` 标记文件，已完成则 O(1) 跳过。
     */
    suspend fun ensureAssetsModelsInstalled(): Unit = withContext(Dispatchers.IO) {
        val root = "sherpa_builtin_models"
        val topList = runCatching { context.assets.list(root) }.getOrNull().orEmpty()
            .filter { it.isNotBlank() }
        if (topList.isEmpty()) return@withContext

        val catalog = builtinCatalog().associateBy { it.modelId }
        for (modelId in topList) {
            val target = installDirFor(modelId)
            val sentinel = File(target, ".asset-installed")
            if (sentinel.isFile) continue

            val manifest = catalog[modelId]
                ?: runCatching {
                    // 内置但不在 catalog 的模型 → 按目录条目做一个最小 manifest 推断
                    guessMinimalManifest(modelId, "$root/$modelId")
                }.getOrNull()
            if (manifest == null) continue

            FgoLogger.info(tag, "从 APK assets 静默安装内置模型 $modelId")
            target.deleteRecursively()
            target.mkdirs()
            try {
                copyAssetTree(srcDir = "$root/$modelId", targetDir = target)
                sentinel.writeText("ok")
                persistInstalled(manifest)
                FgoLogger.info(tag, "内置模型安装完成: $modelId (${target.listFiles()?.size ?: 0} entries)")
            } catch (t: Throwable) {
                runCatching { target.deleteRecursively() }
                FgoLogger.warn(tag, "内置模型安装失败 $modelId: ${t.message}")
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_ERROR,
                    category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                    eventId = "sherpa_builtin_install_failed",
                    title = "内置 TTS 模型安装失败",
                    message = "model=$modelId: ${t.stackTraceToString()}"
                )
            }
        }
    }

    private fun guessMinimalManifest(modelId: String, assetPrefix: String): SherpaOnnxModelManifest? {
        val entries = context.assets.list(assetPrefix).orEmpty().toList()
        val modelType = when {
            entries.any { it == "voices.bin" } -> SherpaModelType.KOKORO_82M
            entries.any { it == "espeak-ng-data" || (modelId.startsWith("piper-")) } -> SherpaModelType.PIPER_VITS
            else -> SherpaModelType.VITS_PLAIN
        }
        val lang = when {
            modelId.contains("zh") -> "zh"
            modelId.contains("ja") -> "ja"
            modelId.startsWith("kokoro") -> "multi"
            else -> "unknown"
        }
        return SherpaOnnxModelManifest(
            modelId = modelId,
            displayName = "内置模型 · $modelId",
            modelType = modelType,
            language = lang,
            speakerCount = if (modelId == "vits-zh-fanchen-C") 187 else if (modelId == "vits-zh-ll") 5 else 1,
            sampleRate = 22050,
            downloadUrl = "",
            archiveSizeBytes = 0L,
            unpackedSizeBytes = 0L,
            sha256 = "",
            notes = "APK 内置本地模型，启动即装"
        )
    }

    private fun copyAssetTree(srcDir: String, targetDir: File) {
        val stack = ArrayDeque<Pair<String, File>>()
        stack.addLast(srcDir to targetDir)
        val buf = ByteArray(64 * 1024)
        while (stack.isNotEmpty()) {
            val (src, dst) = stack.removeLast()
            val entries = context.assets.list(src).orEmpty()
            if (entries.isEmpty()) {
                // 叶子：是文件
                dst.parentFile?.mkdirs()
                context.assets.open(src).use { input ->
                    java.io.FileOutputStream(dst).use { out ->
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                }
            } else {
                // 目录：要么内容是子目录名，要么是文件名（list 非空）
                // 进一步判断是否真的是目录：尝试 list("src/entry") 是否成功且非空；若抛异常 IOException 则是文件
                for (entry in entries) {
                    val childSrc = if (src.isEmpty()) entry else "$src/$entry"
                    val childDst = File(dst, entry)
                    val isDir = try {
                        val sub = context.assets.list(childSrc)
                        sub != null && sub.isNotEmpty()
                    } catch (_: java.io.FileNotFoundException) { false }
                    catch (_: Exception) { false }
                    if (isDir) {
                        childDst.mkdirs()
                        stack.addLast(childSrc to childDst)
                    } else {
                        childDst.parentFile?.mkdirs()
                        context.assets.open(childSrc).use { input ->
                            java.io.FileOutputStream(childDst).use { out ->
                                var n: Int
                                while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
        }
    }

    /** 判断某模型是否已安装（目录存在且含可用的 .onnx 模型文件，损坏的安装允许重新下载） */
    fun isInstalled(modelId: String): Boolean {
        val dir = installDirFor(modelId)
        if (!dir.isDirectory) return false
        if (File(dir, "model.onnx").isFile) return true
        return dir.listFiles { f -> f.isFile && f.extension.equals("onnx", ignoreCase = true) }
            ?.isNotEmpty() == true
    }

    /** 该模型是否随 APK assets 内置（卸载后会在下次启动/刷新时自动重新安装，无需手动卸载）。 */
    fun isBundledInApk(modelId: String): Boolean {
        return runCatching {
            context.assets.list("sherpa_builtin_models").orEmpty().any { it == modelId }
        }.getOrDefault(false)
    }

    fun installDirFor(modelId: String): File = File(modelsDir, modelId)

    suspend fun installFromArchive(
        manifest: SherpaOnnxModelManifest,
        archiveFile: File,
        onProgress: ((percent: Int, message: String) -> Unit)? = null
    ): InstalledSherpaModel = withContext(Dispatchers.IO) {
        if (manifest.sha256.isNotBlank()) {
            onProgress?.invoke(2, "校验压缩包")
            val actual = sha256(archiveFile)
            if (actual.lowercase() != manifest.sha256.lowercase()) {
                throw SecurityException("SHA256 不匹配：期望 ${manifest.sha256.take(16)}… 实际 ${actual.take(16)}…")
            }
        }

        val dir = installDirFor(manifest.modelId)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        onProgress?.invoke(5, "解压模型文件")
        try {
            when {
                archiveFile.name.endsWith(".tar.bz2", ignoreCase = true) ||
                    archiveFile.name.endsWith(".tbz2", ignoreCase = true) -> {
                    extractTarBz2(archiveFile, dir) { p ->
                        onProgress?.invoke(5 + ((p * 80) / 100), "解压模型文件")
                    }
                }
                archiveFile.name.endsWith(".zip", ignoreCase = true) -> {
                    extractZip(archiveFile, dir) { p ->
                        onProgress?.invoke(5 + ((p * 80) / 100), "解压模型文件")
                    }
                }
                archiveFile.name.endsWith(".onnx") -> {
                    // 用户直接导入的单个 onnx（Piper 社区音色）
                    archiveFile.copyTo(File(dir, "model.onnx"), overwrite = true)
                    // 对于 Piper，还需要额外的 tokens + espeak-ng-data
                    // 这里不强制失败，交给 synthesize 时检测并提示
                }
                else -> throw IllegalArgumentException("不支持的压缩包格式: ${archiveFile.name}")
            }
        } catch (t: Throwable) {
            runCatching { dir.deleteRecursively() }
            throw t
        }

        onProgress?.invoke(95, "写入注册表")
        // sherpa 官方包内模型文件名各异（keqing.onnx / vits-zh-hf-fanchen-C.onnx / model.int8.onnx…），
        // 统一规范为 model.onnx，否则 native 层找不到模型会 "Failed to create OfflineTts"
        normalizeModelFile(dir)
        persistInstalled(manifest)

        onProgress?.invoke(100, "安装完成")
        FgoLogger.info(tag, "模型安装成功: ${manifest.modelId}")
        InstalledSherpaModel(manifest, dir.absolutePath)
    }

    fun uninstall(modelId: String) {
        installDirFor(modelId).takeIf { it.isDirectory }?.deleteRecursively()
        persistInstalled(null, removeId = modelId)
        FgoLogger.info(tag, "卸载模型: $modelId")
    }

    /**
     * 国内访问 GitHub 困难时使用的下载加速线路（反向代理）。
     * 格式：在原始 GitHub URL 前拼接该前缀即可。
     */
    data class GhProxyLine(
        val label: String,
        val prefix: String
    )

    companion object {
        /** 候选加速线路，按默认优先级排序；用户可在设置页手动切换。 */
        val GH_PROXY_LINES: List<GhProxyLine> = listOf(
            GhProxyLine("ghfast.top", "https://ghfast.top/"),
            GhProxyLine("ghproxy.cn", "https://ghproxy.cn/"),
            GhProxyLine("gh-proxy.com", "https://gh-proxy.com/"),
            GhProxyLine("moeyy", "https://github.moeyy.xyz/"),
            GhProxyLine("ghproxy.net", "https://ghproxy.net/")
        )
    }

    /** 某线路的加速下载 URL（非 GitHub 的 URL 不使用代理）。 */
    private fun proxyDownloadUrl(prefix: String, originalUrl: String): String {
        return if (originalUrl.startsWith("https://github.com/") ||
            originalUrl.startsWith("https://raw.githubusercontent.com/") ||
            originalUrl.startsWith("https://objects.githubusercontent.com/")
        ) {
            prefix + originalUrl
        } else {
            originalUrl
        }
    }

    /** 测速探针单次结果。 */
    data class ProxySpeedResult(
        val line: GhProxyLine,
        val success: Boolean,
        val latencyMs: Long,
        val speedMBps: Float,
        val error: String = ""
    )

    /**
     * 对某条加速线路测速：Range 下载 512KB 探针，统计首字节延迟与平均速度。
     * [probeUrl] 为原始 GitHub 下载 URL。
     */
    suspend fun testProxySpeed(
        line: GhProxyLine,
        probeUrl: String
    ): ProxySpeedResult = withContext(Dispatchers.IO) {
        val url = if (probeUrl.startsWith("https://")) line.prefix + probeUrl else probeUrl
        val start = System.currentTimeMillis()
        try {
            val resp = httpClient.get(url) {
                header(HttpHeaders.Range, "bytes=0-524287")
                header(HttpHeaders.UserAgent, "FgoGotran")
            }
            if (!resp.status.isSuccess()) {
                return@withContext ProxySpeedResult(
                    line, false, System.currentTimeMillis() - start, 0f,
                    "HTTP ${resp.status.value}"
                )
            }
            val channel = resp.bodyAsChannel()
            var bytes = 0L
            val buf = ByteArray(64 * 1024)
            val target = 512L * 1024
            while (bytes < target) {
                val r = channel.readAvailable(buf, 0, buf.size)
                if (r == -1) break
                bytes += r
            }
            val elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(1)
            val speed = bytes / 1024f / 1024f / (elapsedMs / 1000f)
            ProxySpeedResult(line, true, elapsedMs, speed)
        } catch (e: Throwable) {
            ProxySpeedResult(
                line, false, System.currentTimeMillis() - start, 0f,
                e.message?.take(80) ?: e.javaClass.simpleName
            )
        }
    }

    /**
     * 从 [manifest.downloadUrl] 流式下载模型压缩包并安装（App 内下载）。
     * 下载成功后自动把该模型设为用户选择。
     *
     * 在应用级协程作用域执行：不随 UI / 页面销毁而取消，切后台也能继续下载。
     * 国内访问 GitHub 困难时，下载 URL 会自动经过 ghfast.top 加速代理。
     * 支持断点续传：下载中断后保留临时文件，重试时从断点继续。
     *
     * @param onProgress 进度回调（percent 0..100，message 为阶段描述，可在 UI 展示）
     * @param onSuccess  安装成功回调（模型已被设为当前选择）
     * @param onError    失败回调（message 为可直接展示的错误描述）
     */
    fun downloadAndInstallAsync(
        manifest: SherpaOnnxModelManifest,
        onProgress: (percent: Int, message: String) -> Unit,
        onSuccess: (InstalledSherpaModel) -> Unit,
        onError: (String) -> Unit
    ): Job = downloadScope.launch {
        try {
            val installed = downloadAndInstallInternal(manifest, onProgress)
            onSuccess(installed)
        } catch (e: Throwable) {
            val msg = describeDownloadError(e)
            FgoLogger.warn(tag, "模型下载失败 ${manifest.modelId}: $msg", e)
            diagnosticEventStore.record("warn", "voice", "tts_model_download_failed", "TTS 模型下载失败", msg.take(160))
            onError(msg)
        }
    }

    private suspend fun downloadAndInstallInternal(
        manifest: SherpaOnnxModelManifest,
        onProgress: (percent: Int, message: String) -> Unit
    ): InstalledSherpaModel = withContext(Dispatchers.IO) {
        require(manifest.downloadUrl.startsWith("https://")) {
            "模型「${manifest.displayName}」缺少可用的下载地址"
        }

        // 存储空间预检：至少需要 压缩包 + 解压后增量 + 50MB 余量
        val minFreeBytes =
            manifest.archiveSizeBytes + (manifest.unpackedSizeBytes / 2) + (50L * 1024 * 1024)
        val freeBytes = StatFs(context.filesDir.absolutePath).availableBytes
        if (freeBytes < minFreeBytes) {
            throw IllegalStateException(
                "存储空间不足：需要约 ${formatMb(minFreeBytes)}，当前可用 ${formatMb(freeBytes)}。请清理后重试。"
            )
        }

        val downloadDir = File(rootDir, "downloads").apply { mkdirs() }
        val tempFile = File(downloadDir, "${manifest.modelId}.tar.bz2")

        // 候选线路：用户手动选择的优先，其余按默认顺序兜底
        val userPrefix = settingsRepository.getSherpaDownloadProxy().trim()
            .takeIf { it.isNotBlank() }
        val candidates = buildList {
            GH_PROXY_LINES.firstOrNull { it.prefix == userPrefix }?.let { add(it) }
            addAll(GH_PROXY_LINES.filter { it.prefix != userPrefix })
        }

        var lastError: Throwable? = null
        for (line in candidates) {
            try {
                onProgress(1, "连接 ${line.label}")
                val installed = downloadViaLine(manifest, line, tempFile, onProgress)
                // 下载成功 → 记住该线路，下次优先
                settingsRepository.setSherpaDownloadProxy(line.prefix)
                return@withContext installed
            } catch (e: Throwable) {
                lastError = e
                FgoLogger.warn(tag, "线路 ${line.label} 下载失败：${e.message}")
                onProgress(1, "${line.label} 失败，尝试下一线路…")
            }
        }
        throw lastError ?: IOException("所有加速线路均不可用")
    }

    /**
     * 用指定线路下载并安装模型。支持断点续传；416（Range 起点无效）时清空临时文件从头下载。
     */
    private suspend fun downloadViaLine(
        manifest: SherpaOnnxModelManifest,
        line: GhProxyLine,
        tempFile: File,
        onProgress: (percent: Int, message: String) -> Unit
    ): InstalledSherpaModel = withContext(Dispatchers.IO) {
        val actualUrl = proxyDownloadUrl(line.prefix, manifest.downloadUrl)
        val expectedBytes = manifest.archiveSizeBytes.coerceAtLeast(1L)
        var resumeFrom = 0L
        if (tempFile.exists() && tempFile.length() in 1 until expectedBytes) {
            resumeFrom = tempFile.length()
            FgoLogger.info(tag, "断点续传 ${manifest.modelId}：已有 ${formatMb(resumeFrom)}")
        } else if (tempFile.exists()) {
            if (!tempFile.delete()) throw IllegalStateException("无法清理旧的下载缓存: ${tempFile.name}")
        }

        var attempt = 0
        while (true) {
            attempt++
            FgoLogger.info(tag, "开始下载模型 ${manifest.modelId} <- $actualUrl" +
                (if (resumeFrom > 0) " (从 ${formatMb(resumeFrom)} 续传)" else "") +
                " 尝试#$attempt")
            val response = httpClient.get(actualUrl) {
                if (resumeFrom > 0) header(HttpHeaders.Range, "bytes=$resumeFrom-")
                header(HttpHeaders.UserAgent, "FgoGotran")
            }
            when {
                response.status.value == 416 -> {
                    // Range 起点 >= 文件实际长度（如上次实际已下完）→ 清空重下
                    FgoLogger.warn(tag, "线路 ${line.label} 返回 416，清空临时文件从头下载")
                    if (!tempFile.delete()) throw IllegalStateException("无法清理旧的下载缓存: ${tempFile.name}")
                    resumeFrom = 0L
                    continue
                }
                !response.status.isSuccess() -> {
                    throw IllegalStateException("下载失败 HTTP ${response.status.value}")
                }
            }

            // 用响应头确定真实总大小（比清单估算值可靠）
            val totalSize = response.headers[HttpHeaders.ContentRange]
                ?.substringAfter('/')?.trim()?.toLongOrNull()
                ?: response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            // 服务器忽略 Range 返回 200 全量 → 从头覆盖
            val resumed = response.status.value == 206 && resumeFrom > 0
            if (!resumed) {
                if (resumeFrom > 0 || tempFile.exists()) {
                    if (!tempFile.delete()) throw IllegalStateException("无法清理旧的下载缓存: ${tempFile.name}")
                }
                resumeFrom = 0L
            }

            val channel = response.bodyAsChannel()
            val startedAt = System.currentTimeMillis()
            java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                raf.seek(resumeFrom)
                val buffer = ByteArray(64 * 1024)
                var written = resumeFrom
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read > 0) {
                        raf.write(buffer, 0, read)
                        written += read
                    }
                    val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)
                    val speed = (written - resumeFrom) / elapsedSec.toFloat()
                    val percent = ((written * 100) / expectedBytes).toInt().coerceIn(0, 94)
                    onProgress(percent, "下载中 ${formatMb(written)} · ${formatMb(speed.toLong())}/s")
                }
            }

            val downloaded = tempFile.length()
            if (totalSize != null && downloaded < totalSize) {
                // 提前断流：保留断点，抛错让上层切换线路
                throw IOException(
                    "下载不完整：期望 ${formatMb(totalSize)}，实际 ${formatMb(downloaded)}（断点已保留，将自动续传）"
                )
            }
            FgoLogger.info(tag, "模型下载完成: $downloaded bytes (预期 ${totalSize ?: "未知"})")
            break
        }

        try {
            val installed = installFromArchive(
                manifest = manifest,
                archiveFile = tempFile,
                onProgress = { p, msg ->
                    onProgress(94 + (p * 6 / 100), msg)
                }
            )
            // 下载完成后自动切换为当前模型
            settingsRepository.setSherpaSelectedModel(manifest.modelId)
            installed
        } finally {
            // 安装成功/失败都清理临时文件
            runCatching { tempFile.delete() }
        }
    }

    /** 把下载异常转成用户可读的错误描述。 */
    private fun describeDownloadError(e: Throwable): String {
        return when (e) {
            is SocketTimeoutException -> "下载超时：网络中断或速度过慢，请重试（会从断点继续）"
            is java.util.concurrent.TimeoutException -> "下载超时：网络中断或速度过慢，请重试"
            is IOException -> "网络错误：${e.message.orEmpty().take(120)}，请重试（会从断点继续）"
            is SecurityException -> "校验失败：${e.message.orEmpty().take(120)}"
            is IllegalStateException -> e.message.orEmpty().take(160)
            else -> "${e.javaClass.simpleName}：${e.message.orEmpty().take(120)}"
        }
    }

    /** 已安装模型列表（从注册表目录扫描） */
    fun listInstalled(): List<InstalledSherpaModel> {
        val catalog = builtinCatalog().associateBy { it.modelId }
        return modelsDir.listFiles()?.filter { it.isDirectory }.orEmpty().mapNotNull { dir ->
            // 懒迁移：旧版本安装的模型目录里是原始文件名（如 vits-zh-hf-fanchen-C.onnx），
            // 这里统一规范为 model.onnx，否则加载时会 "Failed to create OfflineTts"。
            // 迁移失败（目录内无 .onnx）视为损坏安装，跳过并让 UI 引导重新下载。
            val migrated = runCatching { normalizeModelFile(dir) }
                .onFailure { FgoLogger.warn(tag, "跳过损坏的模型目录 ${dir.name}：${it.message}") }
                .isSuccess
            if (!migrated) return@mapNotNull null

            val id = dir.name
            val manifest = catalog[id]
                // 用户自定义导入的模型，做一个最小 manifest fallback
                ?: SherpaOnnxModelManifest(
                    modelId = id,
                    displayName = "自定义模型 · $id",
                    modelType = SherpaModelType.VITS_PLAIN,
                    language = "unknown",
                    speakerCount = 1,
                    sampleRate = 22050,
                    downloadUrl = "",
                    archiveSizeBytes = 0L,
                    unpackedSizeBytes = 0L,
                    sha256 = ""
                )
            InstalledSherpaModel(manifest, dir.absolutePath)
        }
    }

    /**
     * 返回默认选中的模型（按方案 A 的优先级排序）：
     *   1. vits-zh-fanchen-C（187 种音色，角色映射最全）
     *   2. 其它中文（zh*）
     *   3. 任意已安装
     */
    fun preferredInstalled(): InstalledSherpaModel? {
        val installed = listInstalled()
        if (installed.isEmpty()) return null
        return installed.firstOrNull { it.manifest.modelId == "vits-zh-fanchen-C" }
            ?: installed.firstOrNull { it.manifest.language.startsWith("zh") }
            ?: installed.first()
    }

    /**
     * 返回用户当前选择的模型（SettingsRepository.sherpa_selected_model）。
     * 未选择或所选模型已被卸载时，回退到 [preferredInstalled]。
     */
    suspend fun selectedInstalled(): InstalledSherpaModel? {
        val selectedId = settingsRepository.getSherpaSelectedModel().trim()
        if (selectedId.isNotBlank()) {
            listInstalled().firstOrNull { it.manifest.modelId == selectedId }?.let { return it }
        }
        return preferredInstalled()
    }

    // ==================================================================
    // 内部：解压与持久化
    // ==================================================================

    private fun extractZip(archive: File, destDir: File, onProgress: (Int) -> Unit) {
        val totalSize = archive.length().coerceAtLeast(1L)
        var readSoFar = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                val target = File(destDir, sanitizeEntryName(entry.name))
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(target)).use { out ->
                        var n: Int
                        while (zis.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            readSoFar += n
                        }
                    }
                }
                onProgress(((readSoFar * 100) / totalSize).toInt().coerceAtMost(99))
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun extractTarBz2(archive: File, destDir: File, onProgress: (Int) -> Unit) {
        val totalSize = archive.length().coerceAtLeast(1L)
        var readSoFar = 0L
        val fin = FileInputStream(archive)
        val bin = BufferedInputStream(fin)
        val bzIn = BZip2CompressorInputStream(bin)
        val tarIn = TarArchiveInputStream(bzIn)
        val buf = ByteArray(64 * 1024)
        tarIn.use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val target = File(destDir, sanitizeEntryName(entry.name))
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(target)).use { out ->
                        var n: Int
                        while (tar.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            readSoFar += n
                        }
                    }
                }
                onProgress(((readSoFar * 100) / totalSize).toInt().coerceAtMost(99))
                entry = tar.nextTarEntry
            }
        }
    }

    private fun sanitizeEntryName(name: String): String {
        val stripped = if (name.startsWith("./")) name.substring(2) else name
        // 去掉 tar 最外层同名目录（sherpa 打包通常带一层）
        val parts = stripped.split('/', limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && !parts[0].contains('.')) parts[1] else stripped
    }

    /**
     * 将模型目录内的主 .onnx 文件规范为固定的 model.onnx。
     *
     * sherpa 官方包内模型文件名各异（vits-zh-hf-fanchen-C.onnx / keqing.onnx /
     * model-steps-*.onnx…），而加载侧统一按 model.onnx 引用；不重命名会导致
     * native 层找不到模型，抛出 "Failed to create OfflineTts"。
     *
     * 幂等：已存在 model.onnx 时直接返回，因此可对旧版本已安装的模型反复调用做懒迁移。
     */
    private fun normalizeModelFile(dir: File) {
        val canonical = File(dir, "model.onnx")
        // 已存在且非空（>0B）才算规范；0 字节的损坏文件继续走重命名修复
        if (canonical.isFile && canonical.length() > 0) return

        // 排除 Matcha 的 vocoder（hifigan*.onnx / vocoder*.onnx），只挑主模型
        val candidates = dir.listFiles { f ->
            f.isFile && f.extension.equals("onnx", ignoreCase = true) &&
                !f.name.startsWith("hifigan", ignoreCase = true) &&
                !f.name.startsWith("vocoder", ignoreCase = true)
        }.orEmpty()

        when {
            candidates.isEmpty() ->
                throw IllegalStateException("模型目录中没有 .onnx 模型文件：${dir.absolutePath}")
            candidates.size == 1 -> {
                if (!candidates[0].renameTo(canonical)) {
                    throw IllegalStateException("重命名模型文件失败：${candidates[0].name} → model.onnx")
                }
                FgoLogger.info(tag, "已规范模型文件名: ${candidates[0].name} -> model.onnx (${dir.name})")
            }
            else -> {
                // 极少数包内 int8/fp32 并存：取体积最大的（完整精度版）
                val picked = candidates.maxByOrNull { it.length() } ?: candidates[0]
                FgoLogger.warn(
                    tag,
                    "模型目录存在多个 .onnx（${candidates.joinToString { it.name }}），选用 ${picked.name}"
                )
                if (!picked.renameTo(canonical)) {
                    throw IllegalStateException("重命名模型文件失败：${picked.name} → model.onnx")
                }
            }
        }

        // Matcha 的 vocoder 也统一命名为 hifigan.onnx（加载侧按该名字查找）
        val vocoder = dir.listFiles { f ->
            f.isFile && f.extension.equals("onnx", ignoreCase = true) &&
                (f.name.startsWith("hifigan", ignoreCase = true) ||
                    f.name.startsWith("vocoder", ignoreCase = true))
        }.orEmpty().maxByOrNull { it.length() }
        if (vocoder != null) {
            val target = File(dir, "hifigan.onnx")
            if (!target.isFile && !vocoder.renameTo(target)) {
                FgoLogger.warn(tag, "重命名 vocoder 失败: ${vocoder.name} -> hifigan.onnx")
            }
        }
    }

    private fun formatMb(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) {
            String.format(Locale.US, "%.1f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f MB", mb)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            var n: Int
            while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 简单的已安装列表持久化（用 JSON 字符串，避免额外依赖） */
    private fun persistInstalled(add: SherpaOnnxModelManifest?, removeId: String? = null) {
        val current = listInstalled().map { it.manifest }.toMutableList()
        if (removeId != null) current.removeAll { it.modelId == removeId }
        if (add != null) {
            current.removeAll { it.modelId == add.modelId }
            current.add(add)
        }
        val json = current.joinToString(prefix = "[", postfix = "]", separator = ",") { m ->
            """{"modelId":"${m.modelId}","displayName":"${m.displayName}","modelType":"${m.modelType}","language":"${m.language}","speakerCount":${m.speakerCount},"sampleRate":${m.sampleRate},"downloadUrl":"${m.downloadUrl}","archiveSizeBytes":${m.archiveSizeBytes},"unpackedSizeBytes":${m.unpackedSizeBytes},"sha256":"${m.sha256}","notes":"${m.notes}","requiresMetadataPatch":${m.requiresMetadataPatch}}"""
        }
        registryFile.writeText(json)
    }
}
