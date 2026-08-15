package com.fgogotran.voice

/**
 * Sherpa-ONNX 支持的 TTS 模型类型。
 *
 * - PIPER_VITS：轻量、快速，社区音色生态最好（几百种预训练 voice）
 * - MATCHA_TTS：略慢但质量更优，支持情感调节
 * - KOKORO_82M：多语言混合（含中英日），82M 参数，质量接近商业方案
 * - VITS_PLAIN：原生 VITS 模型（中文 fanchen / aishell3 等多说话人）
 */
enum class SherpaModelType {
    PIPER_VITS,
    MATCHA_TTS,
    KOKORO_82M,
    VITS_PLAIN
}

/**
 * 一个可安装的 Sherpa-ONNX TTS 模型描述（从内置列表 / 远程 JSON 读取）。
 *
 * 典型文件结构（Sherpa 约定）：
 *  - model.onnx           模型权重（带 metadata）
 *  - tokens.txt           词表
 *  - espeak-ng-data/      英文音素化数据（PIPER 需要）
 *  - dict/                中文 jieba 词典（zh 模型需要）
 */
data class SherpaOnnxModelManifest(
    val modelId: String,
    val displayName: String,
    val modelType: SherpaModelType,
    val language: String,          // "zh", "ja", "en", "zh_en" ...
    val speakerCount: Int,         // 多说话人模型的 speaker 数量
    val sampleRate: Int,           // 生成音频采样率 Hz
    val downloadUrl: String,       // 下载地址（通常是 tar.bz2 / zip）
    val archiveSizeBytes: Long,    // 压缩包大小，用于显示
    val unpackedSizeBytes: Long,   // 解压后预估大小
    val sha256: String,            // 校验
    val notes: String = "",        // 展示给用户的说明（如"中文女声187种"）
    /** Piper 模型需要给 onnx 补 metadata；若用户导入社区模型，可离线使用 python 脚本处理 */
    val requiresMetadataPatch: Boolean = false
) {
    /**
     * Sherpa-ONNX 模型需要的配置字段（根据 modelType 略有差异）。
     * 这些字段会传入 JNI 的 OfflineTtsConfig。
     */
    fun configForInstallDir(dir: String): Map<String, String> = buildMap {
        val tokens = "$dir/tokens.txt"
        val modelPath = "$dir/model.onnx"
        put("model", modelPath)
        put("tokens", tokens)
        put("num_threads", "2")
        put("sample_rate", sampleRate.toString())

        when (modelType) {
            SherpaModelType.PIPER_VITS -> {
                put("data_dir", "$dir/espeak-ng-data")
                put("provider", "piper_vits")
            }
            SherpaModelType.VITS_PLAIN -> {
                // 中文模型带 dict 目录；日文模型带 rule/fst
                val dictDir = "$dir/dict"
                if (java.io.File(dictDir).isDirectory) {
                    put("dict_dir", dictDir)
                }
                put("provider", "vits")
            }
            SherpaModelType.KOKORO_82M -> {
                put("voices", "$dir/voices.bin")
                val lexicon = listOfNotNull(
                    "$dir/lexicon-zh.txt".takeIf { java.io.File(it).exists() },
                    "$dir/lexicon-us-en.txt".takeIf { java.io.File(it).exists() }
                ).joinToString(",")
                if (lexicon.isNotBlank()) put("lexicon", lexicon)
                put("provider", "kokoro")
            }
            SherpaModelType.MATCHA_TTS -> {
                put("data_dir", "$dir/espeak-ng-data")
                put("provider", "matcha")
            }
        }
    }
}

/**
 * 已安装的本地模型运行时描述（安装目录 + 已选择的 speaker id）。
 */
data class InstalledSherpaModel(
    val manifest: SherpaOnnxModelManifest,
    val installDir: String,
    val defaultSpeakerId: Int = 0
) {
    /** 返回可用 speaker 名称列表（用于 UI 下拉选择与角色映射） */
    fun speakerNames(): List<String> = when {
        manifest.speakerCount <= 1 -> listOf("默认")
        else -> (0 until manifest.speakerCount).map { "Speaker $it" }
    }
}
