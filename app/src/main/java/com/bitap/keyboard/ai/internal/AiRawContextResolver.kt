package com.bitap.keyboard.ai.internal

/**
 * Replacement plan for an AI lexicon request that was started from raw editor
 * text rather than an active composing region.
 *
 * [original] is the exact text immediately before the cursor (or the selected
 * text) that the AI candidate must replace. The controller re-reads the
 * editor and compares against it before replacing anything, so moving the
 * cursor or changing the text invalidates the plan.
 */
data class AiRawReplacement(
    val query: String,
    val context: String,
    val original: String,
    val selected: Boolean,
)

/**
 * Pure rules for deriving a pinyin query from raw editor text.
 *
 * Only a trailing pinyin-like token (letters/apostrophes) or a fully pinyin-like
 * selection is accepted. Chinese selections stay with the rewrite panel and are
 * not treated as an AI lexicon query.
 */
object AiRawContextResolver {

    const val MAX_QUERY_CHARS: Int = 48
    const val MAX_CONTEXT_CHARS: Int = 160

    private val TRAILING_PINYIN = Regex("[A-Za-z']+$")

    fun resolve(selected: String?, beforeCursor: String?): AiRawReplacement? {
        // T04: keep the exact selected text (including outer whitespace) as the
        // replacement range; only the query is trimmed/normalized. The
        // controller compares this exact string before touching the editor.
        if (selected != null && selected.isNotEmpty()) {
            if (selected.isBlank()) return null
            val queryText = selected.trim()
            if (!isPinyinLike(queryText)) return null
            val query = AiPinyinDictionary.normalizePinyin(queryText)
            if (query.length !in 1..MAX_QUERY_CHARS) return null
            return AiRawReplacement(
                query = query,
                context = "",
                original = selected,
                selected = true,
            )
        }

        val before = beforeCursor ?: return null
        val match = TRAILING_PINYIN.find(before) ?: return null
        val original = match.value
        val query = AiPinyinDictionary.normalizePinyin(original)
        if (query.length !in 1..MAX_QUERY_CHARS) return null
        val context = before.substring(0, match.range.first).take(MAX_CONTEXT_CHARS)
        return AiRawReplacement(
            query = query,
            context = context,
            original = original,
            selected = false,
        )
    }

    private fun isPinyinLike(text: String): Boolean =
        text.any { char -> char in 'a'..'z' || char in 'A'..'Z' } &&
            text.all { char -> char in 'a'..'z' || char in 'A'..'Z' || char == '\'' || char.isWhitespace() }
}
