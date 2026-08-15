package com.fgogotran.voice

import java.io.File

/**
 * 可插拔的 TTS 合成后端接口。
 *
 * 现有两种实现：
 * - AzureTtsProvider：调用 Azure Speech Service 云端合成
 * - SherpaOnnxTtsProvider：使用 Sherpa-ONNX 本地 ONNX 推理（Piper/VITS/Kokoro）
 *
 * 新增本地合成后端时，实现此接口并在 AiVoiceService 中注册即可。
 */
interface TtsProvider {

    /** Provider 唯一标识，如 "azure", "sherpa_onnx_piper", "sherpa_onnx_kokoro" */
    val providerId: String

    /** 面向用户的显示名称 */
    val displayName: String

    /** 是否需要网络连接（本地合成返回 false） */
    val requiresNetwork: Boolean

    /** 是否需要 API Key 等凭证配置 */
    val requiresCredentials: Boolean

    /**
     * 执行一次语音合成，输出为音频文件（wav/mp3）。
     *
     * @param request  合成请求（含文本、音色、情感、速度/音调参数）
     * @param outputFile 写入目标文件（调用方确保目录可写）
     * @return 写入成功返回 true；失败时抛出带诊断信息的异常
     */
    suspend fun synthesizeToFile(
        request: VoiceSynthesisRequest,
        outputFile: File
    ): Boolean

    /**
     * 返回当前 provider 已注册的音色列表（用于 UI 下拉选择与角色匹配）。
     * 本地 provider 应返回已安装模型的 speaker 列表；云端 provider 返回可用的 neural voice 列表。
     */
    suspend fun listAvailableVoices(): List<VoiceProfile>

    /**
     * 预热 / 加载模型。本地 provider 首次调用合成前会被触发；云端通常 no-op。
     * @throws Exception 初始化失败时抛出，调用方应降级或提示用户
     */
    suspend fun warmUp() {}

    /** 释放模型、关闭引擎（进程退出或切换 provider 时调用） */
    fun close() {}
}
