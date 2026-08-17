package com.fgogotran.voice

import com.fgogotran.analytics.AppAnalytics
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.translation.TextNormalizer
import com.fgogotran.translation.VoiceLineHint
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class TtsTestResult(
    val provider: String,
    val speakerName: String,
    val dialogue: String,
    val voiceName: String,
    val profileId: String,
    val voiceHintApplied: Boolean
)

@Singleton
class AiVoiceService @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val appAnalytics: AppAnalytics,
    private val characterVoiceRepository: CharacterVoiceRepository,
    private val tempVoiceProfileRepository: TempVoiceProfileRepository,
    private val tempVoiceProfileBuilder: TempVoiceProfileBuilder,
    private val diagnosticEventStore: DiagnosticEventStore,
    private val azureTtsClient: AzureTtsClient,
    private val azureTtsProvider: AzureTtsProvider,
    private val sherpaOnnxTtsProvider: SherpaOnnxTtsProvider,
    private val localTtsVoiceProfileBuilder: LocalTtsVoiceProfileBuilder,
    private val audioCache: VoiceAudioCache,
    private val playbackEngine: VoicePlaybackEngine
) {
    private val tag = "AiVoice"
    private val speakMutex = Mutex()
    private val tempProfileMutex = Mutex()
    private val analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val voiceRequestLock = Any()
    private val tempProfileFailureRetryAt = mutableMapOf<String, Long>()
    private var latestVoiceRequestId = 0L
    private var lastRequestedCacheMaterial: String? = null
    private var lastRequestedLineKey: String? = null

    /** 返回当前用户选择的 TTS Provider。默认 Azure，用户可在「语音设置」里切换到本地合成。 */
    private suspend fun currentProvider(): TtsProvider {
        return when (settingsRepository.getTtsProvider()) {
            SettingsRepository.TTS_PROVIDER_SHERPA_ONNX -> sherpaOnnxTtsProvider
            else -> azureTtsProvider
        }
    }

    private data class PreparedVoiceLine(
        val speaker: String,
        val profile: VoiceProfile,
        val expression: VoiceExpression?,
        val cacheMaterial: String
    )

    suspend fun speakDialogue(
        speakerName: String?,
        sourceDialogue: String?,
        translatedDialogue: String?,
        voiceHint: VoiceLineHint? = null
    ) {
        if (!settingsRepository.aiVoiceEnabled.first()) return

        val speaker = speakerName
            ?.let(::normalizeVisibleSpeakerName)
            ?.takeIf(String::isNotBlank)
            ?: return
        val dialogue = voiceTextFor(
            translatedDialogue = translatedDialogue
        )
            ?.takeIf { TextNormalizer.hasTranslatableContent(it) }
            ?: return

        val provider = currentProvider()
        if (provider.requiresCredentials) {
            // 云端 provider 需要 key；本地合成不需要
            val speechKey = settingsRepository.azureSpeechKey.first().trim()
            if (speechKey.isBlank()) {
                FgoLogger.warn(tag, "AI voice provider=${provider.providerId} 但凭证为空")
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_ERROR,
                    category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                    eventId = "tts_provider_credentials_missing",
                    title = "${provider.displayName} 凭证未设置",
                    message = "AI语音已开启，但当前 provider 所需凭证为空",
                    speaker = speaker,
                    textPreview = dialogue.previewText()
                )
                return
            }
        }

        // 本地 TTS：尽早后台预热模型（类加载 + 模型加载约 1~2 秒），
        // 与下方档案解析 / 缓存命中并行执行，隐藏首次合成的等待时间。
        if (provider is SherpaOnnxTtsProvider) {
            warmUpScope.launch { runCatching { provider.warmUp() } }
        }

        val gameServer = settingsRepository.getGameServer()
        val normalizedServer = SettingsRepository.normalizeGameServer(gameServer)
        val speakers = splitVoiceSpeakers(speaker)
        val lineKey = voiceLineKey(normalizedServer, speakers.joinToString("|"), dialogue)
        val speechRegion = SettingsRepository.normalizeAzureSpeechRegion(
            settingsRepository.azureSpeechRegion.first()
        )
        val voiceSpeedPercent = settingsRepository.aiVoiceSpeedPercent.first()
        val preparedLines = prepareVoiceLines(
            gameServer = normalizedServer,
            speakers = speakers,
            dialogue = dialogue,
            voiceHint = voiceHint,
            azureSpeechRegion = speechRegion,
            aiVoiceSpeedPercent = voiceSpeedPercent,
            allowTempApi = provider.requiresCredentials
        )
        if (preparedLines.isEmpty()) {
            FgoLogger.debug(tag, "No AI voice profile for speaker: $speaker")
            return
        }
        if (preparedLines.size > 1) {
            FgoLogger.debug(
                tag,
                "AI multi-speaker voice split original=$speaker speakers=${preparedLines.joinToString("|") { it.speaker }}"
            )
        }

        val voiceVolumePercent = settingsRepository.aiVoiceVolumePercent.first()
        val cacheMaterial = "${provider.providerId}|" +
            preparedLines.joinToString("||") { it.cacheMaterial }
        val requestId = reserveVoiceRequest(
            lineKey = lineKey,
            cacheMaterial = cacheMaterial,
            speaker = speaker
        ) ?: return

        speakMutex.withLock {
            runCatching {
                if (!isLatestVoiceRequest(requestId)) {
                    FgoLogger.debug(tag, "AI voice stale skipped before synthesis: speaker=$speaker")
                    return@runCatching
                }
                val audioFiles = synthesizeVoiceLinesViaProvider(
                    provider = provider,
                    dialogue = dialogue,
                    lines = preparedLines
                )
                if (!isLatestVoiceRequest(requestId)) {
                    FgoLogger.debug(tag, "AI voice stale skipped after synthesis: speaker=$speaker")
                    return@runCatching
                }
                val playbackStarted = withContext(Dispatchers.Main) {
                    if (!isLatestVoiceRequest(requestId)) {
                        FgoLogger.debug(tag, "AI voice stale skipped before playback: speaker=$speaker")
                        return@withContext false
                    }
                    val started = if (audioFiles.size == 1) {
                        playbackEngine.play(audioFiles.single(), voiceVolumePercent)
                    } else {
                        playbackEngine.playTogether(audioFiles, voiceVolumePercent)
                    }
                    started
                }
                if (playbackStarted) {
                    analyticsScope.launch {
                        appAnalytics.reportVoiceServerUsed(normalizedServer)
                    }
                }
            }.onFailure { e ->
                clearFailedVoiceRequest(requestId, lineKey, cacheMaterial)
                val errorMessage = e.message.orEmpty()
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_ERROR,
                    category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                    eventId = "tts_provider_synthesis_failed",
                    title = "${provider.displayName} 合成失败",
                    message = errorMessage.ifBlank { e::class.java.simpleName },
                    server = normalizedServer,
                    speaker = speaker,
                    detail = "provider=${provider.providerId} lines=${preparedLines.joinToString("|") { "${it.speaker}:${it.profile.profileId}" }}",
                    voiceType = preparedLines.joinToString(",") { it.profile.description },
                    voiceName = preparedLines.joinToString(",") { it.profile.voiceName },
                    textPreview = dialogue.previewText()
                )
                FgoLogger.warn(tag, "AI voice playback skipped for provider=${provider.providerId}", e)
            }
        }
    }

    suspend fun playTtsTest(
        speakerName: String,
        dialogue: String,
        voiceHint: VoiceLineHint? = null
    ): TtsTestResult {
        val cleanSpeaker = normalizeVisibleSpeakerName(speakerName)
            .ifBlank { TEST_VOICE_SPEAKER_JP }
        val cleanDialogue = voiceTextFor(dialogue)
            ?: throw IllegalArgumentException("Test dialogue is blank")
        val voiceSpeedPercent = settingsRepository.aiVoiceSpeedPercent.first()
        val voiceVolumePercent = settingsRepository.aiVoiceVolumePercent.first()

        val provider = currentProvider()
        return when (provider) {
            is AzureTtsProvider -> playAzureTtsTest(
                provider = provider,
                cleanSpeaker = cleanSpeaker,
                cleanDialogue = cleanDialogue,
                voiceHint = voiceHint,
                voiceSpeedPercent = voiceSpeedPercent,
                voiceVolumePercent = voiceVolumePercent
            )
            is SherpaOnnxTtsProvider -> playSherpaTtsTest(
                provider = provider,
                cleanSpeaker = cleanSpeaker,
                cleanDialogue = cleanDialogue,
                voiceSpeedPercent = voiceSpeedPercent,
                voiceVolumePercent = voiceVolumePercent
            )
            else -> throw IllegalStateException("Unknown TTS provider: ${provider.providerId}")
        }
    }

    private suspend fun playAzureTtsTest(
        provider: AzureTtsProvider,
        cleanSpeaker: String,
        cleanDialogue: String,
        voiceHint: VoiceLineHint?,
        voiceSpeedPercent: Int,
        voiceVolumePercent: Int
    ): TtsTestResult {
        val speechKey = settingsRepository.azureSpeechKey.first().trim()
        if (speechKey.isBlank()) {
            throw IllegalArgumentException("Azure Speech key is blank")
        }

        withContext(Dispatchers.IO) {
            characterVoiceRepository.reload()
        }

        val profile = resolveCuratedTestProfile(cleanSpeaker)
            ?: throw IllegalStateException("Mash voice profile not found in CDN voice data")
        val expression = voiceExpressionFor(
            profile = profile,
            dialogue = cleanDialogue,
            voiceHint = voiceHint,
            aiVoiceSpeedPercent = voiceSpeedPercent
        )
        val speechRegion = SettingsRepository.normalizeAzureSpeechRegion(
            settingsRepository.azureSpeechRegion.first()
        )
        val request = VoiceSynthesisRequest(
            speakerName = cleanSpeaker,
            spokenText = cleanDialogue,
            profile = profile,
            styleOverride = expression?.styleOverride,
            rateOverride = expression?.rateOverride,
            pitchOverride = expression?.pitchOverride,
            styleDegree = expression?.styleDegree,
            pauseScale = expression?.pauseScale,
            ssmlModeVersion = expression?.ssmlModeVersion,
            azureSpeechRegion = speechRegion,
            aiVoiceSpeedPercent = voiceSpeedPercent
        )
        val audioFile = withContext(Dispatchers.IO) {
            audioCache.cachedFile(request.cacheMaterial()) ?: audioCache.write(
                cacheMaterial = request.cacheMaterial(),
                audio = azureTtsClient.synthesize(
                    config = AzureSpeechConfig(key = speechKey, region = speechRegion),
                    profile = profile,
                    text = cleanDialogue,
                    styleOverride = expression?.styleOverride,
                    rateOverride = expression?.rateOverride,
                    pitchOverride = expression?.pitchOverride,
                    styleDegree = expression?.styleDegree,
                    pauseScale = expression?.pauseScale
                )
            )
        }

        withContext(Dispatchers.Main) {
            playbackEngine.play(audioFile, voiceVolumePercent)
        }
        val voiceHintApplied = expression?.voiceHintApplied == true
        FgoLogger.info(
            tag,
            "Azure voice test played speaker=$cleanSpeaker voice=${profile.voiceName} hintApplied=$voiceHintApplied"
        )
        return TtsTestResult(
            provider = provider.providerId,
            speakerName = cleanSpeaker,
            dialogue = cleanDialogue,
            voiceName = profile.voiceName,
            profileId = profile.profileId,
            voiceHintApplied = voiceHintApplied
        )
    }

    private suspend fun playSherpaTtsTest(
        provider: SherpaOnnxTtsProvider,
        cleanSpeaker: String,
        cleanDialogue: String,
        voiceSpeedPercent: Int,
        voiceVolumePercent: Int
    ): TtsTestResult {
        provider.warmUp()
        val voices = provider.listAvailableVoices()
        if (voices.isEmpty()) {
            throw IllegalStateException("没有可用的本地 TTS 模型，请确认内置模型已安装")
        }
        val profile = voices.first()
        val request = VoiceSynthesisRequest(
            speakerName = cleanSpeaker,
            spokenText = cleanDialogue,
            profile = profile,
            aiVoiceSpeedPercent = voiceSpeedPercent
        )
        val cacheMaterial = request.cacheMaterial()
        val audioFile = withContext(Dispatchers.IO) {
            audioCache.cachedFile(cacheMaterial) ?: run {
                val tempFile = audioCache.tempFileFor(cacheMaterial)
                provider.synthesizeToFile(request, tempFile)
                audioCache.promoteTempToCache(cacheMaterial = cacheMaterial, tempFile = tempFile)
                    ?: tempFile
            }
        }
        withContext(Dispatchers.Main) {
            playbackEngine.play(audioFile, voiceVolumePercent)
        }
        FgoLogger.info(
            tag,
            "Sherpa voice test played speaker=$cleanSpeaker voice=${profile.voiceName}"
        )
        return TtsTestResult(
            provider = provider.providerId,
            speakerName = cleanSpeaker,
            dialogue = cleanDialogue,
            voiceName = profile.voiceName,
            profileId = profile.profileId,
            voiceHintApplied = false
        )
    }

    /**
     * 试听本地模型的指定音色编号（sid）。
     * 通过 style="sid:n" 直接锁定 speaker，绕过角色映射，供「角色音色分配」列表试听。
     */
    suspend fun playSherpaSidPreview(sid: Int, text: String) {
        sherpaOnnxTtsProvider.warmUp()
        val voiceSpeedPercent = settingsRepository.aiVoiceSpeedPercent.first()
        val voiceVolumePercent = settingsRepository.aiVoiceVolumePercent.first()
        val profile = VoiceProfile(
            profileId = "sherpa-sid-preview:$sid",
            provider = SherpaOnnxTtsProvider.PROVIDER_ID,
            locale = "zh-CN",
            voiceName = "preview",
            style = "sid:$sid",
            pitch = "0%",
            rate = "0%",
            volume = "100%",
            description = "音色试听"
        )
        val request = VoiceSynthesisRequest(
            speakerName = "音色$sid",
            spokenText = text,
            profile = profile,
            aiVoiceSpeedPercent = voiceSpeedPercent
        )
        val audioFile = withContext(Dispatchers.IO) {
            val tempFile = audioCache.tempFileFor("sherpa-sid-preview-$sid")
            sherpaOnnxTtsProvider.synthesizeToFile(request, tempFile)
            tempFile
        }
        withContext(Dispatchers.Main) {
            playbackEngine.play(audioFile, voiceVolumePercent)
        }
        FgoLogger.info(tag, "Sherpa sid preview played sid=$sid text=$text")
    }

    private fun reserveVoiceRequest(lineKey: String, cacheMaterial: String, speaker: String): Long? {
        synchronized(voiceRequestLock) {
            if (lineKey == lastRequestedLineKey || cacheMaterial == lastRequestedCacheMaterial) {
                FgoLogger.debug(tag, "AI voice duplicate skipped: speaker=$speaker")
                return null
            }
            latestVoiceRequestId += 1
            lastRequestedLineKey = lineKey
            lastRequestedCacheMaterial = cacheMaterial
            return latestVoiceRequestId
        }
    }

    private fun isLatestVoiceRequest(requestId: Long): Boolean {
        return synchronized(voiceRequestLock) {
            requestId == latestVoiceRequestId
        }
    }

    private fun clearFailedVoiceRequest(requestId: Long, lineKey: String, cacheMaterial: String) {
        synchronized(voiceRequestLock) {
            if (requestId != latestVoiceRequestId) return
            if (lastRequestedLineKey == lineKey) {
                lastRequestedLineKey = null
            }
            if (lastRequestedCacheMaterial == cacheMaterial) {
                lastRequestedCacheMaterial = null
            }
        }
    }

    private suspend fun prepareVoiceLines(
        gameServer: String,
        speakers: List<String>,
        dialogue: String,
        voiceHint: VoiceLineHint?,
        azureSpeechRegion: String,
        aiVoiceSpeedPercent: Int,
        allowTempApi: Boolean = true
    ): List<PreparedVoiceLine> {
        return speakers.mapNotNull { speaker ->
            val profile = resolveVoiceProfile(
                gameServer = gameServer,
                speaker = speaker,
                dialogue = dialogue,
                allowTempApi = allowTempApi
            ) ?: run {
                FgoLogger.debug(tag, "No AI voice profile for speaker: $speaker")
                return@mapNotNull null
            }
            FgoLogger.debug(
                tag,
                "AI voice profile speaker=$speaker profile=${profile.profileId} " +
                    "voice=${profile.voiceName} source=translated_chinese"
            )
            val expression = voiceExpressionFor(
                profile = profile,
                dialogue = dialogue,
                voiceHint = voiceHint,
                aiVoiceSpeedPercent = aiVoiceSpeedPercent
            )
            val request = VoiceSynthesisRequest(
                speakerName = speaker,
                spokenText = dialogue,
                profile = profile,
                styleOverride = expression?.styleOverride,
                rateOverride = expression?.rateOverride,
                pitchOverride = expression?.pitchOverride,
                styleDegree = expression?.styleDegree,
                pauseScale = expression?.pauseScale,
                ssmlModeVersion = expression?.ssmlModeVersion,
                azureSpeechRegion = azureSpeechRegion,
                aiVoiceSpeedPercent = aiVoiceSpeedPercent
            )
            PreparedVoiceLine(
                speaker = speaker,
                profile = profile,
                expression = expression,
                cacheMaterial = request.cacheMaterial()
            )
        }
    }

    private suspend fun synthesizeVoiceLines(
        config: AzureSpeechConfig,
        dialogue: String,
        lines: List<PreparedVoiceLine>
    ): List<File> {
        return coroutineScope {
            lines.map { line ->
                async(Dispatchers.IO) {
                    audioCache.cachedFile(line.cacheMaterial) ?: audioCache.write(
                        cacheMaterial = line.cacheMaterial,
                        audio = azureTtsClient.synthesize(
                            config = config,
                            profile = line.profile,
                            text = dialogue,
                            styleOverride = line.expression?.styleOverride,
                            rateOverride = line.expression?.rateOverride,
                            pitchOverride = line.expression?.pitchOverride,
                            styleDegree = line.expression?.styleDegree,
                            pauseScale = line.expression?.pauseScale
                        )
                    )
                }
            }.awaitAll()
        }
    }

    /**
     * 通过 [TtsProvider] 抽象层统一合成多条音频。
     * - 优先走音频缓存，命中则直接返回
     * - 未命中时并发调用 provider.synthesizeToFile
     * - 同时兼容保留原来的 Azure 专用分支，避免行为回归
     */
    private suspend fun synthesizeVoiceLinesViaProvider(
        provider: TtsProvider,
        dialogue: String,
        lines: List<PreparedVoiceLine>
    ): List<File> {
        // Azure 保留原路径，以便利用其成熟的 SSML 构造和 HTTP client
        if (provider is AzureTtsProvider) {
            val key = settingsRepository.azureSpeechKey.first().trim()
            val region = SettingsRepository.normalizeAzureSpeechRegion(
                settingsRepository.azureSpeechRegion.first()
            )
            return synthesizeVoiceLines(
                AzureSpeechConfig(key = key, region = region),
                dialogue,
                lines
            )
        }
        return coroutineScope {
            lines.map { line ->
                async(Dispatchers.IO) {
                    audioCache.cachedFile(line.cacheMaterial) ?: run {
                        val outFile = audioCache.tempFileFor(line.cacheMaterial)
                        val request = VoiceSynthesisRequest(
                            speakerName = line.speaker,
                            spokenText = dialogue,
                            profile = line.profile,
                            styleOverride = line.expression?.styleOverride,
                            rateOverride = line.expression?.rateOverride,
                            pitchOverride = line.expression?.pitchOverride,
                            styleDegree = line.expression?.styleDegree,
                            pauseScale = line.expression?.pauseScale,
                            ssmlModeVersion = line.expression?.ssmlModeVersion,
                            aiVoiceSpeedPercent = settingsRepository.aiVoiceSpeedPercent.first()
                        )
                        provider.synthesizeToFile(request, outFile)
                        // 合成完成后，把临时文件登记为正式缓存项
                        audioCache.promoteTempToCache(cacheMaterial = line.cacheMaterial, tempFile = outFile)
                            ?: outFile
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun resolveVoiceProfile(
        gameServer: String,
        speaker: String,
        dialogue: String,
        allowTempApi: Boolean = true
    ): VoiceProfile? {
        val lookupCandidates = voiceSpeakerLookupCandidates(speaker)
        lookupCandidates.firstNotNullOfOrNull { candidate ->
            characterVoiceRepository.resolveProfileOrNull(candidate)
        }?.let { return it }

        val normalizedServer = SettingsRepository.normalizeGameServer(gameServer)
        lookupCandidates.firstNotNullOfOrNull { candidate ->
            tempVoiceProfileRepository.resolveProfileOrNull(normalizedServer, candidate)
        }?.let { return it }

        val normalizedSpeaker = VoiceNameNormalizer.normalize(speaker)
        val tempKey = "$normalizedServer|$normalizedSpeaker"
        val now = System.currentTimeMillis()
        val retryAt = tempProfileFailureRetryAt[tempKey] ?: 0L
        if (retryAt > now) {
            if (!allowTempApi) {
                // 本地 TTS：冷却期间直接用默认音色兜底，保持可发声
                return localTtsVoiceProfileBuilder.fallbackProfile(normalizedServer, speaker)
            }
            FgoLogger.debug(tag, "Temp voice profile API cooldown active: server=$normalizedServer speaker=$speaker")
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "temp_voice_api_cooldown",
                title = "临时语音 API 冷却中",
                message = "上次建立失败，暂时不重复请求",
                server = normalizedServer,
                speaker = speaker,
                textPreview = dialogue.previewText()
            )
            return null
        }

        return tempProfileMutex.withLock {
            lookupCandidates.firstNotNullOfOrNull { candidate ->
                characterVoiceRepository.resolveProfileOrNull(candidate)
            }?.let { return@withLock it }
            lookupCandidates.firstNotNullOfOrNull { candidate ->
                tempVoiceProfileRepository.resolveProfileOrNull(normalizedServer, candidate)
            }?.let { return@withLock it }

            FgoLogger.info(tag, "Temp voice profile miss: server=$normalizedServer speaker=$speaker")
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_MISSING_VOICE,
                eventId = "voice_profile_missing",
                title = "找不到语音档案",
                message = "主语音表与临时语音表都未命中",
                server = normalizedServer,
                speaker = speaker,
                detail = "curated=miss temp=miss",
                textPreview = dialogue.previewText()
            )
            if (!allowTempApi) {
                // 本地 TTS：无档案角色用 AI 分配本地音色/语气；AI 不可用时回退默认音色，
                // 保证角色一定有声音、不再报错。
                return@withLock buildLocalTtsProfile(
                    server = normalizedServer,
                    speaker = speaker,
                    dialogue = dialogue,
                    tempKey = tempKey,
                    now = now
                )
            }

            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_INFO,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "temp_voice_api_request",
                title = "请求建立临时语音档案",
                server = normalizedServer,
                speaker = speaker,
                textPreview = dialogue.previewText()
            )
            runCatching {
                val row = tempVoiceProfileBuilder.build(
                    server = normalizedServer,
                    nameBox = speaker,
                    dialogue = dialogue
                ) ?: throw IllegalStateException("API returned no temp voice profile")
                val profile = tempVoiceProfileRepository.upsert(normalizedServer, row)
                    ?: throw IllegalStateException("Temp voice profile could not be stored")
                row to profile
            }.onSuccess { (row, profile) ->
                tempProfileFailureRetryAt.remove(tempKey)
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_INFO,
                    category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                    eventId = "temp_voice_api_built",
                    title = "已建立临时语音档案",
                    message = "rate=${row.rate} pitch=${row.pitch}",
                    server = normalizedServer,
                    speaker = speaker,
                    detail = listOfNotNull(
                        row.style.takeIf { style -> style.isNotBlank() }?.let { style -> "style=$style" },
                        row.reason.takeIf { reason -> reason.isNotBlank() }?.let { reason -> "reason=$reason" }
                    ).joinToString(" "),
                    voiceType = row.voiceType,
                    voiceName = row.voiceName,
                    textPreview = dialogue.previewText()
                )
                profile
            }.onFailure { e ->
                tempProfileFailureRetryAt[tempKey] = System.currentTimeMillis() + TEMP_PROFILE_FAILURE_COOLDOWN_MS
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_ERROR,
                    category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                    eventId = "temp_voice_api_failed",
                    title = "建立临时语音档案失败",
                    message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                    server = normalizedServer,
                    speaker = speaker,
                    textPreview = dialogue.previewText()
                )
                FgoLogger.warn(tag, "Temp voice profile generation failed: server=$normalizedServer speaker=$speaker", e)
            }.getOrNull()?.second
        }
    }

    /**
     * 本地 TTS：无语音档案的角色通过 AI 调用自动分配本地音色/语气参数。
     *
     * - AI 分配成功：把 voice_type / cn_rate / cn_pitch 持久化到临时语音表
     *   （[TempVoiceProfileRow] 的 voiceName 带 "sherpa:" 前缀，下次直接命中缓存），
     *   并清除失败冷却。
     * - AI 分配失败（无 API key / 无已安装模型 / 返回格式错误）：记录诊断、设置
     *   冷却避免重复请求，并回退到默认音色，保证角色一定有声音、不报错。
     *
     * 注意：调用方已持有 [tempProfileMutex]，本函数不能再获取同一把锁。
     */
    private suspend fun buildLocalTtsProfile(
        server: String,
        speaker: String,
        dialogue: String,
        tempKey: String,
        now: Long
    ): VoiceProfile? {
        var assigned: VoiceProfile? = null
        runCatching {
            val row = localTtsVoiceProfileBuilder.build(
                server = server,
                nameBox = speaker,
                dialogue = dialogue
            ) ?: throw IllegalStateException("Local TTS voice AI returned no result")
            val profile = tempVoiceProfileRepository.upsert(server, row)
                ?: throw IllegalStateException("Local TTS voice profile could not be stored")
            row to profile
        }.onSuccess { (row, profile) ->
            assigned = profile
            tempProfileFailureRetryAt.remove(tempKey)
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_INFO,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "local_tts_voice_assigned",
                title = "本地 TTS 语音已自动分配",
                message = "rate=${row.rate} pitch=${row.pitch}",
                server = server,
                speaker = speaker,
                detail = listOfNotNull(
                    row.voiceType.takeIf(String::isNotBlank)?.let { "type=$it" },
                    row.reason.takeIf(String::isNotBlank)?.let { "reason=$it" }
                ).joinToString(" "),
                voiceType = row.voiceType,
                voiceName = row.voiceName,
                textPreview = dialogue.previewText()
            )
            FgoLogger.info(
                tag,
                "Local TTS voice assigned: server=$server speaker=$speaker type=${row.voiceType} rate=${row.rate}"
            )
        }.onFailure { e ->
            assigned = localTtsVoiceProfileBuilder.fallbackProfile(server, speaker)
            tempProfileFailureRetryAt[tempKey] = now + TEMP_PROFILE_FAILURE_COOLDOWN_MS
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "local_tts_voice_assignment_failed",
                title = "本地 TTS 语音 AI 分配失败，使用默认音色",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                server = server,
                speaker = speaker,
                textPreview = dialogue.previewText()
            )
            FgoLogger.warn(tag, "Local TTS voice assignment failed: server=$server speaker=$speaker", e)
        }
        return assigned
    }

    private fun voiceTextFor(
        translatedDialogue: String?
    ): String? {
        return translatedDialogue
            ?.let(TextNormalizer::stripRubyAnnotations)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun voiceLineKey(server: String, speaker: String, dialogue: String): String {
        return listOf(
            SettingsRepository.normalizeGameServer(server),
            compactSpeakerKeyText(speaker),
            compactVoiceKeyText(dialogue)
        ).joinToString("|")
    }

    private fun splitVoiceSpeakers(speaker: String): List<String> {
        val speakers = MULTI_SPEAKER_SEPARATOR_REGEX.split(speaker)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::compactSpeakerKeyText)
        return speakers.takeIf { it.size > 1 } ?: listOf(speaker)
    }

    private fun resolveCuratedTestProfile(speakerName: String): VoiceProfile? {
        val candidates = (
            voiceSpeakerLookupCandidates(speakerName) +
                listOf(
                    TEST_VOICE_SPEAKER_TRADITIONAL,
                    TEST_VOICE_SPEAKER_SIMPLIFIED,
                    TEST_VOICE_SPEAKER_JP
                )
            ).distinctBy(::compactSpeakerKeyText)
        return candidates.firstNotNullOfOrNull { candidate ->
            characterVoiceRepository.resolveProfileOrNull(candidate)
        }
    }

    private fun voiceSpeakerLookupCandidates(speaker: String): List<String> {
        val visibleSpeaker = normalizeVisibleSpeakerName(speaker)
        val strippedSpeaker = TextNormalizer.stripRubyAnnotations(speaker).trim()
        return listOf(visibleSpeaker, strippedSpeaker)
            .filter(String::isNotBlank)
            .distinctBy { candidate -> VoiceNameNormalizer.normalize(candidate) }
    }

    private fun normalizeVisibleSpeakerName(speaker: String): String {
        return TextNormalizer.normalizeForTranslation(speaker).trim()
    }

    private fun compactSpeakerKeyText(text: String): String {
        return compactKeyText(TextNormalizer.normalizeForTranslation(text).trim())
    }

    private fun compactVoiceKeyText(text: String): String {
        return compactKeyText(TextNormalizer.stripRubyAnnotations(text).trim())
    }

    private fun compactKeyText(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val compact = buildString(normalized.length) {
            normalized.forEach { char ->
                if (char.isLetterOrDigit()) {
                    append(char.lowercaseChar())
                }
            }
        }
        return compact.ifBlank {
            normalized.replace(Regex("\\s+"), "")
                .lowercase(Locale.US)
        }
    }

    private fun String.previewText(): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .take(TEXT_PREVIEW_CHARS)
    }

    private fun voiceExpressionFor(
        profile: VoiceProfile,
        dialogue: String,
        voiceHint: VoiceLineHint?,
        aiVoiceSpeedPercent: Int
    ): VoiceExpression? {
        if (!VoiceLocaleSupport.isChineseLocale(profile.locale)) {
            return null
        }
        return ChineseVoiceEmotionStyle.expressionFor(
            profile = profile,
            text = dialogue,
            voiceHint = voiceHint,
            baseSpeedMultiplier = SettingsRepository.normalizeAiVoiceSpeedPercent(aiVoiceSpeedPercent) / 100.0
        )
    }

    fun stop() {
        playbackEngine.stop()
    }

    /** 预热当前 provider（设置页切换引擎后调用，提前加载本地模型，首次朗读不用等）。 */
    suspend fun warmUpCurrentProvider() {
        runCatching { currentProvider().warmUp() }
            .onFailure { e ->
                FgoLogger.warn(tag, "TTS provider warm-up failed: ${e.message}")
            }
    }

    private companion object {
        const val TEMP_PROFILE_FAILURE_COOLDOWN_MS = 10 * 60 * 1000L
        const val TEXT_PREVIEW_CHARS = 80
        const val TEST_VOICE_SPEAKER_JP = "マシュ"
        const val TEST_VOICE_SPEAKER_SIMPLIFIED = "玛修"
        const val TEST_VOICE_SPEAKER_TRADITIONAL = "瑪修"
        val MULTI_SPEAKER_SEPARATOR_REGEX = Regex("[&/\\uFF06\\uFF0F]")
    }
}
