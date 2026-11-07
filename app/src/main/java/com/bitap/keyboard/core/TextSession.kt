package com.bitap.keyboard.core

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Bounded Unicode grapheme-cluster boundary handling for editor deletion.
 *
 * Android's [InputConnection.deleteSurroundingText] counts UTF-16 code units,
 * not user-visible characters. This helper keeps one backspace from splitting
 * surrogate pairs, ZWJ emoji, skin-tone modifiers, regional-indicator flags,
 * variation selectors and combining marks.
 */
internal object UnicodeGrapheme {
    private const val ZWJ = 0x200D
    private const val VARIATION_SELECTOR_START = 0xFE00
    private const val VARIATION_SELECTOR_END = 0xFE0F
    private const val VARIATION_SELECTOR_SUPPLEMENT_START = 0xE0100
    private const val VARIATION_SELECTOR_SUPPLEMENT_END = 0xE01EF
    private const val EMOJI_MODIFIER_START = 0x1F3FB
    private const val EMOJI_MODIFIER_END = 0x1F3FF
    private const val REGIONAL_INDICATOR_START = 0x1F1E6
    private const val REGIONAL_INDICATOR_END = 0x1F1FF
    private const val TAG_START = 0xE0020
    private const val TAG_END = 0xE007E
    private const val TAG_CANCEL = 0xE007F

    /** UTF-16 length of the last user-visible character in [text]. */
    fun lastGraphemeLength(text: String): Int {
        if (text.isEmpty()) return 0
        val codePoints = toCodePoints(text)
        var start = codePoints.size - 1
        while (start > 0 && joins(codePoints, start - 1, start)) {
            start--
        }
        return text.length - text.offsetByCodePoints(0, start)
    }

    /** Returns [text] with its last user-visible character removed. */
    fun dropLastGrapheme(text: String): String {
        val length = lastGraphemeLength(text)
        return if (length <= 0) text else text.dropLast(length)
    }

    private fun toCodePoints(text: String): IntArray {
        val out = IntArray(text.length)
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            out[count++] = codePoint
            index += Character.charCount(codePoint)
        }
        return out.copyOf(count)
    }

    private fun joins(codePoints: IntArray, previous: Int, current: Int): Boolean {
        val prev = codePoints[previous]
        val curr = codePoints[current]
        if (prev == ZWJ || curr == ZWJ) return true
        if (isExtend(curr)) return true
        if (isRegionalIndicator(curr) && isRegionalIndicator(prev)) {
            var runLength = 0
            var index = current
            while (index >= 0 && isRegionalIndicator(codePoints[index])) {
                runLength++
                index--
            }
            return runLength % 2 == 0
        }
        return false
    }

    private fun isExtend(codePoint: Int): Boolean {
        if (codePoint in VARIATION_SELECTOR_START..VARIATION_SELECTOR_END) return true
        if (codePoint in VARIATION_SELECTOR_SUPPLEMENT_START..VARIATION_SELECTOR_SUPPLEMENT_END) return true
        if (codePoint in EMOJI_MODIFIER_START..EMOJI_MODIFIER_END) return true
        if (codePoint in TAG_START..TAG_END || codePoint == TAG_CANCEL) return true
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean =
        codePoint in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END
}

/** Snapshot of the editor content around the cursor. */
data class EditorTextSnapshot(
    val selected: String?,
    val beforeCursor: String?,
)

/**
 * Thin, testable facade over the editor connection. The controller only ever
 * talks to this interface, never to InputConnection directly.
 */
interface TextSession {
    val isActive: Boolean

    fun commitText(text: String)

    fun setComposingText(text: String)

    fun finishComposingText()

    fun deleteBackward()

    fun sendEnter()

    /** Selected text, or null when the cursor has no selection. */
    fun getSelectedText(): String?

    /** Up to [maxChars] characters immediately before the cursor. */
    fun getTextBeforeCursor(maxChars: Int): String?

    /** Selected text and text before the cursor in one snapshot. */
    fun getSurroundingText(maxChars: Int = 512): EditorTextSnapshot

    /**
     * AI source rule: the selection when present, otherwise the most recent
     * sentence before the cursor, capped at [maxChars] characters.
     */
    fun readAiSource(maxChars: Int = 512): String
}

/** [TextSession] backed by the live [InputConnection] of an InputMethodService. */
class AndroidTextSession(private val service: InputMethodService) : TextSession {

    private val connection: InputConnection?
        get() = service.currentInputConnection

    override val isActive: Boolean
        get() = connection != null

    override fun commitText(text: String) {
        val input = connection ?: return
        input.commitText(text, 1)
    }

    override fun setComposingText(text: String) {
        val input = connection ?: return
        if (text.isEmpty()) {
            input.finishComposingText()
        } else {
            input.setComposingText(text, 1)
        }
    }

    override fun finishComposingText() {
        connection?.finishComposingText()
    }

    override fun deleteBackward() {
        val input = connection ?: return
        val selection = input.getSelectedText(0)
        if (!selection.isNullOrEmpty()) {
            input.commitText("", 1)
            return
        }
        // Read a bounded suffix only; grapheme segmentation below never scans
        // the whole editor. 64 UTF-16 units cover even long ZWJ/tag sequences.
        val before = input.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val deleteCount = UnicodeGrapheme.lastGraphemeLength(before).coerceAtLeast(if (before.isEmpty()) 1 else 0)
        input.deleteSurroundingText(deleteCount, 0)
    }

    override fun sendEnter() {
        val input = connection
        if (input == null) {
            service.sendDefaultEditorAction(false)
            return
        }
        val action = service.currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        val hasEditorAction = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (hasEditorAction && service.sendDefaultEditorAction(false)) return
        input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    override fun getSelectedText(): String? =
        connection?.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }

    override fun getTextBeforeCursor(maxChars: Int): String? {
        if (maxChars <= 0) return null
        return connection?.getTextBeforeCursor(maxChars, 0)?.toString()
    }

    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot {
        val input = connection ?: return EditorTextSnapshot(null, null)
        return EditorTextSnapshot(
            selected = if (maxChars > 0) input.getSelectedText(0)?.toString() else null,
            beforeCursor = if (maxChars > 0) input.getTextBeforeCursor(maxChars, 0)?.toString() else null,
        )
    }

    override fun readAiSource(maxChars: Int): String {
        if (maxChars <= 0) return ""
        val surrounding = getSurroundingText(maxChars)
        val selected = surrounding.selected?.takeIf { it.isNotBlank() }
        if (!selected.isNullOrEmpty()) return selected.take(maxChars)
        val before = surrounding.beforeCursor ?: return ""
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return ""
        fun isTerminator(char: Char): Boolean =
            char == '。' || char == '！' || char == '？' || char == '!' || char == '?' || char == '\n'
        // Skip trailing sentence terminators so "你好。|光标" still yields "你好".
        var end = trimmed.length
        while (end > 0 && (isTerminator(trimmed[end - 1]) || trimmed[end - 1].isWhitespace())) end--
        if (end == 0) return ""
        val candidate = trimmed.substring(0, end)
        val sentenceStart = candidate.indexOfLast { isTerminator(it) }
        val sentence = if (sentenceStart >= 0) candidate.substring(sentenceStart + 1) else candidate
        return sentence.trim().take(maxChars)
    }
}
