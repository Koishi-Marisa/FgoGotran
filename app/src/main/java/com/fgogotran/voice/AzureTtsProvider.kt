package com.fgogotran.voice

import android.content.Context
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将现有 AzureTtsClient 包装为 TtsProvider 实现，保持向后兼容。
 */
@Singleton
class AzureTtsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val azureTtsClient: AzureTtsClient,
    private val diagnosticEventStore: DiagnosticEventStore,
    private val settingsRepository: SettingsRepository
) : TtsProvider {

    override val providerId: String = "azure"
    override val displayName: String = "Azure Neural TTS (云端)"
    override val requiresNetwork: Boolean = true
    override val requiresCredentials: Boolean = true

    override suspend fun synthesizeToFile(
        request: VoiceSynthesisRequest,
        outputFile: File
    ): Boolean {
        val key = settingsRepository.azureSpeechKey.first().trim()
        val region = settingsRepository.azureSpeechRegion.first().trim()
            .ifBlank { SettingsRepository.DEFAULT_AZURE_SPEECH_REGION }

        if (key.isBlank()) {
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                eventId = "azure_tts_key_missing",
                title = "Azure TTS Key 未配置",
                message = "provider=$providerId"
            )
            throw IllegalStateException("Azure Speech Key 为空")
        }

        val audioBytes = azureTtsClient.synthesize(
            config = AzureSpeechConfig(key = key, region = region),
            profile = request.profile,
            text = request.spokenText,
            styleOverride = request.styleOverride,
            rateOverride = request.rateOverride,
            pitchOverride = request.pitchOverride,
            styleDegree = request.styleDegree,
            pauseScale = request.pauseScale
        )

        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(audioBytes)
        FgoLogger.info(
            "AzureTtsProvider",
            "合成完成: ${audioBytes.size} bytes → ${outputFile.name}"
        )
        return audioBytes.isNotEmpty()
    }

    override suspend fun listAvailableVoices(): List<VoiceProfile> {
        // Azure 列表较大，这里返回 CharacterVoiceRepository 已加载的常用音色即可
        // （保留原有行为：角色音色由 voice profiles TSV 管理）
        return emptyList()
    }
}
