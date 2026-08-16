package com.fgogotran.voice

import android.content.Context
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
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

    /** 判断某模型是否已安装 */
    fun isInstalled(modelId: String): Boolean = installDirFor(modelId).isDirectory

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

    /** 已安装模型列表（从注册表目录扫描） */
    fun listInstalled(): List<InstalledSherpaModel> {
        val catalog = builtinCatalog().associateBy { it.modelId }
        return modelsDir.listFiles()?.filter { it.isDirectory }.orEmpty().mapNotNull { dir ->
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
