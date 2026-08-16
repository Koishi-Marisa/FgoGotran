package com.fgogotran.voice

import android.content.Context
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sherpa-ONNX 本地 TTS Provider。
 *
 * 直接调用 k2-fsa/sherpa-onnx 的 Android Kotlin API（[OfflineTts]），
 * 通过 ONNX Runtime 推理 VITS / Piper / Kokoro / Matcha 等模型。
 */
@Singleton
class SherpaOnnxTtsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: SherpaOnnxModelRegistry,
    private val settingsRepository: SettingsRepository,
    private val diagnosticEventStore: DiagnosticEventStore,
    private val speakerMappings: SherpaSpeakerMappings
) : TtsProvider {

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Sherpa-ONNX 本地离线合成"
    override val requiresNetwork: Boolean = false
    override val requiresCredentials: Boolean = false

    private val tag = "SherpaTts"
    private val mutex = Mutex()

    /** 当前加载的 Sherpa 引擎实例 */
    private var tts: OfflineTts? = null
    private var activeModelId: String? = null

    // =====================================================================
    // 生命周期
    // =====================================================================
    override suspend fun warmUp() {
        if (tts != null) return
        // 预热可能来自后台协程（设置页切换引擎 / 播放前预加载），
        // 与 synthesizeToFile 共用同一把 mutex，保证不会与推理并发互相释放。
        mutex.withLock {
            if (tts != null) return@withLock
            warmUpLocked()
        }
    }

    /** 需在持有 [mutex] 时调用（synthesizeToFile 通过 [warmUpIfNeeded] 间接调用）。 */
    private suspend fun warmUpLocked() {
        // 触发 OfflineTts 类加载，其 companion init 会 System.loadLibrary("sherpa-onnx-jni")。
        // 如果 APK 未包含对应 so，这里会抛出 UnsatisfiedLinkError，转成友好提示。
        withContext(Dispatchers.IO) {
            try {
                Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException(
                    "Sherpa-ONNX Kotlin API 未找到。请确认 APK 包含 sherpa-onnx AAR，" +
                        "或切换为 Azure 云端合成。",
                    e
                )
            } catch (e: UnsatisfiedLinkError) {
                // 类找到了但 so 加载失败：通常是 libonnxruntime.so / libsherpa-onnx-jni.so
                // 版本或来源不匹配。把原始异常消息暴露出来，方便定位。
                val original = e.message ?: e.toString()
                FgoLogger.error(tag, "Sherpa-ONNX native load failed: $original", e)
                throw IllegalStateException(
                    "本地 TTS 库加载失败。原始错误：$original\n" +
                        "常见原因：APK 中的 libonnxruntime.so 不是 Sherpa-ONNX 编译的版本。" +
                        "请尝试重新安装 APK 或切换为 Azure 云端合成。",
                    e
                )
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "初始化 Sherpa-ONNX 失败：${e.message}。请切换为 Azure 云端合成。",
                    e
                )
            }
        }

        // 1) 若 APK 里打了 assets/sherpa_builtin_models/<id>/，首次启动自动静默安装
        registry.ensureAssetsModelsInstalled()

        // 2) 尝试自动加载当前选择的模型，后续 synthesize 可直接使用
        val preferred = registry.selectedInstalled() ?: run {
            FgoLogger.warn(tag, "当前尚未安装任何本地 TTS 模型")
            return
        }
        ensureModelLoaded(preferred)
    }

    override fun close() {
        tts?.free()
        tts = null
        activeModelId = null
    }

    // =====================================================================
    // 合成核心
    // =====================================================================
    override suspend fun synthesizeToFile(
        request: VoiceSynthesisRequest,
        outputFile: File
    ): Boolean = withContext(Dispatchers.Default) {
        // 锁内只做推理（OfflineTts 非线程安全，需串行）；纯文件写入挪到锁外，
        // 让多角色并发合成时写 WAV 不再阻塞后续角色的推理。
        val synthesized = mutex.withLock {
            warmUpIfNeeded()

            // 当前用户选择的模型（设置页切换后这里会自动换模型并重载引擎）
            val installed = registry.selectedInstalled()
                ?: throw IllegalStateException("没有已安装的本地 TTS 模型，请到「语音设置」中下载")
            ensureModelLoaded(installed)

            // 说话人 id：
            //   1. 优先用 profile.style/profile.description 中显式写的 "sid:xxx"；
            //   2. 否则查「FGO 常见角色 × 模型预设表」（方案 A）；
            //   3. 最终按"角色名+性别"稳定 hash 保证同角色音色不跳变。
            val sid = speakerMappings.resolveSpeakerId(
                speakerName = request.speakerName,
                profile = request.profile,
                installed = installed
            )
            // 速度：profile.rate 形如 "15%" -> 换算为 speed factor
            val speed = resolveSpeed(request)

            val text = request.spokenText.trim()
                .ifBlank { throw IllegalArgumentException("合成文本为空") }

            val audio = try {
                tts?.generate(text, sid, speed)
                    ?: throw IllegalStateException("Sherpa OfflineTts 未初始化")
            } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException("本地 TTS 推理失败（native 库异常）：${e.message}", e)
            } catch (e: OutOfMemoryError) {
                throw IllegalStateException("本地 TTS 内存不足，请缩短文本或切换为 Azure 云端合成", e)
            }
            SynthesizedAudio(
                samples = audio.samples,
                sampleRate = audio.sampleRate,
                sid = sid,
                speed = speed
            )
        }

        outputFile.parentFile?.mkdirs()
        writeWav(outputFile, synthesized.samples, synthesized.sampleRate)
        FgoLogger.info(
            tag,
            "本地合成完成: sid=${synthesized.sid} rate=${synthesized.speed} " +
                "samples=${synthesized.samples.size} sr=${synthesized.sampleRate} → ${outputFile.name}"
        )
        true
    }

    override suspend fun listAvailableVoices(): List<VoiceProfile> {
        val installed = registry.listInstalled()
        // 把用户当前选择的模型排在最前，测试语音等默认取第一个即可命中所选模型
        val selectedId = settingsRepository.sherpaSelectedModel.first().trim()
        val ordered = if (selectedId.isBlank()) {
            installed
        } else {
            installed.sortedBy { if (it.manifest.modelId == selectedId) 0 else 1 }
        }
        val defaultModel = registry.preferredInstalled()
        return ordered.flatMap { inst ->
            inst.speakerNames().mapIndexed { idx, name ->
                VoiceProfile(
                    profileId = "${inst.manifest.modelId}#spk$idx",
                    provider = providerId,
                    locale = when (inst.manifest.language) {
                        "zh", "zh_en" -> "zh-CN"
                        "ja" -> "ja-JP"
                        else -> "en-US"
                    },
                    voiceName = "${inst.manifest.modelId}:$idx",
                    style = "sid:$idx",
                    pitch = "0%",
                    rate = "0%",
                    volume = "100%",
                    description = buildString {
                        append(inst.manifest.displayName)
                        if (defaultModel?.manifest?.modelId != inst.manifest.modelId) {
                            append(" · $name")
                        }
                    }
                )
            }
        }
    }

    // =====================================================================
    // 内部
    // =====================================================================
    /** 调用方（synthesizeToFile）已持有 [mutex]，直接走无锁内部实现，避免重入死锁。 */
    private suspend fun warmUpIfNeeded() {
        if (tts == null) warmUpLocked()
    }

    private suspend fun ensureModelLoaded(installed: InstalledSherpaModel) {
        if (activeModelId == installed.manifest.modelId && tts != null) return

        // 释放旧模型
        tts?.free()
        tts = null
        activeModelId = null

        val config = buildTtsConfig(installed)
        FgoLogger.info(tag, "加载 Sherpa 模型 ${installed.manifest.modelId}")
        tts = OfflineTts(config = config)
        activeModelId = installed.manifest.modelId
    }

    private fun buildTtsConfig(installed: InstalledSherpaModel): OfflineTtsConfig {
        val dir = installed.installDir
        // 用足设备核心数，显著加快 VITS/Kokoro 推理（2~4 线程是速度/功耗的平衡点）
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val baseModelConfig = OfflineTtsModelConfig(
            numThreads = numThreads,
            debug = false,
            provider = "cpu"
        )

        return when (installed.manifest.modelType) {
            SherpaModelType.VITS_PLAIN -> {
                // 中文 VITS 模型（如 vits-zh-ll）需要 lexicon.txt + jieba dict 才能正确分词；
                // 缺少 lexicon 会导致 native 层 Lexicon 查找失败而 SIGABRT（sherpa-onnx#823）。
                val lexiconFile = File(dir, "lexicon.txt")
                val vits = OfflineTtsVitsModelConfig(
                    model = "$dir/model.onnx",
                    tokens = "$dir/tokens.txt",
                    lexicon = if (lexiconFile.isFile) lexiconFile.absolutePath else ""
                )
                val dictDir = File(dir, "dict")
                if (dictDir.isDirectory) {
                    vits.dictDir = dictDir.absolutePath
                }
                // 中文模型常附带 date/number/phone/heteronym .fst 用于文本正规化
                val ruleFsts = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")
                    .mapNotNull { name ->
                        val f = File(dir, name)
                        if (f.isFile) f.absolutePath else null
                    }
                    .joinToString(",")
                OfflineTtsConfig(
                    model = baseModelConfig.copy(vits = vits),
                    ruleFsts = ruleFsts
                )
            }

            SherpaModelType.PIPER_VITS -> {
                val vits = OfflineTtsVitsModelConfig(
                    model = "$dir/model.onnx",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data"
                )
                OfflineTtsConfig(model = baseModelConfig.copy(vits = vits))
            }

            SherpaModelType.KOKORO_82M -> {
                val lexicon = listOfNotNull(
                    "$dir/lexicon-zh.txt".takeIf { File(it).exists() },
                    "$dir/lexicon-us-en.txt".takeIf { File(it).exists() }
                ).joinToString(",")
                val kokoro = OfflineTtsKokoroModelConfig(
                    model = "$dir/model.onnx",
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    lexicon = lexicon,
                    lang = installed.manifest.language.takeIf { it.isNotBlank() } ?: "zh"
                )
                OfflineTtsConfig(model = baseModelConfig.copy(kokoro = kokoro))
            }

            SherpaModelType.MATCHA_TTS -> {
                val acoustic = File(dir, "model.onnx")
                    .takeIf { it.exists() }
                    ?: throw IllegalStateException("Matcha TTS 缺少 acoustic model")
                val vocoder = File(dir, "hifigan.onnx").takeIf { it.exists() }
                    ?: File(dir, "vocoder.onnx").takeIf { it.exists() }
                    ?: throw IllegalStateException("Matcha TTS 缺少 vocoder 模型")
                val matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = acoustic.absolutePath,
                    vocoder = vocoder.absolutePath,
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data"
                )
                OfflineTtsConfig(model = baseModelConfig.copy(matcha = matcha))
            }
        }
    }

    private suspend fun resolveSpeed(request: VoiceSynthesisRequest): Float {
        // 1) AI 语气增强给出的最终语速（ChineseVoiceEmotionStyle 已把用户全局速度
        //    baseSpeedMultiplier 与情感微调合并进 rateOverride，格式如 "1.03"）
        parseRateMultiplier(request.rateOverride)?.let { return it }

        // 2) 语音档案语速：临时语音档案（Azure 或本地 AI 分配）会带 rate 如 "0.96"
        request.profile.rate.takeIf { !it.isNoRateSetting() }
            ?.let(::parseRateMultiplier)
            ?.let { return it }

        // 3) 全局速度兜底：115% -> 1.15x
        val pct = request.aiVoiceSpeedPercent.takeIf { it in 50..200 }
            ?: settingsRepository.aiVoiceSpeedPercent.first().coerceIn(50, 200)
        return (pct / 100f).coerceIn(0.5f, 2.0f)
    }

    /** "15%" -> 1.15、"0.96" -> 0.96；无法解析返回 null */
    private fun parseRateMultiplier(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val value = if (trimmed.endsWith("%")) {
            val percent = trimmed.dropLast(1).toDoubleOrNull() ?: return null
            1.0 + percent / 100.0
        } else {
            trimmed.toDoubleOrNull() ?: return null
        }
        return value.toFloat().coerceIn(0.5f, 2.0f)
    }

    private fun String.isNoRateSetting(): Boolean {
        return isBlank() || this == "0" || this == "0%" || this == "+0%" || this == "-0%"
    }

    /** synthesizeToFile 锁内推理结果，供锁外写文件与日志使用 */
    private data class SynthesizedAudio(
        val samples: FloatArray,
        val sampleRate: Int,
        val sid: Int,
        val speed: Float
    )

    companion object {
        const val PROVIDER_ID = "sherpa_onnx"
    }

    /** 将 float [-1,1] 音频以 16-bit PCM 写入 WAV */
    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val dataLen = samples.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
            // RIFF header
            buf.put("RIFF".toByteArray())
            buf.putInt(36 + dataLen)
            buf.put("WAVE".toByteArray())
            // fmt chunk
            buf.put("fmt ".toByteArray())
            buf.putInt(16)
            buf.putShort(1)          // PCM
            buf.putShort(1)          // channels
            buf.putInt(sampleRate)
            buf.putInt(sampleRate * 2)   // byte rate
            buf.putShort(2)          // block align
            buf.putShort(16)         // bits per sample
            // data chunk
            buf.put("data".toByteArray())
            buf.putInt(dataLen)
            samples.forEach { s ->
                val clamped = s.coerceIn(-1f, 1f)
                buf.putShort((clamped * 32767f).toInt().toShort())
            }
            raf.channel.write(buf.rewind() as ByteBuffer)
        }
    }
}
