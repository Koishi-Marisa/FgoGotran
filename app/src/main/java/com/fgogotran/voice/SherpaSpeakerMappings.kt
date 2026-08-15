package com.fgogotran.voice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 方案 A：为 FGO 常见角色预设与「当前安装的 Sherpa 多 speaker 模型」之间的 speaker id 映射。
 *
 * ### 三层优先级（越靠前优先级越高）
 * 1. **显式覆盖（TSV / TempVoice API 里写死）**：`VoiceProfile.style` 若满足 `sid:17` 格式，直接用；
 * 2. **角色内置映射表（本组件）**：按「模型 id × 角色名」精确匹配，例如 Saber 在 fanchen-C 用 sid=22；
 * 3. **稳定 hash fallback**：根据「角色名 + 性别描述」做 hash，保证同一个角色在同一模型下永远落到相同 speaker id，避免每句话音色跳变。
 */
@Singleton
class SherpaSpeakerMappings @Inject constructor() {

    /** 对外入口：返回在 [installed] 模型范围内可用的 speaker id（0..speakerCount-1）。 */
    fun resolveSpeakerId(
        speakerName: String?,
        profile: VoiceProfile,
        installed: InstalledSherpaModel
    ): Int {
        // 优先级 1：显式 "sid:xxx" 覆盖（TSV 列或用户自定义）
        val explicit = explicitSidFrom(profile.style)
            ?: explicitSidFrom(profile.description)
            ?: profile.profileId.takeIf { it.startsWith("sid:") }?.substring(4)?.toIntOrNull()
        if (explicit != null) {
            return clamp(explicit, installed.manifest.speakerCount)
        }

        val normalizedSpeaker = (speakerName ?: profile.profileId)
            .let(VoiceNameNormalizer::normalize)
            .takeIf { it.isNotBlank() } ?: run {
            return clamp(installed.defaultSpeakerId, installed.manifest.speakerCount)
        }

        // 优先级 2：针对模型类型的角色预设表
        val byModel = when (installed.manifest.modelId) {
            FANCHEN_C_MODEL_ID -> fgoRoleToFanchenC[normalizedSpeaker]
            ZH_LL_MODEL_ID -> fgoRoleToZhLl[normalizedSpeaker]
            KOKORO_MODEL_ID -> fgoRoleToKokoro[normalizedSpeaker]
            PIPER_JA_AMAKUSA_MODEL_ID,
            PIPER_JA_TSUKUYOMI_MODEL_ID -> 0   // 单音色 Piper
            PIPER_JA_ONOMA_MODEL_ID,
            VITS_JA_MASARU_MODEL_ID -> 0       // 单音色男声
            VITS_JA_MEI_MODEL_ID -> 0          // 单音色女声
            else -> null
        }
        if (byModel != null) {
            return clamp(byModel, installed.manifest.speakerCount)
        }

        // 优先级 3：根据角色性别 + 名称稳定 hash，落在模型支持的区间内
        val bucket = stableBucketFor(
            name = normalizedSpeaker,
            genderHint = profile.description,
            max = installed.manifest.speakerCount
        )
        return clamp(bucket, installed.manifest.speakerCount)
    }

    // ==================================================================
    // 各模型的 FGO 角色 → speaker id 预设
    // ==================================================================

