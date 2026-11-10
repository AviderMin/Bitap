package com.bitap.keyboard.core

/**
 * Small editable [TextSession] fake used by controller-level JVM tests.
 *
 * It models a real editor reasonably closely: setComposingText keeps a composing
 * region, commitText replaces that region (or the selection), and the cursor
 * position is tracked so tests can assert no characters are lost or duplicated.
 */
class TestEditorTextSession(
    initialText: String = "",
    initialSelectionStart: Int = initialText.length,
    initialSelectionEnd: Int = initialText.length,
    initialClipboard: String = "",
) : TextSession {

    var text: String = initialText
        private set

    /** In-memory clipboard used by [copySelection], [cutSelection] and [paste]. */
    var clipboardText: String = initialClipboard
        private set

    var selectionStart: Int = initialSelectionStart.coerceIn(0, initialText.length)
        private set

    var selectionEnd: Int = initialSelectionEnd.coerceIn(0, initialText.length)
        private set

    val log: MutableList<String> = mutableListOf()

    var enterCount: Int = 0
        private set

    private var composingStart: Int = -1
    private var composingEnd: Int = -1

    val cursor: Int
        get() = selectionEnd

    override val isActive: Boolean = true

    fun select(start: Int, end: Int) {
        selectionStart = start.coerceIn(0, text.length)
        selectionEnd = end.coerceIn(0, text.length)
    }

    fun replaceText(newText: String) {
        text = newText
        composingStart = -1
        composingEnd = -1
        selectionStart = newText.length
        selectionEnd = newText.length
    }

    override fun commitText(text: String) {
        log += "commit:" + text
        val start = if (composingStart >= 0) composingStart else selectionStart
        val end = if (composingEnd >= 0) composingEnd else selectionEnd
        replaceRange(start = minOf(start, end), end = maxOf(start, end), value = text)
        composingStart = -1
        composingEnd = -1
        selectionStart = minOf(start, end) + text.length
        selectionEnd = selectionStart
    }

    override fun setComposingText(text: String) {
        log += "setComposing:" + text
        val start = if (composingStart >= 0) composingStart else minOf(selectionStart, selectionEnd)
        val end = if (composingEnd >= 0) composingEnd else maxOf(selectionStart, selectionEnd)
        replaceRange(start = minOf(start, end), end = maxOf(start, end), value = text)
        composingStart = minOf(start, end)
        composingEnd = composingStart + text.length
        selectionStart = composingEnd
        selectionEnd = composingEnd
    }

    override fun finishComposingText() {
        log += "finishComposing"
        composingStart = -1
        composingEnd = -1
    }

    override fun moveCursorBy(delta: Int): Boolean {
        if (delta == 0) return false
        val target = (selectionEnd + delta).coerceIn(0, text.length)
        selectionStart = target
        selectionEnd = target
        log += "moveCursor:$delta"
        return true
    }

    override fun selectAll(): Boolean {
        if (text.isEmpty()) return false
        log += "selectAll"
        selectionStart = 0
        selectionEnd = text.length
        return true
    }

    override fun copySelection(): Boolean {
        val selected = getSelectedText() ?: return false
        clipboardText = selected
        log += "copy:$selected"
        return true
    }

    override fun cutSelection(): Boolean {
        val selected = getSelectedText() ?: return false
        clipboardText = selected
        deleteSelection()
        log += "cut:$selected"
        return true
    }

    override fun paste(): Boolean {
        if (clipboardText.isEmpty()) return false
        val value = clipboardText
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        replaceRange(start, end, value)
        composingStart = -1
        composingEnd = -1
        selectionStart = start + value.length
        selectionEnd = selectionStart
        log += "paste:$value"
        return true
    }

    override fun moveCursorByWord(delta: Int): Boolean {
        if (delta == 0) return false
        val target = if (selectionStart != selectionEnd) {
            // Collapse a real selection in the requested direction first.
            if (delta > 0) maxOf(selectionStart, selectionEnd) else minOf(selectionStart, selectionEnd)
        } else {
            if (delta > 0) WordBoundary.nextWordEnd(text, selectionEnd) else WordBoundary.previousWordStart(text, selectionEnd)
        }
        selectionStart = target
        selectionEnd = target
        log += "moveCursorByWord:$delta"
        return true
    }

    override fun deleteBackwardWord(): Boolean {
        if (selectionStart != selectionEnd) {
            deleteSelection()
            log += "deleteWord:selection"
            return true
        }
        if (selectionStart <= 0) return false
        val count = WordBoundary.backwardDeletionLength(text, selectionStart)
        if (count <= 0) return false
        replaceRange(selectionStart - count, selectionStart, "")
        composingStart = -1
        composingEnd = -1
        selectionStart -= count
        selectionEnd = selectionStart
        log += "deleteWord:$count"
        return true
    }

    override fun deleteBackward() {
        log += "deleteBackward"
        if (selectionStart != selectionEnd) {
            val start = minOf(selectionStart, selectionEnd)
            val end = maxOf(selectionStart, selectionEnd)
            replaceRange(start, end, "")
            composingStart = -1
            composingEnd = -1
            selectionStart = start
            selectionEnd = start
            return
        }
        if (selectionStart > 0) {
            replaceRange(selectionStart - 1, selectionStart, "")
            if (composingStart >= 0) {
                composingEnd -= 1
                if (composingEnd <= composingStart) {
                    composingStart = -1
                    composingEnd = -1
                }
            }
            selectionStart -= 1
            selectionEnd = selectionStart
        }
    }

    override fun sendEnter() {
        log += "sendEnter"
        enterCount += 1
    }

    override fun getSelectedText(): String? =
        if (selectionStart != selectionEnd) {
            text.substring(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd))
        } else {
            null
        }

    override fun getTextBeforeCursor(maxChars: Int): String? {
        if (maxChars <= 0) return null
        val end = selectionStart.coerceIn(0, text.length)
        return text.substring((end - maxChars).coerceAtLeast(0), end)
    }

    override fun getTextAfterCursor(maxChars: Int): String? {
        if (maxChars <= 0) return null
        val start = selectionEnd.coerceIn(0, text.length)
        return text.substring(start, (start + maxChars).coerceAtMost(text.length))
    }

    override fun getSelection(): EditorSelection = EditorSelection(selectionStart, selectionEnd)

    override fun updateSelection(start: Int, end: Int) {
        if (start < 0 || end < 0) return
        select(start, end)
    }

    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot = EditorTextSnapshot(
        selected = getSelectedText(),
        beforeCursor = getTextBeforeCursor(maxChars),
    )

    override fun readAiSource(maxChars: Int): String {
        if (maxChars <= 0) return ""
        val selected = getSelectedText()
        if (!selected.isNullOrEmpty()) return selected.take(maxChars)
        val before = getTextBeforeCursor(maxChars) ?: return ""
        return before.trimEnd().take(maxChars)
    }

    private fun deleteSelection() {
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        replaceRange(start, end, "")
        composingStart = -1
        composingEnd = -1
        selectionStart = start
        selectionEnd = start
    }

    private fun replaceRange(start: Int, end: Int, value: String) {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        text = text.substring(0, safeStart) + value + text.substring(safeEnd)
    }
}
