package com.fgogotran.voice

import android.content.Context
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
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
 * 使用 k2-fsa/sherpa-onnx 的 Android JNI 包（`libsherpa-onnx-jni.so`），
 * 通过 ONNX Runtime 推理 VITS / Piper / Kokoro / Matcha 等模型。
 *
 * ### 启用步骤（一次性，用户侧）
 * 在 app/build.gradle.kts 中加入：
 * ```
 * // 请与项目现有的 onnxruntime-android 版本对齐（当前 1.27.0）
 * implementation("io.github.k2-fsa:sherpa-onnx-android:1.14.0")
 * ```
 * 若 mavenCentral() 拉不到，可改为从 GitHub Release 下载 AAR 手动放入 app/libs/ 并：
 * ```
 * implementation(files("libs/sherpa-onnx-android.aar"))
 * ```
 *
 * 如果当前未引入 sherpa aar，[warmUp] 会检测并抛出，UI 层提示用户先下载
 * 模型包或切换为 Azure 云端合成。
 */
@Singleton
class SherpaOnnxTtsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: SherpaOnnxModelRegistry,
    private val settingsRepository: SettingsRepository,
    private val diagnosticEventStore: DiagnosticEventStore,
    private val speakerMappings: SherpaSpeakerMappings
) : TtsProvider {

    override val providerId: String = "sherpa_onnx"
    override val displayName: String = "Sherpa-ONNX 本地离线合成"
    override val requiresNetwork: Boolean = false
    override val requiresCredentials: Boolean = false

    private val tag = "SherpaTts"
    private val mutex = Mutex()

    /** 当前加载的模型（sherpa 引擎句柄用 Long 持有，JNI 分配） */
    private var activeModelHandle: Long = 0L
    private var activeModelId: String? = null
    private var activeSampleRate: Int = 22050

    /** 反射持有 JNI 入口，避免硬依赖（未安装 aar 时给友好提示） */
    private object JniBridge {
        var available: Boolean = false
        var createFn: ((Array<String>) -> Long)? = null
        var generateFn: ((handle: Long, text: String, sid: Int, speed: Float) -> AudioSamples)? = null
        var destroyFn: ((handle: Long) -> Unit)? = null

        fun tryBind() {
            runCatching {
                System.loadLibrary("sherpa-onnx-jni")
                val cls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
                val ctor = cls.getConstructor(Array<String>::class.java)
                val generate = cls.getMethod(
                    "generate",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                )
                val destroy = cls.getMethod("delete")
                createFn = { args ->
                    val inst = ctor.newInstance(args)
                    // 把实例以"地址"形式存进 map；简化处理，用实例的 identityHashCode 是不够的
                    // 这里我们改用直接持有实例引用的方案，见下方 InstanceHolder
                    InstanceHolder.put(inst)
                }
                generateFn = { handle, text, sid, speed ->
                    val inst = InstanceHolder.get(handle)
                        ?: error("Sherpa handle 无效: $handle")
                    val result = generate.invoke(inst, text, sid, speed)
                    // Sherpa generate() 返回 OfflineTtsGeneratedAudio，属性 samples/sampleRate
                    val samplesArr = result.javaClass.getMethod("getSamples").invoke(result) as FloatArray
                    val sr = result.javaClass.getMethod("getSampleRate").invoke(result) as Int
                    AudioSamples(samplesArr, sr)
                }
                destroyFn = { handle ->
                    val inst = InstanceHolder.remove(handle)
                    inst?.let { destroy.invoke(it) }
                    Unit
                }
                available = true
                FgoLogger.info("SherpaJNI", "sherpa-onnx-jni 绑定成功")
            }.onFailure {
                FgoLogger.warn("SherpaJNI", "sherpa-onnx-jni 不可用：${it.message}")
                available = false
            }
        }
    }

    /** 为了避免把 JNI 类写死，用一个 holder 将实例映射为 Long "句柄" */
    private object InstanceHolder {
        private var nextHandle = 1L
        private val map = mutableMapOf<Long, Any>()
        fun put(inst: Any): Long = synchronized(this) {
            val h = nextHandle++
            map[h] = inst; h
        }
        fun get(h: Long): Any? = synchronized(this) { map[h] }
        fun remove(h: Long): Any? = synchronized(this) { map.remove(h) }
        fun clear() = synchronized(this) {
            map.values.toList().forEach { inst ->
                runCatching { inst.javaClass.getMethod("delete").invoke(inst) }
            }
            map.clear()
        }
    }

    private data class AudioSamples(val floats: FloatArray, val sampleRate: Int)

    // =====================================================================
    // 生命周期
    // =====================================================================
    override suspend fun warmUp() {
        if (!JniBridge.available) {
            withContext(Dispatchers.IO) { JniBridge.tryBind() }
        }
        if (!JniBridge.available) {
            throw IllegalStateException(
                "Sherpa-ONNX JNI 库未找到。请先在 build.gradle.kts 添加 sherpa-onnx-android.aar，" +
                    "或切换为 Azure 云端合成。"
            )
        }
        // 1) 若 APK 里打了 assets/sherpa_builtin_models/<id>/，首次启动自动静默安装
        registry.ensureAssetsModelsInstalled()
        // 2) 尝试自动加载一个已安装的模型，后续 synthesize 可直接使用
        val preferred = registry.preferredInstalled() ?: run {
            FgoLogger.warn(tag, "当前尚未安装任何本地 TTS 模型")
            return
        }
        ensureModelLoaded(preferred)
    }

    override fun close() {
        if (activeModelHandle != 0L) {
            runCatching { JniBridge.destroyFn?.invoke(activeModelHandle) }
            activeModelHandle = 0L
        }
        InstanceHolder.clear()
    }

    // =====================================================================
    // 合成核心
    // =====================================================================
    override suspend fun synthesizeToFile(
        request: VoiceSynthesisRequest,
        outputFile: File
    ): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            warmUpIfNeeded()
            val installed = registry.listInstalled().firstOrNull { it.manifest.modelId == activeModelId }
                ?: registry.preferredInstalled()
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

            val audio = JniBridge.generateFn?.invoke(activeModelHandle, text, sid, speed)
                ?: throw IllegalStateException("Sherpa generate 句柄未初始化")

            outputFile.parentFile?.mkdirs()
            writeWav(outputFile, audio.floats, audio.sampleRate)
            FgoLogger.info(
                tag,
                "本地合成完成: sid=$sid rate=$speed samples=${audio.floats.size} sr=${audio.sampleRate} → ${outputFile.name}"
            )
            true
        }
    }

    override suspend fun listAvailableVoices(): List<VoiceProfile> {
        val installed = registry.listInstalled()
        val defaultModel = registry.preferredInstalled()
        return installed.flatMap { inst ->
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
    private suspend fun warmUpIfNeeded() {
        if (!JniBridge.available) warmUp()
    }

    private suspend fun ensureModelLoaded(installed: InstalledSherpaModel) {
        if (activeModelId == installed.manifest.modelId && activeModelHandle != 0L) return
        // 释放旧模型
        if (activeModelHandle != 0L) {
            runCatching { JniBridge.destroyFn?.invoke(activeModelHandle) }
            activeModelHandle = 0L
        }
        val configMap = installed.manifest.configForInstallDir(installed.installDir)
        val args = configMap.flatMap { (k, v) -> listOf("--$k", v) }.toTypedArray()
        FgoLogger.info(tag, "加载 Sherpa 模型 ${installed.manifest.modelId} args=${args.joinToString(" ")}")
        val handle = JniBridge.createFn?.invoke(args)
            ?: throw IllegalStateException("Sherpa OfflineTts create 失败")
        activeModelHandle = handle
        activeModelId = installed.manifest.modelId
        activeSampleRate = installed.manifest.sampleRate
    }

    private fun resolveSpeed(request: VoiceSynthesisRequest): Float {
        val pct = request.aiVoiceSpeedPercent.takeIf { it in 50..200 }
            ?: settingsRepository.aiVoiceSpeedPercent.valueOr(115)
        // 115% -> 1.15x speed
        return (pct / 100f).coerceIn(0.5f, 2.0f)
    }

    private fun Int?.valueOr(def: Int) = this ?: def

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
