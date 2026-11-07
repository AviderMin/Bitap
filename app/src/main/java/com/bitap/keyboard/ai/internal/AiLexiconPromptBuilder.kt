package com.bitap.keyboard.ai.internal

/** One lexicon request: pinyin plus the surrounding text, already normalised. */
data class AiLexiconPrompt(
    val system: String,
    val user: String,
)

/**
 * Builds the short, bounded prompt used when the AI is asked for pinyin
 * candidate words. Pure Kotlin so the wording and bounds can be unit-tested.
 *
 * The lexicographic task is unusually hard for a 0.6B model, so the prompt is
 * deliberately tiny, keeps the previous sentence as context, and anchors the
 * output format with two examples. Sampling is greedy (temperature 0) to make
 * repeated requests stable.
 */
object AiLexiconPromptBuilder {

    const val MAX_PINYIN_CHARS: Int = 48
    const val MAX_CONTEXT_CHARS: Int = 160
    const val MAX_CANDIDATES: Int = 5

    /** Greedy sampling limits the model's freedom to drift to unrelated words. */
    const val TEMPERATURE: Double = 0.0
    const val TOP_K: Int = 1
    const val TOP_P: Double = 1.0
    const val MAX_OUTPUT_TOKENS: Int = 48

    const val SYSTEM_PROMPT: String =
        "你是 Bitap 输入法的本地中文词库。根据拼音给出最多 5 个常用中文候选词，每行一个。\n" +
            "示例：\n" +
            "拼音：nihao\n" +
            "你好\n" +
            "拼音：pingguo\n" +
            "苹果\n" +
            "要求：只输出中文候选词，不要输出拼音、数字、编号、解释、引号或 Markdown；不确定时只输出最可能的一个词。"

    fun normalizePinyin(pinyin: String): String = pinyin.trim().lowercase().take(MAX_PINYIN_CHARS)

    fun normalizeContext(context: String): String = context.trim().take(MAX_CONTEXT_CHARS)

    fun build(pinyin: String, context: String): AiLexiconPrompt {
        val normalizedPinyin = normalizePinyin(pinyin)
        val normalizedContext = normalizeContext(context).ifBlank { "（无）" }
        val user = "拼音：$normalizedPinyin\n上文：$normalizedContext\n只输出中文候选词，每行一个。"
        return AiLexiconPrompt(system = SYSTEM_PROMPT, user = user)
    }
}
