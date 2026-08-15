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
        // 中文：5 说话人（推荐，小体积，稳定）
        SherpaOnnxModelManifest(
            modelId = "vits-zh-ll",
            displayName = "中文多音色 (zh-ll · 5人)",
            modelType = SherpaModelType.VITS_PLAIN,
            language = "zh",
            speakerCount = 5,
            sampleRate = 16000,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-vits-zh-ll.tar.bz2",
            archiveSizeBytes = 115L * 1024 * 1024,
            unpackedSizeBytes = 230L * 1024 * 1024,
            sha256 = "",
            notes = "官方推荐中文模型，5 种音色，模型体积 ~115MB"
        ),
        // 中文：187 说话人（Fanchen-C）
        SherpaOnnxModelManifest(
            modelId = "vits-zh-fanchen-C",
            displayName = "中文超多音色 (fanchen-C · 187人)",
            modelType = SherpaModelType.VITS_PLAIN,
            language = "zh",
            speakerCount = 187,
            sampleRate = 16000,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-fanchen-C.tar.bz2",
            archiveSizeBytes = 116L * 1024 * 1024,
            unpackedSizeBytes = 240L * 1024 * 1024,
            sha256 = "",
            notes = "社区贡献，187 种男女混合音色"
        ),
        // 中英混合：MeloTTS
        SherpaOnnxModelManifest(
            modelId = "melo-tts-zh_en",
            displayName = "中英混合 (MeloTTS)",
            modelType = SherpaModelType.VITS_PLAIN,
            language = "zh_en",
            speakerCount = 1,
            sampleRate = 44100,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2",
            archiveSizeBytes = 163L * 1024 * 1024,
            unpackedSizeBytes = 350L * 1024 * 1024,
            sha256 = "",
            notes = "中英混读效果好，质量高，单音色"
        ),
        // Kokoro-82M：多语言，质量顶级
        SherpaOnnxModelManifest(
            modelId = "kokoro-82m-multi",
            displayName = "多语言高音质 (Kokoro-82M)",
            modelType = SherpaModelType.KOKORO_82M,
            language = "multi",
            speakerCount = 90,
            sampleRate = 24000,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-v1.0-onnx.tar.bz2",
            archiveSizeBytes = 500L * 1024 * 1024,
            unpackedSizeBytes = 900L * 1024 * 1024,
            sha256 = "",
            notes = "支持中英日韩等 50+ 语言，质量接近商业服务；模型较大（~500MB）"
        ),
        // 日文 Piper
        SherpaOnnxModelManifest(
            modelId = "piper-ja_JP-amakusa-medium",
            displayName = "日文女声 (Piper amakusa-medium)",
            modelType = SherpaModelType.PIPER_VITS,
            language = "ja",
            speakerCount = 1,
            sampleRate = 22050,
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/ja/ja_JP/amakusa/medium/ja_JP-amakusa-medium.onnx",
            archiveSizeBytes = 70L * 1024 * 1024,
            unpackedSizeBytes = 150L * 1024 * 1024,
            sha256 = "",
            notes = "Piper 社区日语女声，需要先给 onnx 添加 metadata（见 Sherpa 官方文档）"
        )
    )

    // ==================================================================
    // 安装与下载
    // ==================================================================

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

    /** 返回默认选中的模型（按优先级：中文已安装 > 任意已安装） */
    fun preferredInstalled(): InstalledSherpaModel? {
        val installed = listInstalled()
        return installed.firstOrNull { it.manifest.language.startsWith("zh") }
            ?: installed.firstOrNull()
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