    companion object {
        private const val FANCHEN_C_MODEL_ID = "vits-zh-fanchen-C"
        private const val ZH_LL_MODEL_ID = "vits-zh-ll"
        private const val KOKORO_MODEL_ID = "kokoro-82m-multi"
        private const val PIPER_JA_AMAKUSA_MODEL_ID = "piper-ja_JP-amakusa-medium"
        private const val PIPER_JA_TSUKUYOMI_MODEL_ID = "piper-ja_JP-tsukuyomi-low"
        private const val PIPER_JA_ONOMA_MODEL_ID = "piper-ja_JP-onoma-medium"
        private const val VITS_JA_MEI_MODEL_ID = "vits-ja-vits-mei"
        private const val VITS_JA_MASARU_MODEL_ID = "vits-ja-vits-masaru"

        /**
         * fanchen-C (187 speaker) → FGO 常见角色
         * 取值在 0..186 之间，按"女/男/萝莉/御姐/中年/少年"音感分布抽样。
         */
        private val fgoRoleToFanchenC: Map<String, Int> = mapOf(
            // 御主
            "藤丸立香" to 71,
            "咕哒子" to 71,
            "咕哒夫" to 131,
            "主人公" to 71,
            "立香" to 71,
            // 女主角/后辈
            "玛修基列莱特" to 48,
            "玛修" to 48,
            "学妹" to 48,
            "马修" to 48,
            // Saber 系
            "阿尔托莉雅潘德拉贡" to 22,
            "阿尔托莉雅" to 22,
            "Saber" to 22,
            "呆毛王" to 22,
            "阿尔托莉雅潘德拉贡Alter" to 15,
            "黑Saber" to 15,
            "SaberAlter" to 15,
            "阿尔托莉雅潘德拉贡Lily" to 9,
            "SaberLily" to 9,
            "尼禄克劳狄乌斯" to 41,
            "尼禄" to 41,
            "红Saber" to 41,
            "冲田总司" to 12,
            "樱Saber" to 12,
            "宫本武藏" to 56,
            "武藏" to 56,
            // 远坂家/间桐家
            "远坂凛" to 56,
            "凛" to 56,
            "间桐樱" to 9,
            "樱" to 9,
            "BB" to 34,
            "杀生院祈荒" to 34,
            // Caster 系
            "玉藻前" to 34,
            "C狐" to 34,
            "玄奘三藏" to 3,
            "达芬奇" to 42,
            "莱昂纳多达芬奇" to 42,
            "梅林" to 141,
            "花之魔术师" to 141,
            "吉尔伽美什Caster" to 177,
            "贤王闪" to 177,
            // Archer 系
            "吉尔伽美什" to 164,
            "金闪闪" to 164,
            "英雄王" to 164,
            "卫宫士郎" to 137,
            "卫宫" to 137,
            "红A" to 137,
            "EMIYA" to 137,
            "织田信长" to 56,
            "第六天魔王" to 56,
            "阿周那" to 134,
            "伊什塔尔" to 56,
            "凛伊斯塔" to 56,
            // Lancer 系
            "库丘林" to 129,
            "大狗" to 129,
            "斯卡哈" to 31,
            "师匠" to 31,
            "布伦希尔德" to 18,
            "迦尔纳" to 152,
            "恩奇都" to 152,
            // Ruler/贞德系
            "贞德" to 3,
            "圣女贞德" to 3,
            "贞德Alter" to 18,
            "黑贞" to 18,
            "贞德AlterSantaLily" to 9,
            "幼贞" to 9,
            "天草四郎时贞" to 148,
            // Assassin/Rider 等
            "开膛手杰克" to 9,
            "杰克" to 9,
            "酒吞童子" to 34,
            "源赖光" to 18,
            "奶光" to 18,
            "两仪式" to 31,
            "式姐" to 31,
            "伊斯坎达尔" to 179,
            "征服王" to 179,
            "德雷克" to 64,
            "奥兹曼迪亚斯" to 177,
            "拉二" to 177,
            "诸葛孔明" to 177,
            "埃尔梅罗二世" to 181,
            "孔明" to 177,
            // 福尔摩斯/侦探
            "夏洛克福尔摩斯" to 181,
            "福尔摩斯" to 181,
            "老福" to 181,
            // 低年龄段/萝莉
            "伊莉雅斯菲尔冯爱因兹贝伦" to 9,
            "伊莉雅" to 9,
            "克洛伊冯爱因兹贝伦" to 12,
            "小黑" to 12,
            "美游艾德费尔特" to 6,
            // 其他主要女角色
            "阿斯托尔福" to 56,
            "阿福" to 56,
            "莫德雷德" to 56,
            "小莫" to 56,
            "弗朗西斯德雷克" to 64,
            "清姬" to 34,
            "茨木童子" to 31,
            "刑部姬" to 9,
            "始皇帝" to 148,
            "政哥哥" to 148,
            "虞姬" to 18
        )

        /** zh-ll 只有 5 speaker（0~4），按"主角色分类"粗粒度映射。 */
        private val fgoRoleToZhLl: Map<String, Int> = mapOf(
            "玛修基列莱特" to 2,
            "玛修" to 2,
            "学妹" to 2,
            "阿尔托莉雅潘德拉贡" to 0,
            "阿尔托莉雅" to 0,
            "Saber" to 0,
            "远坂凛" to 3,
            "凛" to 3,
            "间桐樱" to 2,
            "樱" to 2,
            "吉尔伽美什" to 4,
            "金闪闪" to 4,
            "英雄王" to 4,
            "贞德" to 0,
            "贞德Alter" to 1,
            "黑贞" to 1,
            "梅林" to 4,
            "孔明" to 4,
            "福尔摩斯" to 4,
            "库丘林" to 4,
            "卫宫" to 4,
            "咕哒子" to 3,
            "咕哒夫" to 4
        )

        /** Kokoro 多语言包（通常 90+ speaker，按 sid 粗映射） */
        private val fgoRoleToKokoro: Map<String, Int> = mapOf(
            "玛修基列莱特" to 18,
            "玛修" to 18,
            "阿尔托莉雅潘德拉贡" to 10,
            "Saber" to 10,
            "远坂凛" to 32,
            "吉尔伽美什" to 72,
            "贞德" to 10,
            "贞德Alter" to 14,
            "咕哒子" to 5,
            "咕哒夫" to 80
        )
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private fun explicitSidFrom(raw: String): Int? {
        val candidate = raw.trim()
        return SID_PREFIXES.firstNotNullOfOrNull { prefix ->
            if (candidate.startsWith(prefix, ignoreCase = true)) {
                candidate.substring(prefix.length).toIntOrNull()
            } else null
        }
    }

    private fun clamp(id: Int, count: Int): Int {
        if (count <= 0) return 0
        val c = if (id < 0) ((id % count) + count) % count else id % count
        return c
    }

    private fun stableBucketFor(name: String, genderHint: String, max: Int): Int {
        if (max <= 0) return 0
        val femaleBias = when {
            GENDER_MALE_TOKENS.any { genderHint.contains(it, ignoreCase = true) } ||
                GENDER_MALE_TOKENS.any { name.contains(it) } -> 0
            GENDER_FEMALE_TOKENS.any { genderHint.contains(it, ignoreCase = true) } ||
                GENDER_FEMALE_TOKENS.any { name.contains(it) } -> (max * 0.3).toInt()
            else -> 0
        }
        var hash = 0x811c9dc5L
        name.forEach { c ->
            hash = hash xor c.code.toLong()
            hash = (hash * 0x01000193L) and 0xffffffffL
        }
        // 先在 [0, max/2) 里挑一个女性/中性位置；若性别为男，跳到 [max/2, max) 区间
        val half = (max.coerceAtLeast(2) / 2).coerceAtLeast(1)
        val base = (hash % half).toInt().let { if (it < 0) it + half else it }
        return base + femaleBias
    }

    private val SID_PREFIXES = listOf("sid:", "spk:", "speaker:", "speaker_id=", "id:")

    private val GENDER_MALE_TOKENS = listOf(
        "男", "male", "man", "少年", "大叔", "先生", "爷爷", "父", "兄", "青年男声", "男声",
        "saber男", "archer男", "lancer男"
    )
    private val GENDER_FEMALE_TOKENS = listOf(
        "女", "female", "woman", "少女", "萝莉", "御姐", "太太", "女士", "姐", "妹", "妈",
        "女声", "青年女声"
    )
}
