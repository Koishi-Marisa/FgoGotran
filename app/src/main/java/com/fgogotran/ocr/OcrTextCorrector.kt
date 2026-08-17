package com.fgogotran.ocr

/**
 * Conservative cleanup for high-confidence OCR glyph confusions in dialogue text.
 *
 * Rules are generated from canonical Japanese words plus visually confusable
 * glyphs, then applied only in normal word contexts. This avoids broad character
 * replacement such as changing every 善 into 普.
 */
object OcrTextCorrector {
    private data class CorrectionRule(
        val ocrVariant: String,
        val canonical: String
    )

    private val likelyDialogueWords = listOf(
        "普段",
        "転移孔",
        "彷徨海",
        "頷く",
        "頷いた",
        "頷いて",
        "頷き",
        "頷ける",
        "了解",
        "了解した",
        "了解して",
        "了解しました",
        "了解する",
        "子供",
        "量子",
        "量子化",
        "量子的",
        "量子コンピューター",
        "量子コンピュータ",
        "嗜好",
        "嗜好品",
        "嗜好的",
        "嗜好性",
        "魔術",
        "魔術師",
        "魔術回路",
        "魔術協会",
        "魔神柱",
        "投影魔術",
        // 国服中文常用词（与 confusableGlyphs 的中文形近字配合生成变体）
        "玛修",
        "灵基",
        "御主",
        "迦勒底",
        "魔术师",
        "魔术回路",
        "令咒",
        "从者",
        "特异点",
        "人理",
        "英灵",
        "影从者",
        "圣杯",
        "魔力"
    )

    private val confusableGlyphs = mapOf(
        '了' to setOf('3'),
        '子' to setOf('3'),
        '普' to setOf('善'),
        '嗜' to setOf('晴'),
        '孔' to setOf('乳', '乱'),
        '彷' to setOf('紡'),
        '徨' to setOf('律', '僧', '管'),
        '頷' to setOf('領'),
        '魔' to setOf('廃', '废'),
        '術' to setOf('术'),
        // 国服中文形近字（OCR 高频混淆）
        '玛' to setOf('码', '妈'),
        '修' to setOf('休'),
        '灵' to setOf('录'),
        '基' to setOf('其'),
        '御' to setOf('卸'),
        '主' to setOf('王'),
        '迦' to setOf('加', '珈'),
        '勒' to setOf('肋'),
        '底' to setOf('低', '抵'),
        '令' to setOf('今'),
        '咒' to setOf('咎'),
        '从' to setOf('丛'),
        '特' to setOf('持'),
        '异' to setOf('导'),
        '人' to setOf('入'),
        '理' to setOf('里')
    )

    private val rules: List<CorrectionRule> = likelyDialogueWords
        .flatMap { canonical ->
            variantsFor(canonical)
                .filter { it != canonical }
                .map { variant -> CorrectionRule(variant, canonical) }
        }
        .sortedByDescending { it.ocrVariant.length }

    fun correct(text: String): String {
        if (text.isBlank() || rules.isEmpty()) return text

        var corrected = text
        for (rule in rules) {
            corrected = applyRule(corrected, rule)
        }
        return corrected
    }

    private fun variantsFor(canonical: String): Set<String> {
        var variants = setOf("")
        for (char in canonical) {
            val choices = listOf(char) + confusableGlyphs[char].orEmpty()
            variants = variants.flatMap { prefix ->
                choices.map { choice -> prefix + choice }
            }.toSet()
        }
        return variants
    }

    private fun applyRule(text: String, rule: CorrectionRule): String {
        val result = StringBuilder()
        var searchStart = 0
        var changed = false

        while (searchStart < text.length) {
            val matchStart = text.indexOf(rule.ocrVariant, searchStart)
            if (matchStart < 0) break

            val matchEndExclusive = matchStart + rule.ocrVariant.length
            result.append(text, searchStart, matchStart)
            if (hasDialogueWordContext(text, matchStart, matchEndExclusive)) {
                result.append(rule.canonical)
                changed = true
            } else {
                result.append(text, matchStart, matchEndExclusive)
            }
            searchStart = matchEndExclusive
        }

        if (!changed) return text
        result.append(text, searchStart, text.length)
        return result.toString()
    }

    private fun hasDialogueWordContext(text: String, start: Int, endExclusive: Int): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(endExclusive)
        return isSafeBeforeWord(before) && isSafeAfterWord(after)
    }

    private fun isSafeBeforeWord(char: Char?): Boolean {
        return char == null ||
                char.isWhitespace() ||
                char in setOf(
                    '\n', '　', '、', '。', '，', '．', '.', ',', '!',
                    '！', '?', '？', '…', '「', '『', '（', '(', '[',
                    '【', '》', 'は', 'が', 'を', 'に', 'で', 'へ', 'と',
                    'も', 'の', 'や', 'か'
                )
    }

    private fun isSafeAfterWord(char: Char?): Boolean {
        return char == null ||
                char.isWhitespace() ||
                char in setOf(
                    '\n', '　', '、', '。', '，', '．', '.', ',', '!',
                    '！', '?', '？', '…', '」', '』', '）', ')', ']',
                    '】', '《', 'は', 'が', 'を', 'に', 'で', 'へ', 'と',
                    'も', 'の', 'や', 'か', 'よ', 'ね', 'な', 'だ'
                )
    }
}
