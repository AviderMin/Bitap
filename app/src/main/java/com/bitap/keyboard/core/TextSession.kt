package com.bitap.keyboard.core

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

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
        val before = input.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val deleteCount = if (before.length >= 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
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
