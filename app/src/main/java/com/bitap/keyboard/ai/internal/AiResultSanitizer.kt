package com.bitap.keyboard.ai.internal

/**
 * Pure-JVM cleanup for text produced by the local model.
 *
 * The sanitizer never logs or persists user text; it only normalises a model
 * response before it is shown in the AI preview panel.
 */
object AiResultSanitizer {

    const val MAX_OUTPUT_CHARS: Int = 512
    const val MAX_OUTPUT_MULTIPLIER: Int = 3

    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"
    private const val RESPONSE_OPEN = "<response>"
    private const val RESPONSE_CLOSE = "</response>"
    private const val END_OF_THINKING_FULLWIDTH = "<｜end▁of▁thinking｜>"
    private const val END_OF_THINKING_ASCII = "<|end▁of▁thinking|>"

    enum class FailureReason {
        EMPTY,
        TOO_LONG,
    }

    sealed interface Result {
        data class Success(val text: String) : Result
        data class Failure(val reason: FailureReason) : Result
    }

    /** Maximum accepted output length for a given source text: min(3x source, 512). */
    fun maxAllowedLength(sourceText: String): Int {
        val scaled = sourceText.length.coerceAtLeast(1) * MAX_OUTPUT_MULTIPLIER
        return scaled.coerceAtMost(MAX_OUTPUT_CHARS)
    }

    fun sanitize(raw: String, sourceText: String): Result {
        val cleaned = cleanup(raw)
        if (cleaned.isEmpty()) return Result.Failure(FailureReason.EMPTY)
        if (cleaned.length > maxAllowedLength(sourceText)) return Result.Failure(FailureReason.TOO_LONG)
        return Result.Success(cleaned)
    }

    /** Applies every cleanup rule without a length check. Useful for streaming filters and tests. */
    fun cleanup(raw: String): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        text = stripThinkBlocks(text)
        text = stripMarkdown(text)
        text = text.trim()
        text = stripLeadingPrefixes(text)
        text = stripWrappingQuotes(text)
        text = collapseBlankLines(text)
        return text.trim()
    }

    /** Removes Qwen3 thinking blocks in all common marker spellings. */
    fun stripThinkBlocks(raw: String): String {
        var text = raw
        text = removeDelimited(
            text,
            THINK_OPEN,
            listOf(THINK_CLOSE, END_OF_THINKING_FULLWIDTH, END_OF_THINKING_ASCII),
        )
        text = removeDelimited(text, RESPONSE_OPEN, listOf(RESPONSE_CLOSE))

        // A stray closing marker at the very beginning means a hidden preamble
        // was produced; keep only the public answer after the marker.
        for (marker in listOf(END_OF_THINKING_FULLWIDTH, END_OF_THINKING_ASCII, RESPONSE_CLOSE)) {
            val index = text.lastIndexOf(marker, ignoreCase = true)
            if (index >= 0 && index < text.length / 2) {
                text = text.substring(index + marker.length)
            }
        }
        // Unclosed opening marker: drop everything from it onwards.
        val open = text.indexOf(THINK_OPEN, ignoreCase = true)
        if (open >= 0) text = text.substring(0, open)
        val responseOpen = text.indexOf(RESPONSE_OPEN, ignoreCase = true)
        if (responseOpen >= 0) text = text.substring(0, responseOpen)

        text = text.replace(THINK_OPEN, "", ignoreCase = true)
        text = text.replace(THINK_CLOSE, "", ignoreCase = true)
        text = text.replace(RESPONSE_OPEN, "", ignoreCase = true)
        text = text.replace(RESPONSE_CLOSE, "", ignoreCase = true)
        text = text.replace(END_OF_THINKING_FULLWIDTH, "", ignoreCase = true)
        text = text.replace(END_OF_THINKING_ASCII, "", ignoreCase = true)
        return text
    }

    private fun removeDelimited(text: String, open: String, closes: List<String>): String {
        var result = text
        while (true) {
            val start = result.indexOf(open, ignoreCase = true)
            if (start < 0) return result
            var closestEnd = -1
            var closestLength = 0
            for (close in closes) {
                val index = result.indexOf(close, start + open.length, ignoreCase = true)
                if (index >= 0 && (closestEnd < 0 || index < closestEnd)) {
                    closestEnd = index
                    closestLength = close.length
                }
            }
            result = if (closestEnd < 0) {
                result.substring(0, start)
            } else {
                result.substring(0, start) + result.substring(closestEnd + closestLength)
            }
        }
    }

    private fun stripMarkdown(input: String): String {
        var text = input
        // Code fences: remove the fence lines themselves, keep their content.
        text = text.replace(Regex("(?m)^[ \t]*```[A-Za-z0-9_+.-]*[ \t]*$"), "")
        text = text.replace("```", "")
        text = text.replace(Regex("`([^`\n]+)`"), "$1")
        text = text.replace(Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL), "$1")
        text = text.replace(Regex("__(.+?)__", RegexOption.DOT_MATCHES_ALL), "$1")
        text = text.replace(Regex("~~(.+?)~~", RegexOption.DOT_MATCHES_ALL), "$1")
        // Headings, block quotes and list bullets.
        text = text.replace(Regex("(?m)^[ \t]{0,3}#{1,6}[ \t]+"), "")
        text = text.replace(Regex("(?m)^[ \t]{0,3}>[ \t]?"), "")
        text = text.replace(Regex("(?m)^[ \t]{0,3}[-*+][ \t]+"), "")
        text = text.replace(Regex("(?m)^[ \t]*([-*_])(?:[ \t]*\\1){2,}[ \t]*$"), "")
        return text
    }

    private val PREFIX_PATTERN = Regex(
        listOf(
            """以下是[^\n：:]{0,30}[：:]\s*""",
            """(?:改写|润色|精简|正式化|翻译|译)(?:后|结果|如下)?[^\n：:]{0,10}[：:]\s*""",
            """(?:译文|翻译|结果|输出|答案)[：:]\s*""",
            """(?i:here(?:'s| is)[^:：\n]{0,30}[:：]\s*)""",
        ).joinToString("|", prefix = "^(?:", postfix = ")"),
    )

    private fun stripLeadingPrefixes(input: String): String {
        var text = input.trim()
        var guard = 0
        while (guard < 3) {
            val next = text.replaceFirst(PREFIX_PATTERN, "").trimStart()
            if (next == text) break
            text = next
            guard++
        }
        return text
    }

    private fun stripWrappingQuotes(input: String): String {
        var text = input.trim()
        val pairs = listOf(
            '"' to '"',
            '\'' to '\'',
            '“' to '”',
            '‘' to '’',
            '「' to '」',
            '『' to '』',
            '《' to '》',
        )
        var changed = true
        while (changed && text.length >= 2) {
            changed = false
            for ((open, close) in pairs) {
                if (text.length >= 2 && text.first() == open && text.last() == close) {
                    text = text.substring(1, text.length - 1).trim()
                    changed = true
                }
            }
        }
        return text
    }

    private fun collapseBlankLines(input: String): String {
        var text = input
        text = text.replace(Regex("\n[ \t]*\n(?:[ \t]*\n)+"), "\n\n")
        return text
    }
}
