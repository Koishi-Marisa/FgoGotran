package com.fgogotran.ui.screen

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fgogotran.R
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.Translator
import com.fgogotran.translation.VoiceLineHint
import com.fgogotran.voice.AiVoiceService
import com.fgogotran.voice.SherpaOnnxModelManifest
import com.fgogotran.voice.SherpaOnnxModelRegistry
import com.fgogotran.voice.TtsTestResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    settingsRepository: SettingsRepository,
    translator: Translator,
    aiVoiceService: AiVoiceService,
    sherpaOnnxModelRegistry: SherpaOnnxModelRegistry,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var aiVoiceEnabled by remember { mutableStateOf(false) }
    var aiVoiceApiHintsEnabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_API_HINTS_ENABLED)
    }
    var aiVoiceSpeedPercent by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_SPEED_PERCENT)
    }
    var aiVoiceVolumePercent by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_VOLUME_PERCENT)
    }
    var aiVoiceNamedDialogueEnabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_NAMED_DIALOGUE_ENABLED)
    }
    var aiVoiceNoSpeakerDialogueEnabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_NO_SPEAKER_DIALOGUE_ENABLED)
    }
    var aiVoiceChoiceTextEnabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_CHOICE_TEXT_ENABLED)
    }
    var aiVoiceMasterVoice by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_MASTER_VOICE)
    }
    var ttsProvider by remember { mutableStateOf(SettingsRepository.DEFAULT_TTS_PROVIDER) }
    var azureSpeechKey by remember { mutableStateOf("") }
    var azureSpeechRegion by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AZURE_SPEECH_REGION)
    }
    var azureSpeechSaveMessage by remember { mutableStateOf("") }
    var azureSpeechTestMessage by remember { mutableStateOf("") }
    var azureSpeechTestIsError by remember { mutableStateOf(false) }
    var azureSpeechTesting by remember { mutableStateOf(false) }

    // 本地 TTS 模型管理（下载 / 切换 / 卸载）
    var selectedSherpaModelId by remember { mutableStateOf("") }
    var installedSherpaModelIds by remember { mutableStateOf(setOf<String>()) }
    var downloadingModelId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0) }
    var modelMessage by remember { mutableStateOf("") }
    var modelMessageIsError by remember { mutableStateOf(false) }

    // 加速线路（测速 / 切换）
    var selectedProxyPrefix by remember { mutableStateOf("") }
    var proxySpeeds by remember { mutableStateOf<Map<String, SherpaOnnxModelRegistry.ProxySpeedResult>>(emptyMap()) }
    var testingProxySpeed by remember { mutableStateOf(false) }

    val isAzureTtsProvider = ttsProvider == SettingsRepository.TTS_PROVIDER_AZURE
    val isSherpaTtsProvider = ttsProvider == SettingsRepository.TTS_PROVIDER_SHERPA_ONNX

    LaunchedEffect(Unit) {
        aiVoiceEnabled = settingsRepository.aiVoiceEnabled.first()
        aiVoiceApiHintsEnabled = settingsRepository.aiVoiceApiHintsEnabled.first()
        aiVoiceSpeedPercent = settingsRepository.aiVoiceSpeedPercent.first()
        aiVoiceVolumePercent = settingsRepository.aiVoiceVolumePercent.first()
        aiVoiceNamedDialogueEnabled = settingsRepository.aiVoiceNamedDialogueEnabled.first()
        aiVoiceNoSpeakerDialogueEnabled = settingsRepository.aiVoiceNoSpeakerDialogueEnabled.first()
        aiVoiceChoiceTextEnabled = settingsRepository.aiVoiceChoiceTextEnabled.first()
        aiVoiceMasterVoice = settingsRepository.aiVoiceMasterVoice.first()
        ttsProvider = settingsRepository.ttsProvider.first()
        azureSpeechKey = settingsRepository.azureSpeechKey.first()
        azureSpeechRegion = settingsRepository.azureSpeechRegion.first()
        // 先确保 APK 内置模型已安装（幂等），再读取列表，UI 才能正确显示"已安装"
        sherpaOnnxModelRegistry.ensureAssetsModelsInstalled()
        selectedSherpaModelId = settingsRepository.sherpaSelectedModel.first().trim()
        selectedProxyPrefix = settingsRepository.sherpaDownloadProxy.first().trim()
        installedSherpaModelIds = sherpaOnnxModelRegistry.listInstalled()
            .map { it.manifest.modelId }
            .toSet()
        if (ttsProvider == SettingsRepository.TTS_PROVIDER_SHERPA_ONNX) {
            // 打开设置页就后台预热本地模型，提前隐藏首次加载耗时
            scope.launch { runCatching { aiVoiceService.warmUpCurrentProvider() } }
        }
    }

    fun refreshSherpaModelState() {
        installedSherpaModelIds = sherpaOnnxModelRegistry.listInstalled()
            .map { it.manifest.modelId }
            .toSet()
    }

    fun downloadSherpaModel(manifest: SherpaOnnxModelManifest) {
        if (downloadingModelId != null) return
        downloadingModelId = manifest.modelId
        downloadProgress = 0
        modelMessage = ""
        modelMessageIsError = false
        // 下载在应用级作用域执行：切后台 / 离开页面也不会中断；失败自动保留断点可续传
        sherpaOnnxModelRegistry.downloadAndInstallAsync(
            manifest = manifest,
            onProgress = { percent, message ->
                downloadProgress = percent
                modelMessage = message
            },
            onSuccess = {
                selectedSherpaModelId = manifest.modelId
                refreshSherpaModelState()
                modelMessage = "「${manifest.displayName}」下载并安装完成，已切换为当前模型"
                modelMessageIsError = false
                scope.launch { runCatching { aiVoiceService.warmUpCurrentProvider() } }
                downloadingModelId = null
            },
            onError = { msg ->
                modelMessageIsError = true
                modelMessage = "下载失败：$msg"
                downloadingModelId = null
            }
        )
    }

    fun selectProxyLine(line: SherpaOnnxModelRegistry.GhProxyLine) {
        scope.launch {
            settingsRepository.setSherpaDownloadProxy(line.prefix)
            selectedProxyPrefix = line.prefix
            modelMessage = "下载线路已切换为 ${line.label}，下次下载生效"
            modelMessageIsError = false
        }
    }

    fun testAllProxyLines() {
        if (testingProxySpeed) return
        testingProxySpeed = true
        proxySpeeds = emptyMap()
        modelMessage = "正在测速…"
        modelMessageIsError = false
        scope.launch {
            // 用内置模型（zh-ll，官方 release 稳定存在）做探针
            val probeUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-vits-zh-ll.tar.bz2"
            val results = SherpaOnnxModelRegistry.GH_PROXY_LINES.map { line ->
                async { sherpaOnnxModelRegistry.testProxySpeed(line, probeUrl) }
            }.awaitAll()
            proxySpeeds = results.associateBy { it.line.prefix }
            val fastest = results.filter { it.success }.maxByOrNull { it.speedMBps }
            modelMessage = if (fastest != null) {
                "测速完成，最快：${fastest.line.label}（${"%.1f".format(fastest.speedMBps)} MB/s）"
            } else {
                "所有线路均不可用，请检查网络后重试"
            }
            modelMessageIsError = fastest == null
            testingProxySpeed = false
        }
    }

    fun selectSherpaModel(modelId: String) {
        scope.launch {
            settingsRepository.setSherpaSelectedModel(modelId)
            selectedSherpaModelId = modelId
            modelMessage = "已切换为当前模型"
            modelMessageIsError = false
            runCatching { aiVoiceService.warmUpCurrentProvider() }
        }
    }

    fun uninstallSherpaModel(modelId: String) {
        scope.launch {
            runCatching { sherpaOnnxModelRegistry.uninstall(modelId) }
                .onSuccess {
                    refreshSherpaModelState()
                    if (selectedSherpaModelId == modelId) {
                        settingsRepository.setSherpaSelectedModel("")
                        selectedSherpaModelId = ""
                        modelMessage = "已卸载；将自动使用其余已安装模型"
                    } else {
                        modelMessage = "已卸载"
                    }
                    modelMessageIsError = false
                    runCatching { aiVoiceService.warmUpCurrentProvider() }
                }
                .onFailure { e ->
                    modelMessageIsError = true
                    modelMessage = "卸载失败：${e.message.orEmpty().take(96)}"
                }
        }
    }

    fun saveAzureSpeechSettings() {
        scope.launch {
            settingsRepository.saveAzureSpeechSettings(azureSpeechKey, azureSpeechRegion)
            azureSpeechSaveMessage = "已保存"
        }
    }

    fun testTtsVoice() {
        if (azureSpeechTesting) return
        scope.launch {
            azureSpeechTesting = true
            azureSpeechSaveMessage = ""
            azureSpeechTestIsError = false
            azureSpeechTestMessage = "正在生成测试语音..."
            try {
                if (isAzureTtsProvider && azureSpeechKey.trim().isBlank()) {
                    throw IllegalArgumentException("Azure Speech key is blank")
                }
                if (isAzureTtsProvider) {
                    settingsRepository.saveAzureSpeechSettings(azureSpeechKey, azureSpeechRegion)
                }
                val sample = azureVoiceTestSample(settingsRepository.targetChineseLocale.first())
                var voiceHint: VoiceLineHint? = null
                var voiceHintError: Throwable? = null
                if (isAzureTtsProvider && aiVoiceApiHintsEnabled) {
                    runCatching {
                        translator.testVoiceHint(sample.speakerName, sample.dialogue)
                    }.onSuccess { hint ->
                        voiceHint = hint
                    }.onFailure { error ->
                        voiceHintError = error
                    }
                }
                val result = aiVoiceService.playTtsTest(
                    speakerName = sample.speakerName,
                    dialogue = sample.dialogue,
                    voiceHint = voiceHint
                )
                azureSpeechTestMessage = voiceTestSuccessMessage(
                    result = result,
                    apiHintsEnabled = isAzureTtsProvider && aiVoiceApiHintsEnabled,
                    apiHintError = voiceHintError
                )
            } catch (e: Throwable) {
                azureSpeechTestIsError = true
                azureSpeechTestMessage = voiceTestErrorMessage(e, isSherpaTtsProvider)
            } finally {
                azureSpeechTesting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            VoiceSettingsCard(
                title = "AI 语音朗读",
                body = "按角色朗读剧情台词。"
            ) {
                VoiceSwitchRow(
                    title = "启用语音",
                    body = "",
                    checked = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceEnabled = it
                        scope.launch { settingsRepository.setAiVoiceEnabled(it) }
                    }
                )
            }

            VoiceSettingsCard(
                title = "语音合成引擎",
                body = "选择使用云端 Azure 还是本地离线模型。"
            ) {
                VoiceTtsProviderOption(
                    title = "Azure Neural TTS (云端)",
                    subtitle = "需要 Azure Speech Key，音色最丰富",
                    selected = isAzureTtsProvider,
                    onClick = {
                        ttsProvider = SettingsRepository.TTS_PROVIDER_AZURE
                        scope.launch {
                            settingsRepository.setTtsProvider(SettingsRepository.TTS_PROVIDER_AZURE)
                        }
                    }
                )
                VoiceTtsProviderOption(
                    title = "Sherpa-ONNX 本地离线合成",
                    subtitle = "无需 Key，使用内置 vits-zh-ll 模型",
                    selected = isSherpaTtsProvider,
                    onClick = {
                        ttsProvider = SettingsRepository.TTS_PROVIDER_SHERPA_ONNX
                        scope.launch {
                            settingsRepository.setTtsProvider(SettingsRepository.TTS_PROVIDER_SHERPA_ONNX)
                            // 立即后台预热本地模型，之后第一次朗读不用等模型加载
                            runCatching { aiVoiceService.warmUpCurrentProvider() }
                        }
                    }
                )
            }

            VoiceSettingsCard(
                title = "下载线路（GitHub 加速）",
                body = "国内访问 GitHub 困难时自动走加速线路。可手动测速选择最快线路；下载失败会自动依次尝试其他线路并记住可用的。",
                iconRes = R.drawable.ic_settings_voice
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (selectedProxyPrefix.isBlank()) "当前：自动选择" else "当前：${
                            SherpaOnnxModelRegistry.GH_PROXY_LINES.firstOrNull { it.prefix == selectedProxyPrefix }?.label
                                ?: selectedProxyPrefix
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    OutlinedButton(
                        onClick = { testAllProxyLines() },
                        enabled = !testingProxySpeed
                    ) {
                        Text(if (testingProxySpeed) "测速中…" else "重新测速")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                SherpaOnnxModelRegistry.GH_PROXY_LINES.forEach { line ->
                    val result = proxySpeeds[line.prefix]
                    ProxyLineRow(
                        line = line,
                        selected = line.prefix == selectedProxyPrefix,
                        result = result,
                        testing = testingProxySpeed,
                        onClick = { selectProxyLine(line) }
                    )
                }
            }

            VoiceSettingsCard(
                title = "本地 TTS 模型",
                body = "在 App 内下载 / 切换本地合成模型（当前引擎为 Azure 时也可预先下载，切换到本地后生效）。下载完成后自动设为当前模型。",
                iconRes = R.drawable.ic_settings_voice
            ) {
                if (modelMessage.isNotBlank()) {
                    Text(
                        modelMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (modelMessageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                sherpaOnnxModelRegistry.builtinCatalog().forEach { manifest ->
                    LocalTtsModelRow(
                        manifest = manifest,
                        installed = manifest.modelId in installedSherpaModelIds,
                        selected = manifest.modelId == selectedSherpaModelId,
                        bundled = sherpaOnnxModelRegistry.isBundledInApk(manifest.modelId),
                        downloading = manifest.modelId == downloadingModelId,
                        progress = if (downloadingModelId == manifest.modelId) downloadProgress else 0,
                        onDownload = { downloadSherpaModel(manifest) },
                        onSelect = { selectSherpaModel(manifest.modelId) },
                        onUninstall = { uninstallSherpaModel(manifest.modelId) }
                    )
                }
            }

            VoiceSettingsCard(
                title = "朗读范围",
                body = ""
            ) {
                VoiceCheckboxRow(
                    title = "有角色名对话",
                    body = "使用对应角色语音。",
                    checked = aiVoiceNamedDialogueEnabled,
                    enabled = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceNamedDialogueEnabled = it
                        scope.launch { settingsRepository.setAiVoiceNamedDialogueEnabled(it) }
                    }
                )
                VoiceCheckboxRow(
                    title = "旁白／无名对白",
                    body = "使用旁白语音。",
                    checked = aiVoiceNoSpeakerDialogueEnabled,
                    enabled = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceNoSpeakerDialogueEnabled = it
                        scope.launch { settingsRepository.setAiVoiceNoSpeakerDialogueEnabled(it) }
                    }
                )
                VoiceCheckboxRow(
                    title = "御主选项",
                    body = "使用御主（男）/（女）语音。",
                    checked = aiVoiceChoiceTextEnabled,
                    enabled = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceChoiceTextEnabled = it
                        scope.launch { settingsRepository.setAiVoiceChoiceTextEnabled(it) }
                    }
                )
                Text(
                    "选项文字声音",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (aiVoiceEnabled && aiVoiceChoiceTextEnabled) 0.82f else 0.48f
                    )
                )
                VoiceMasterVoiceOption(
                    title = "御主（男）",
                    selected = aiVoiceMasterVoice == SettingsRepository.AI_VOICE_MASTER_MALE,
                    enabled = aiVoiceEnabled && aiVoiceChoiceTextEnabled,
                    onClick = {
                        aiVoiceMasterVoice = SettingsRepository.AI_VOICE_MASTER_MALE
                        scope.launch {
                            settingsRepository.setAiVoiceMasterVoice(SettingsRepository.AI_VOICE_MASTER_MALE)
                        }
                    }
                )
                VoiceMasterVoiceOption(
                    title = "御主（女）",
                    selected = aiVoiceMasterVoice == SettingsRepository.AI_VOICE_MASTER_FEMALE,
                    enabled = aiVoiceEnabled && aiVoiceChoiceTextEnabled,
                    onClick = {
                        aiVoiceMasterVoice = SettingsRepository.AI_VOICE_MASTER_FEMALE
                        scope.launch {
                            settingsRepository.setAiVoiceMasterVoice(SettingsRepository.AI_VOICE_MASTER_FEMALE)
                        }
                    }
                )
            }

            VoiceSettingsCard(
                title = "朗读文本",
                body = ""
            ) {
                VoiceReadTextOption(
                    title = "中文",
                    selected = true
                )
            }

            VoiceSettingsCard(
                title = "表现调节",
                body = ""
            ) {
                VoiceSpeedSlider(
                    speedPercent = aiVoiceSpeedPercent,
                    onSpeedChange = { speedPercent ->
                        val normalizedSpeed = SettingsRepository.normalizeAiVoiceSpeedPercent(speedPercent)
                        if (normalizedSpeed != aiVoiceSpeedPercent) {
                            aiVoiceSpeedPercent = normalizedSpeed
                            scope.launch { settingsRepository.setAiVoiceSpeedPercent(normalizedSpeed) }
                        }
                    }
                )
                VoiceVolumeSlider(
                    volumePercent = aiVoiceVolumePercent,
                    enabled = aiVoiceEnabled,
                    onVolumeChange = { volumePercent ->
                        val normalizedVolume = SettingsRepository.normalizeAiVoiceVolumePercent(volumePercent)
                        if (normalizedVolume != aiVoiceVolumePercent) {
                            aiVoiceVolumePercent = normalizedVolume
                            scope.launch { settingsRepository.setAiVoiceVolumePercent(normalizedVolume) }
                        }
                    }
                )
                VoiceSwitchRow(
                    title = "AI 语气增强",
                    body = "开启：调用 API 分析本句情绪、语速、音高，并临时匹配语音；新角色也可尝试播放。\n关闭：只用本机规则和已收录语音；更快、更稳定，但新角色需等数据库更新后才有语音。",
                    checked = aiVoiceApiHintsEnabled,
                    onCheckedChange = {
                        aiVoiceApiHintsEnabled = it
                        scope.launch { settingsRepository.setAiVoiceApiHintsEnabled(it) }
                    }
                )
            }

            VoiceSettingsCard(
                title = "Azure Speech",
                body = if (isSherpaTtsProvider) "当前使用本地离线合成，无需配置 Azure Key。" else "",
                iconRes = R.drawable.ic_speech_services
            ) {
                Text(
                    "Azure 区域",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isAzureTtsProvider) 0.82f else 0.48f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    azureSpeechRegionOptions.forEach { option ->
                        AzureSpeechRegionOptionRow(
                            option = option,
                            selected = option.region == azureSpeechRegion,
                            enabled = isAzureTtsProvider && !azureSpeechTesting,
                            onClick = {
                                val normalizedRegion = SettingsRepository.normalizeAzureSpeechRegion(option.region)
                                azureSpeechRegion = normalizedRegion
                                azureSpeechSaveMessage = ""
                                azureSpeechTestMessage = ""
                                azureSpeechTestIsError = false
                                scope.launch { settingsRepository.setAzureSpeechRegion(normalizedRegion) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    "提示：可选全球 Azure；中国 Azure 需要组织/工作/学校账号，个人 Microsoft 账号不能登录使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isAzureTtsProvider) 0.58f else 0.38f)
                )
                OutlinedTextField(
                    value = azureSpeechKey,
                    onValueChange = {
                        azureSpeechKey = it
                        azureSpeechSaveMessage = ""
                        azureSpeechTestMessage = ""
                        azureSpeechTestIsError = false
                    },
                    label = { Text("Azure Speech Key") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isAzureTtsProvider,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        Text("仅保存在本机，用于 AI 语音朗读。")
                    },
                    singleLine = true
                )
                Text(
                    "测试例句：玛修・基列莱特，在此。御主……战斗准备完成，请下达指示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isAzureTtsProvider) 0.58f else 0.48f)
                )
                if (azureSpeechTestMessage.isNotBlank()) {
                    Text(
                        azureSpeechTestMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (azureSpeechTestIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (azureSpeechSaveMessage.isNotBlank()) {
                        Text(
                            azureSpeechSaveMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    OutlinedButton(
                        onClick = { testTtsVoice() },
                        enabled = !azureSpeechTesting
                    ) {
                        Text(
                            when {
                                azureSpeechTesting -> "测试中..."
                                isSherpaTtsProvider -> "测试本地语音"
                                else -> "测试语音"
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { saveAzureSpeechSettings() },
                        enabled = isAzureTtsProvider
                    ) {
                        Text("保存语音设置")
                    }
                }
            }
        }
    }
}

private data class AzureVoiceTestSample(
    val speakerName: String,
    val dialogue: String
)

private data class AzureSpeechRegionOption(
    val region: String,
    val title: String,
    val subtitle: String
)

private val azureSpeechRegionOptions = listOf(
    AzureSpeechRegionOption(
        region = SettingsRepository.AZURE_SPEECH_REGION_GLOBAL_SOUTHEAST_ASIA,
        title = "全球 Azure",
        subtitle = "southeastasia"
    ),
    AzureSpeechRegionOption(
        region = SettingsRepository.AZURE_SPEECH_REGION_CHINA_NORTH3,
        title = "中国 Azure",
        subtitle = "chinanorth3"
    )
)

private fun azureVoiceTestSample(targetChineseLocale: String): AzureVoiceTestSample {
    return if (
        SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale) ==
        SettingsRepository.TARGET_LOCALE_TRADITIONAL
    ) {
        AzureVoiceTestSample(
            speakerName = "瑪修",
            dialogue = "瑪修・基列萊特，在此。御主……戰鬥準備完成，請下達指示。"
        )
    } else {
        AzureVoiceTestSample(
            speakerName = "玛修",
            dialogue = "玛修·基列莱特，在此。御主……战斗准备完成，请下达指示。"
        )
    }
}

private fun voiceTestSuccessMessage(
    result: TtsTestResult,
    apiHintsEnabled: Boolean,
    apiHintError: Throwable?
): String {
    val apiStatus = when {
        !apiHintsEnabled -> ""
        apiHintError != null -> "；语气增强 API 失败"
        result.voiceHintApplied -> "；语气增强 API 正常"
        else -> "；语气增强 API 已连线，本句未套用语气"
    }
    return "测试语音已播放$apiStatus"
}

private fun voiceTestErrorMessage(error: Throwable, isSherpa: Boolean): String {
    val message = error.message.orEmpty()
    return when {
        isSherpa && (
            message.contains("JNI 库未找到", ignoreCase = true) ||
                message.contains("sherpa-onnx-jni", ignoreCase = true)
            ) -> {
            "本地 TTS 库未找到，请确认 APK 包含 sherpa-onnx 运行时"
        }
        isSherpa && message.contains("没有已安装的本地 TTS 模型", ignoreCase = true) -> {
            "内置模型未安装，请重启应用或检查存储权限"
        }
        isSherpa -> "本地 TTS 测试失败：${message.take(96)}"
        message.contains("Azure Speech key is blank", ignoreCase = true) -> {
            "Azure Speech Key 为空"
        }
        message.contains("HTTP 401", ignoreCase = true) ||
            message.contains("HTTP 403", ignoreCase = true) -> {
            "Azure Key 无效，或当前区域不可用"
        }
        message.contains("Azure TTS failed", ignoreCase = true) -> {
            "Azure 语音请求失败：${message.take(96)}"
        }
        message.contains("Mash voice profile not found", ignoreCase = true) -> {
            "找不到瑪修语音档，请先更新语音资料"
        }
        message.isNotBlank() -> "测试失败：${message.take(96)}"
        else -> "测试失败：${error::class.java.simpleName}"
    }
}

@Composable
private fun VoiceSpeedSlider(
    speedPercent: Int,
    onSpeedChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "语速",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
            Text(
                aiVoiceSpeedMultiplierLabel(speedPercent),
                style = MaterialTheme.typography.bodyMedium,
                color = if (speedPercent == SettingsRepository.DEFAULT_AI_VOICE_SPEED_PERCENT) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                textAlign = TextAlign.End
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "慢",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Slider(
                value = speedPercent.toFloat(),
                onValueChange = { rawValue ->
                    onSpeedChange(rawValue.roundToInt())
                },
                valueRange = SettingsRepository.MIN_AI_VOICE_SPEED_PERCENT.toFloat()..
                    SettingsRepository.MAX_AI_VOICE_SPEED_PERCENT.toFloat(),
                steps = aiVoiceSpeedSliderSteps(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text(
                "快",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun aiVoiceSpeedMultiplierLabel(speedPercent: Int): String {
    val normalized = SettingsRepository.normalizeAiVoiceSpeedPercent(speedPercent)
    return "${normalized / 100}.${(normalized % 100).toString().padStart(2, '0')}x"
}

private fun aiVoiceSpeedSliderSteps(): Int {
    return SettingsRepository.MAX_AI_VOICE_SPEED_PERCENT -
        SettingsRepository.MIN_AI_VOICE_SPEED_PERCENT -
        1
}

@Composable
private fun VoiceVolumeSlider(
    volumePercent: Int,
    enabled: Boolean,
    onVolumeChange: (Int) -> Unit
) {
    val normalizedVolume = SettingsRepository.normalizeAiVoiceVolumePercent(volumePercent)
    val contentAlpha = if (enabled) 0.82f else 0.48f
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "音量",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                "$normalizedVolume%",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled && normalizedVolume != SettingsRepository.DEFAULT_AI_VOICE_VOLUME_PERCENT) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                },
                textAlign = TextAlign.End
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "小",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.48f)
            )
            Slider(
                value = normalizedVolume.toFloat(),
                onValueChange = { rawValue ->
                    onVolumeChange(rawValue.roundToInt())
                },
                valueRange = SettingsRepository.MIN_AI_VOICE_VOLUME_PERCENT.toFloat()..
                    SettingsRepository.MAX_AI_VOICE_VOLUME_PERCENT.toFloat(),
                steps = aiVoiceVolumeSliderSteps(),
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text(
                "大",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.48f)
            )
        }
    }
}

private fun aiVoiceVolumeSliderSteps(): Int {
    return SettingsRepository.MAX_AI_VOICE_VOLUME_PERCENT -
        SettingsRepository.MIN_AI_VOICE_VOLUME_PERCENT -
        1
}

@Composable
private fun AzureSpeechRegionOptionRow(
    option: AzureSpeechRegionOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 1f else 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    option.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center
                )
                Text(
                    option.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun VoiceCheckboxRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.82f else 0.48f)
            )
            if (body.isNotBlank()) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.6f else 0.38f)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun VoiceMasterVoiceOption(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 0.32f else 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = if (enabled) onClick else null,
                enabled = enabled
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = when {
                        !enabled -> 0.48f
                        selected -> 0.82f
                        else -> 0.68f
                    }
                )
            )
        }
    }
}

