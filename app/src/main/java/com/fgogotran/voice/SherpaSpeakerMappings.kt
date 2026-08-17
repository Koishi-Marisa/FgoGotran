package com.fgogotran.voice

import com.fgogotran.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 方案 A：为 FGO 常见角色预设与「当前安装的 Sherpa 多 speaker 模型」之间的 speaker id 映射。
 *
 * ### 三层优先级（越靠前优先级越高）
 * 1. **用户手动配置的角色音色**（语音设置页固定角色 → speaker id）；
 * 2. **显式覆盖（TSV / TempVoice API 里写死）**：`VoiceProfile.style` 若满足 `sid:17` 格式，直接用；
 * 3. **角色内置映射表（本组件）**：按「模型 id × 角色名」精确匹配，例如 Saber 在 fanchen-C 用 sid=22；
 * 4. **性别感知 speaker 选择**：根据 AI 返回的 voice_type（profile.description）判断角色性别，
 *    在模型的「已知性别 speaker 集合」里稳定 hash。男性角色只会落到男声集合，女性角色只会落到女声集合。
 * 5. **稳定 hash fallback**：模型性别分布未知时，按「角色名 + 性别」先分区再 hash，
 *    保证同一个角色在同一模型下永远落到相同 speaker id，避免每句话音色跳变。
 */
