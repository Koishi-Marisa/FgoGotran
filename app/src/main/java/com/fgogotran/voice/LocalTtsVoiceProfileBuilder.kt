package com.fgogotran.voice

import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.translation.Translator
import com.fgogotran.util.FgoLogger
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 TTS 专属的临时语音档案构建器。
 *
 * 与 [TempVoiceProfileBuilder]（Azure 云端）不同：本地离线模型无法选择 Azure voice model，
 * 因此只让 AI 推荐「情感 / 语速 / 音调」，再由 [SherpaOnnxTtsProvider] 转成 speed 等参数。
 *
 * 返回的 [TempVoiceProfileRow].voiceName 形如 "sherpa:<modelId>"，
 * [TempVoiceProfileRepository] 识别该前缀后会构造 provider=sherpa_onnx 的 [VoiceProfile]。
 */
@Singleton
class LocalTtsVoiceProfileBuilder @Inject constructor(
    private val translator: Translator,
    private val registry: SherpaOnnxModelRegistry,
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private val tag = "LocalTtsVoiceBuilder"

    suspend fun build(server: String, nameBox: String, dialogue: String): TempVoiceProfileRow? {
        val defaultModel = registry.selectedInstalled() ?: run {
            FgoLogger.warn(tag, "本地 TTS 无已安装模型，无法分配语音档案")
            return null
        }
        val cleanName = sanitizeField(nameBox, MAX_NAME_CHARS).takeIf { it.isNotBlank() }
            ?: return null
        val cleanDialogue = sanitizeField(dialogue, MAX_DIALOGUE_CHARS)
        val normalizedServer = SettingsRepository.normalizeGameServer(server)

        val rawResponse = try {
            translator.completeUtilityPrompt(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildUserPrompt(
                    nameBox = cleanName,
                    dialogue = cleanDialogue
                ),
                maxTokens = MAX_TOKENS
            )
        } catch (e: Throwable) {
            FgoLogger.warn(tag, "本地语音档案 AI 分配失败: ${e.message}", e)
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "local_tts_voice_api_failed",
                title = "本地 TTS 语音 AI 分配失败",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                server = normalizedServer,
                speaker = nameBox,
                textPreview = cleanDialogue.previewText()
            )
            return null
        }
        FgoLogger.debug(tag, "本地语音档案 AI 响应: ${rawResponse.take(LOG_RESPONSE_CHARS)}")

        return parseResponse(rawResponse, normalizedServer, cleanName, cleanDialogue, defaultModel)
            ?.also {
                FgoLogger.info(
                    tag,
                    "本地语音档案已分配: server=$normalizedServer name=$cleanName " +
                        "type=${it.voiceType} rate=${it.rate} pitch=${it.pitch}"
                )
            }
    }

    /**
     * AI 分配失败时的兜底：直接用当前默认模型的默认音色（sid 由
     * [SherpaSpeakerMappings] 稳定 hash 决定），保证本地 TTS 一定能发声、不报错。
     */
    fun fallbackProfile(server: String, speaker: String): VoiceProfile? {
        val model = registry.preferredInstalled() ?: return null
        val normalized = VoiceNameNormalizer.normalize(speaker)
        return VoiceProfile(
            profileId = "local-fallback:$server:$normalized",
            provider = SherpaOnnxTtsProvider.PROVIDER_ID,
            locale = "zh-CN",
            voiceName = model.manifest.modelId,
            style = "",
            pitch = "0%",
            rate = "1.00",
            volume = "100",
            description = "AI分配失败默认音色"
        )
    }

    private fun buildUserPrompt(nameBox: String, dialogue: String): String {
        return buildString {
            appendLine("为 FGO 游戏中的角色分配本地离线 TTS 语气参数。")
            appendLine("name_box=$nameBox")
            appendLine("dialogue=${dialogue.ifBlank { "（空）" }}")
            appendLine("返回一行 TSV，不要解释，列顺序必须是：")
            appendLine("voice_type\tcn_rate\tcn_pitch\treason")
            appendLine("voice_type 只能选：${ALLOWED_VOICE_TYPES.joinToString(",")}")
            appendLine("cn_rate 是 0.86~1.10 的浮点数（1.00=正常语速，越界不合法）")
            appendLine("cn_pitch 是 -8%~+8% 的百分比（0%=正常音调）")
            appendLine("reason 用中文不超过 30 字")
            appendLine("根据角色的性别年龄（name_box）和台词情绪（dialogue）综合判断")
        }
    }

    private fun parseResponse(
        rawResponse: String,
        server: String,
        nameBox: String,
        dialogue: String,
        defaultModel: InstalledSherpaModel
    ): TempVoiceProfileRow? {
        val candidateLines = rawResponse
            .replace("\r", "")
            .lines()
            .map(String::trim)
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("```") }
            .filterNot { it.startsWith("voice_type", ignoreCase = true) }
        for (line in candidateLines) {
            val columns = line.split('\t').map(String::trim)
            if (columns.size < 4) continue
            val voiceType = normalizeVoiceType(columns[0]) ?: continue
            val rate = normalizeRate(columns[1])
            val pitch = normalizePitch(columns[2])
            val reason = sanitizeField(columns[3], MAX_REASON_CHARS).ifBlank { "AI分配" }
            return TempVoiceProfileRow(
                nameBox = nameBox,
                voiceType = voiceType,
                voiceName = LOCAL_PREFIX + defaultModel.manifest.modelId,
                style = voiceType,
                pitch = pitch,
                rate = rate,
                volume = DEFAULT_VOLUME.toString(),
                reason = reason,
                sourceDialogue = dialogue
            )
        }

        val preview = candidateLines.firstOrNull().orEmpty().take(LOG_RESPONSE_CHARS)
        FgoLogger.warn(tag, "本地语音档案 AI 未返回可用 TSV 行: $preview")
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
            eventId = "local_tts_voice_api_bad_tsv",
            title = "本地 TTS 语音 AI 返回格式错误",
            message = "没有可用 TSV 行",
            server = server,
            speaker = nameBox,
            detail = preview,
            textPreview = dialogue.previewText()
        )
        return null
    }

    private fun normalizeVoiceType(rawType: String): String? {
        val key = rawType.trim().lowercase(Locale.US)
            .replace('-', '_')
            .replace(' ', '_')
        return when (key) {
            in ALLOWED_VOICE_TYPES -> key
            "female", "woman", "girl" -> "young_female"
            "male", "man", "boy" -> "young_male"
            "child", "kid" -> "child_female"
            "elder", "old" -> "elder_male"
            "unknown", "neutral" -> "androgynous"
            else -> null
        }
    }

    private fun normalizePitch(rawPitch: String): String {
        val pitch = rawPitch.trim().ifBlank { return "0%" }
        val numeric = pitch.removeSuffix("%").toIntOrNull() ?: return "0%"
        val safe = numeric.coerceIn(MIN_PITCH_PERCENT, MAX_PITCH_PERCENT)
        return if (safe >= 0) "+$safe%" else "$safe%"
    }

    private fun normalizeRate(rawRate: String): String {
        val trimmed = rawRate.trim()
        val multiplier = if (trimmed.endsWith("%")) {
            val percent = trimmed.removeSuffix("%").toDoubleOrNull() ?: DEFAULT_RATE
            1.0 + percent / 100.0
        } else {
            trimmed.toDoubleOrNull() ?: DEFAULT_RATE
        }
        val safe = multiplier.coerceIn(MIN_RATE, MAX_RATE)
        return String.format(Locale.US, "%.2f", safe)
    }

    private fun sanitizeField(value: String, maxChars: Int): String {
        return value
            .replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(maxChars)
    }

    private fun String.previewText(): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .take(TEXT_PREVIEW_CHARS)
    }

    private companion object {
        const val LOCAL_PREFIX = "sherpa:"
        val ALLOWED_VOICE_TYPES = setOf(
            "child_female",
            "child_male",
            "young_female",
            "young_male",
            "mature_female",
            "mature_male",
            "elder_female",
            "elder_male",
            "androgynous",
            "mechanical",
            "monster",
            "narrator"
        )
        const val SYSTEM_PROMPT =
            "你为FGO角色选择本地离线TTS语气参数。只返回一行TSV，不要解释。reason用中文不超过30字。"
        const val MAX_TOKENS = 80
        const val MAX_NAME_CHARS = 64
        const val MAX_DIALOGUE_CHARS = 120
        const val MAX_REASON_CHARS = 30
        const val LOG_RESPONSE_CHARS = 240
        const val TEXT_PREVIEW_CHARS = 80
        const val MIN_PITCH_PERCENT = -8
        const val MAX_PITCH_PERCENT = 8
        const val MIN_RATE = 0.86
        const val MAX_RATE = 1.10
        const val DEFAULT_RATE = 1.00
        const val DEFAULT_VOLUME = 100
    }
}
