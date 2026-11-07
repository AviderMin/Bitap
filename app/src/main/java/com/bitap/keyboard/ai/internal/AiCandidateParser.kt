package com.bitap.keyboard.ai.internal

/**
 * Parses model output into a small list of clean Chinese candidate words.
 *
 * The local model sometimes echoes the pinyin, adds numbering, or emits an
 * explanatory line; all of those are rejected here so the IME never shows a
 * non-Chinese or over-long entry as an AI candidate.
 */
object AiCandidateParser {

    const val MAX_CANDIDATES: Int = 5
    const val MAX_CANDIDATE_CHARS: Int = 12

    private val NUMBERING = Regex("^[0-9０-９]+[.．、)）:：\\s]+")
    private val SEPARATORS = Regex("[\\s,，、;；]+")
    private val TRAILING_PUNCTUATION = setOf(
        '，', '。', '！', '？', '、', '：', '；', ',', '.', '!', '?', ':', ';',
    )
    private val LABEL_WORDS = setOf("候选", "候选词", "输出", "结果", "答案", "中文候选")

    fun parse(raw: String, maxCandidates: Int = MAX_CANDIDATES): List<String> {
        if (maxCandidates <= 0) return emptyList()
        val cleaned = AiResultSanitizer.cleanup(raw)
        val result = LinkedHashSet<String>()
        for (rawLine in cleaned.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // Skip label/explanation lines such as "候选：" or "输出如下：".
            if (line.contains(':') || line.contains('：')) continue
            for (rawToken in line.split(SEPARATORS)) {
                var token = rawToken.trim()
                    .trim('-', '*', '#', '`', '"', '“', '”', '「', '」', '『', '』')
                    .trim()
                token = token.replace(NUMBERING, "").trim()
                token = token.trimEnd { it in TRAILING_PUNCTUATION }.trim()
                if (token.length !in 1..MAX_CANDIDATE_CHARS) continue
                if (token in LABEL_WORDS) continue
                if (token.any { it.isDigit() }) continue
                if (token.any { it in 'a'..'z' || it in 'A'..'Z' }) continue
                if (token.none { it in '\u4e00'..'\u9fff' }) continue
                result.add(token)
                if (result.size >= maxCandidates) return result.toList()
            }
        }
        return result.toList()
    }

    /**
     * Parses output for a specific pinyin query and removes the two stable
     * failure modes of the 0.6B model: echoing the pinyin, and leaking an
     * example answer from the prompt for a different query.
     */
    fun parseForPinyin(
        raw: String,
        pinyin: String,
        maxCandidates: Int = MAX_CANDIDATES,
    ): List<String> {
        if (maxCandidates <= 0) return emptyList()
        val normalizedPinyin = AiLexiconPromptBuilder.normalizePinyin(pinyin)
        val allowedExamples = AiLexiconPromptBuilder.EXAMPLE_CANDIDATES[normalizedPinyin].orEmpty()
        val allExamples = AiLexiconPromptBuilder.EXAMPLE_CANDIDATES.values.flatten().toSet()
        val candidates = parse(raw, maxCandidates + allExamples.size)
        return candidates.asSequence()
            .filter { candidate ->
                candidate.lowercase() != normalizedPinyin &&
                    (candidate !in allExamples || candidate in allowedExamples)
            }
            .distinct()
            .take(maxCandidates)
            .toList()
    }

    fun format(candidates: List<String>): String = candidates.joinToString("\n")
}