@Singleton
class SherpaSpeakerMappings @Inject constructor(
    private val settingsRepository: SettingsRepository
) {

    /** 对外入口：返回在 [installed] 模型范围内可用的 speaker id（0..speakerCount-1）。 */
    fun resolveSpeakerId(
        speakerName: String?,
        profile: VoiceProfile,
        installed: InstalledSherpaModel
    ): Int {
        val count = installed.manifest.speakerCount
        val normalizedSpeaker = (speakerName ?: profile.profileId)
            .let(VoiceNameNormalizer::normalize)
            .takeIf { it.isNotBlank() }

        // 优先级 0：用户手动配置的角色音色（语音设置页固定），最高优先级
        if (normalizedSpeaker != null) {
            settingsRepository.getCharVoiceOverridesSync()[normalizedSpeaker]?.let {
                return clamp(it, count)
            }
        }

        // 优先级 1：显式 "sid:xxx" 覆盖（TSV 列或用户自定义）
        val explicit = explicitSidFrom(profile.style)
            ?: explicitSidFrom(profile.description)
            ?: profile.profileId.takeIf { it.startsWith("sid:") }?.substring(4)?.toIntOrNull()
        if (explicit != null) {
            return clamp(explicit, count)
        }

        if (normalizedSpeaker == null) {
            return clamp(installed.defaultSpeakerId, count)
        }

        // 优先级 2：针对模型类型的角色预设表
        val byModel = when (installed.manifest.modelId) {
            FANCHEN_C_MODEL_ID -> fgoRoleToFanchenC[normalizedSpeaker]
            ZH_LL_MODEL_ID -> fgoRoleToZhLl[normalizedSpeaker]
            // Kokoro v1.1（103 音色）已重排 speaker：前段是英文音色，3~57 为中文女声
            // zf_*、58~102 为中文男声 zm_*，v1.0 的角色映射表不再适用，
            // 统一交给「性别感知选择」在 zf_/zm_ 池内稳定分配。
            KOKORO_MODEL_ID_V11,
            KOKORO_MODEL_ID_V11_INT8 -> null
            KOKORO_MODEL_ID_V10,
            KOKORO_MODEL_ID_LEGACY -> fgoRoleToKokoro[normalizedSpeaker]
            // 所有单 speaker 模型统一 0（Piper 中文 + 单角色 VITS）
            PIPER_ZH_HUAYAN,
            PIPER_ZH_CHAOWEN,
            PIPER_ZH_XIAOYA,
            SINGLE_KEQING,
            SINGLE_BRONYA,
            SINGLE_THERESA -> 0
            else -> null
        }
        if (byModel != null) {
            return clamp(byModel, count)
        }

        // 优先级 3：性别感知 speaker 选择。
        // AI 返回的 voice_type（young_male / mature_female ...）存在 profile.description 里，
        // 对已知性别分布的模型（zh-ll、Kokoro）直接把男/女角色落到对应音色集合，
        // 彻底避免「男性角色被分配女声」这类性别错配。
        val gender = detectGender(
            name = normalizedSpeaker,
            description = profile.description
        )
        val genderSet = genderSidSets(
            modelId = installed.manifest.modelId,
            speakerCount = installed.manifest.speakerCount
        )
        if (genderSet != null) {
            val pool = when (gender) {
                Gender.MALE -> genderSet.maleSids
                Gender.FEMALE -> genderSet.femaleSids
                Gender.UNKNOWN -> null
            }
            if (!pool.isNullOrEmpty()) {
                return pool[stableIndex(normalizedSpeaker, pool.size)]
            }
        }

        // 优先级 4：稳定 hash fallback（先按性别分区，再在分区内 hash）
        val bucket = stableBucketFor(
            name = normalizedSpeaker,
            gender = gender,
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
        private const val KOKORO_MODEL_ID_V10 = "kokoro-multi-v1_0"
        private const val KOKORO_MODEL_ID_V11 = "kokoro-multi-v1_1"
        private const val KOKORO_MODEL_ID_V11_INT8 = "kokoro-multi-v1_1-int8"
        private const val KOKORO_MODEL_ID_LEGACY = "kokoro-82m-multi"
        // 单 speaker Piper 中文系列
        private const val PIPER_ZH_HUAYAN = "piper-zh_CN-huayan-medium"
        private const val PIPER_ZH_CHAOWEN = "piper-zh_CN-chaowen-medium"
        private const val PIPER_ZH_XIAOYA = "piper-zh_CN-xiao_ya-medium"
        // 单角色 VITS 系列
        private const val SINGLE_KEQING = "vits-zh-single-keqing"
        private const val SINGLE_BRONYA = "vits-zh-single-bronya"
        private const val SINGLE_THERESA = "vits-zh-single-theresa"

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

        /**
         * zh-ll 只有 5 speaker（0~4）。经实测基频验证的性别分布：
         *   0 = suyingxue (苏樱雪, F0≈286Hz, 女声)
         *   1 = gunian     (姑念,   F0≈109Hz, 男声)
         *   2 = fushiyu    (傅诗语, F0≈239Hz, 女声)
         *   3 = bingjiao   (病娇,   F0≈182Hz, 女声)
         *   4 = bazong     (霸总,   F0≈120Hz, 男声)
         */
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
            "贞德Alter" to 3,
            "黑贞" to 3,
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
            "玛修基列莱特" to 2,
            "玛修" to 2,
            "阿尔托莉雅潘德拉贡" to 10,
            "Saber" to 10,
            "远坂凛" to 32,
            "吉尔伽美什" to 72,
            "贞德" to 10,
            "贞德Alter" to 4,
            "咕哒子" to 5,
            "咕哒夫" to 80
        )
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private enum class Gender { MALE, FEMALE, UNKNOWN }

    private data class GenderSidSet(val femaleSids: List<Int>, val maleSids: List<Int>)

    /**
     * 已知性别分布的模型的 speaker 集合。
     * 返回 null 表示该模型性别分布未知（如 fanchen-C 187 音色无官方性别标注），
     * 此时走稳定 hash fallback。
     */
    private fun genderSidSets(modelId: String, speakerCount: Int): GenderSidSet? = when (modelId) {
        // zh-ll 5 speaker 性别经基频实测：0/2/3 女声，1/4 男声
        ZH_LL_MODEL_ID -> GenderSidSet(femaleSids = listOf(0, 2, 3), maleSids = listOf(1, 4))
        // Kokoro v1.0/legacy：53 个 speaker，按官方命名段划分（af_/am_/bf_/bm_...）
        KOKORO_MODEL_ID_V10,
        KOKORO_MODEL_ID_LEGACY -> kokoroV10GenderSidSet(speakerCount)
        // Kokoro v1.1：103 个 speaker，重新排序后 0~2 为英文女声（af_maple/af_sol/bf_vale），
        // 3~57 为中文女声 zf_*，58~102 为中文男声 zm_*（经实际加载 voices.bin 确认）。
        KOKORO_MODEL_ID_V11,
        KOKORO_MODEL_ID_V11_INT8 -> kokoroV11GenderSidSet(speakerCount)
        else -> null
    }

    /**
     * Kokoro v1.0（53 speaker）官方 voices 命名规则：`<2字母><性别>_<名字>`，
     * f = female（af_/bf_/ef_/ff_/hf_/if_/jf_/pf_/zf_），m = male（am_/bm_/em_/hm_/im_/jm_/pm_/zm_）。
     * 顺序固定（0..52），此处只认已确认的 53 个段，超出范围不计入集合。
     */
    private fun kokoroV10GenderSidSet(speakerCount: Int): GenderSidSet {
        data class Seg(val start: Int, val end: Int, val female: Boolean)
        val segments = listOf(
            Seg(0, 10, true), Seg(11, 19, false), Seg(20, 23, true), Seg(24, 27, false),
            Seg(28, 28, true), Seg(29, 29, false), Seg(30, 30, true), Seg(31, 32, true),
            Seg(33, 34, false), Seg(35, 35, true), Seg(36, 36, false), Seg(37, 40, true),
            Seg(41, 41, false), Seg(42, 42, true), Seg(43, 44, false), Seg(45, 48, true),
            Seg(49, 52, false)
        )
        val female = mutableListOf<Int>()
        val male = mutableListOf<Int>()
        for (seg in segments) {
            if (seg.start >= speakerCount) break
            val last = minOf(seg.end, speakerCount - 1)
            for (id in seg.start..last) {
                if (seg.female) female.add(id) else male.add(id)
            }
        }
        return GenderSidSet(female, male)
    }

    /**
     * Kokoro v1.1（103 speaker，voices.bin 实测）：
     *   sid 0..2   = af_maple / af_sol / bf_vale（英文女声，中文角色不使用）
     *   sid 3..57  = zf_001..zf_099（中文女声）
     *   sid 58..102 = zm_009..zm_100（中文男声）
     */
    private fun kokoroV11GenderSidSet(speakerCount: Int): GenderSidSet {
        val female = (3..57).takeWhile { it < speakerCount }
        val male = (58..102).takeWhile { it < speakerCount }
        return GenderSidSet(femaleSids = female, maleSids = male)
    }

    /**
     * 从「角色名 + AI 的 voice_type 描述」判断性别。
     * 注意顺序：必须先把 female 判定放在 male 之前——"young_female" 这类词
     * 也包含子串 "male"（"fe**male**"），若先匹配 male 会把女声误判成男声。
     */
    private fun detectGender(name: String, description: String): Gender {
        val hint = description.ifBlank { name }
        if (GENDER_FEMALE_TOKENS.any { hint.contains(it, ignoreCase = true) }) return Gender.FEMALE
        if (GENDER_MALE_TOKENS.any { hint.contains(it, ignoreCase = true) }) return Gender.MALE
        return Gender.UNKNOWN
    }

    /** FNV-1a 风格稳定 hash，保证同一名字在同一池子里永远落在同一个槽位 */
    private fun stableIndex(name: String, size: Int): Int {
        if (size <= 0) return 0
        var hash = 0x811c9dc5L
        name.forEach { c ->
            hash = hash xor c.code.toLong()
            hash = (hash * 0x01000193L) and 0xffffffffL
        }
        return (hash % size).toInt()
    }

    /**
     * 性别未知模型的稳定 hash fallback：
     * 男性角色落在 [max/2, max) 区间，女性/中性角色落在 [0, max/2) 区间，
     * 避免男性角色系统性落到前一半（很多模型前一半是女声，如 zh-ll 的 sid=0）。
     */
    private fun stableBucketFor(name: String, gender: Gender, max: Int): Int {
        if (max <= 0) return 0
        val male = gender == Gender.MALE
        val half = (max / 2).coerceAtLeast(1)
        val slot = stableIndex(name, if (male) (max - half).coerceAtLeast(1) else half)
        return if (male) half + slot else slot
    }

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

    private val SID_PREFIXES = listOf("sid:", "spk:", "speaker:", "speaker_id=", "id:")

    private val GENDER_MALE_TOKENS = listOf(
        "male", "男", "man", "少年", "大叔", "先生", "爷爷", "父", "兄", "青年男声", "男声",
        "saber男", "archer男", "lancer男"
    )
    private val GENDER_FEMALE_TOKENS = listOf(
        "female", "女", "woman", "少女", "萝莉", "御姐", "太太", "女士", "姐", "妹", "妈",
        "女声", "青年女声"
    )
}