@Composable
private fun VoiceTtsProviderOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        }
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = null
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (selected) 0.82f else 0.68f
                    )
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
            }
        }
    }
}

@Composable
private fun VoiceReadTextOption(
    title: String,
    selected: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = false
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 0.82f else 0.48f)
            )
        }
    }
}

@Composable
private fun ProxyLineRow(
    line: SherpaOnnxModelRegistry.GhProxyLine,
    selected: Boolean,
    result: SherpaOnnxModelRegistry.ProxySpeedResult?,
    testing: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !testing) { onClick() },
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = false
            )
            Text(
                line.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                modifier = Modifier.weight(1f)
            )
            when {
                testing -> Text(
                    "测速中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                result == null -> Text(
                    "未测速",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                result.success -> Text(
                    "${"%.1f".format(result.speedMBps)} MB/s · ${result.latencyMs / 1000}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                else -> Text(
                    "不可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun LocalTtsModelRow(
    manifest: SherpaOnnxModelManifest,
    installed: Boolean,
    selected: Boolean,
    bundled: Boolean,
    downloading: Boolean,
    progress: Int,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onUninstall: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    manifest.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                when {
                    selected -> Text(
                        "当前使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    installed -> Text(
                        if (bundled) "内置 · 已安装" else "已安装",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Text(
                buildString {
                    if (manifest.archiveSizeBytes > 0) append("${formatModelSize(manifest.archiveSizeBytes)} · ")
                    append("${manifest.speakerCount} 音色")
                    if (manifest.notes.isNotBlank()) append(" · ${manifest.notes}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            when {
                downloading -> {
                    Text(
                        "下载中 $progress%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                installed -> {
                    if (bundled) {
                        Button(
                            onClick = onSelect,
                            enabled = !selected,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (selected) "当前使用" else "使用此模型")
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onUninstall,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("卸载")
                            }
                            Button(
                                onClick = onSelect,
                                enabled = !selected,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (selected) "当前使用" else "使用此模型")
                            }
                        }
                    }
                }
                else -> {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (manifest.archiveSizeBytes > 0) {
                                "下载（${formatModelSize(manifest.archiveSizeBytes)}）"
                            } else {
                                "下载"
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun formatModelSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}

@Composable
private fun VoiceSettingsCard(
    title: String,
    body: String,
    @DrawableRes iconRes: Int? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (iconRes != null) {
                    VoiceSettingsIconBadge(iconRes)
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (body.isNotBlank()) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            content()
        }
    }
}

@Composable
private fun VoiceSettingsIconBadge(@DrawableRes iconRes: Int) {
    Surface(
        modifier = Modifier.size(36.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.small
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun VoiceSwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
