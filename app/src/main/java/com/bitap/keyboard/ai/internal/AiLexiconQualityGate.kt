package com.bitap.keyboard.ai.internal

/** Result of applying the local-model quality gate to one lexicon response. */
sealed interface AiLexiconOutcome {
    data class Candidates(val words: List<String>) : AiLexiconOutcome
    data class Fallback(val message: String) : AiLexiconOutcome
}

/**
 * Decides whether a model response is good enough to show as AI candidates.
 *
 * If the 0.6B model echoes the pinyin, leaks prompt examples, or otherwise
 * produces no usable Chinese word, the caller must fall back to the normal
 * local pinyin candidates instead of blocking the keyboard.
 */
object AiLexiconQualityGate {

    const val FALLBACK_MESSAGE: String = "AI 词库暂无合适候选，请直接使用本地候选"

    fun evaluate(
        raw: String,
        pinyin: String,
        dictionary: AiPinyinDictionary,
        maxCandidates: Int = AiCandidateParser.MAX_CANDIDATES,
    ): AiLexiconOutcome {
        if (dictionary.size == 0) return AiLexiconOutcome.Fallback(FALLBACK_MESSAGE)
        val words = AiCandidateParser.parseForPinyin(raw, pinyin, dictionary, maxCandidates)
        return if (words.isEmpty()) {
            AiLexiconOutcome.Fallback(FALLBACK_MESSAGE)
        } else {
            AiLexiconOutcome.Candidates(words)
        }
    }
}
