package com.bitap.keyboard.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
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

/**
 * Pure word-boundary rules for word-level cursor movement and deletion.
 *
 * CJK characters are words of their own, a run of letters/digits forms one
 * word, and whitespace is skipped while looking for the neighbouring word.
 * Offsets are UTF-16 code units so they can be passed to InputConnection
 * without conversion.
 */
internal object WordBoundary {
    /** Start offset of the word before [from], skipping trailing whitespace. */
    fun previousWordStart(text: String, from: Int): Int {
        var index = from.coerceIn(0, text.length)
        while (index > 0) {
            val codePoint = text.codePointBefore(index)
            if (!Character.isWhitespace(codePoint)) break
            index -= Character.charCount(codePoint)
        }
        if (index <= 0) return 0
        val codePoint = text.codePointBefore(index)
        if (isCjk(codePoint)) return index - Character.charCount(codePoint)
        if (isWordChar(codePoint)) {
            while (index > 0) {
                val previous = text.codePointBefore(index)
                if (!isWordChar(previous)) break
                index -= Character.charCount(previous)
            }
            return index
        }
        return index - Character.charCount(codePoint)
    }

    /** End offset of the word after [from], skipping leading whitespace. */
    fun nextWordEnd(text: String, from: Int): Int {
        var index = from.coerceIn(0, text.length)
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!Character.isWhitespace(codePoint)) break
            index += Character.charCount(codePoint)
        }
        if (index >= text.length) return text.length
        val codePoint = text.codePointAt(index)
        if (isCjk(codePoint)) return index + Character.charCount(codePoint)
        if (isWordChar(codePoint)) {
            while (index < text.length) {
                val current = text.codePointAt(index)
                if (!isWordChar(current)) break
                index += Character.charCount(current)
            }
            return index
        }
        return index + Character.charCount(codePoint)
    }

    /** Number of UTF-16 units one backward word deletion removes before [from]. */
    fun backwardDeletionLength(text: String, from: Int): Int {
        val end = from.coerceIn(0, text.length)
        return end - previousWordStart(text, end)
    }

    /**
     * True for CJK characters that behave as single-character words. Han covers
     * the unified ideographs and their extensions; the extra ranges keep
     * compatibility forms working without a script lookup.
     */
    fun isCjk(codePoint: Int): Boolean {
        if (codePoint in 0x4E00..0x9FFF) return true // CJK Unified Ideographs
        if (codePoint in 0x3400..0x4DBF) return true // Extension A
        if (codePoint in 0xF900..0xFAFF) return true // Compatibility Ideographs
        if (codePoint in 0x20000..0x2FA1F) return true // Extensions B-F
        return false
    }

    private fun isWordChar(codePoint: Int): Boolean =
        !isCjk(codePoint) && Character.isLetterOrDigit(codePoint)
}

/** Snapshot of the editor content around the cursor. */
data class EditorTextSnapshot(
    val selected: String?,
    val beforeCursor: String?,
)

/** Absolute caret or selection range in the editor, in UTF-16 code units. */
data class EditorSelection(
    val start: Int,
    val end: Int,
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

    /** Deletes [count] UTF-16 units before the cursor; used by one-step undo. */
    fun deleteBackward(count: Int) {
        if (count <= 0) return
        repeat(count) { deleteBackward() }
    }

    /** Moves the cursor by [delta] user-visible-ish characters; false when unavailable. */
    fun moveCursorBy(delta: Int): Boolean = false

    /** Selects the whole editor content; false when unavailable or empty. */
    fun selectAll(): Boolean = false

    /** Copies the current selection to the clipboard; false when unavailable or empty. */
    fun copySelection(): Boolean = false

    /** Cuts the current selection to the clipboard; false when unavailable or empty. */
    fun cutSelection(): Boolean = false

    /** Pastes clipboard text at the cursor or replaces the current selection. */
    fun paste(): Boolean = false

    /** Moves the cursor by [delta] words; false when unavailable or delta is zero. */
    fun moveCursorByWord(delta: Int): Boolean = false

    /** Deletes the word before the cursor (or the selection); false when unavailable. */
    fun deleteBackwardWord(): Boolean = false

    fun sendEnter()

    /** Selected text, or null when the cursor has no selection. */
    fun getSelectedText(): String?

    /** Up to [maxChars] characters immediately before the cursor. */
    fun getTextBeforeCursor(maxChars: Int): String?

    /** Up to [maxChars] characters immediately after the cursor. */
    fun getTextAfterCursor(maxChars: Int): String? = null

    /**
     * Last absolute selection reported by the host editor, or null when the
     * editor has not delivered a selection yet and no fallback is available.
     */
    fun getSelection(): EditorSelection? = null

    /**
     * Records the absolute selection from InputMethodService.onUpdateSelection.
     * The service should forward every callback here, including composing-end
     * and repeated ones, so cursor math never works from a stale caret.
     */
    fun updateSelection(start: Int, end: Int) = Unit

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

    /** Absolute selection last reported by the service's onUpdateSelection. */
    private var cachedSelection: EditorSelection? = null
    private var cachedSelectionConnection: InputConnection? = null

    override val isActive: Boolean
        get() = connection != null

    override fun commitText(text: String) {
        val input = connection ?: return
        invalidateSelectionCache()
        input.commitText(text, 1)
    }

    override fun setComposingText(text: String) {
        val input = connection ?: return
        invalidateSelectionCache()
        if (text.isEmpty()) {
            input.finishComposingText()
        } else {
            input.setComposingText(text, 1)
        }
    }

    override fun finishComposingText() {
        val input = connection ?: return
        invalidateSelectionCache()
        input.finishComposingText()
    }

    override fun deleteBackward() {
        val input = connection ?: return
        invalidateSelectionCache()
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

    override fun deleteBackward(count: Int) {
        if (count <= 0) return
        val input = connection ?: return
        invalidateSelectionCache()
        input.deleteSurroundingText(count, 0)
    }

    override fun moveCursorBy(delta: Int): Boolean {
        if (delta == 0) return false
        val input = connection ?: return false
        val selection = getSelection() ?: return false
        // A selection collapses in the requested direction before the move:
        // right goes to the selection end, left to its start.
        val anchor = if (delta > 0) {
            maxOf(selection.start, selection.end)
        } else {
            minOf(selection.start, selection.end)
        }
        val target = if (delta > 0) {
            val requested = minOf(delta, MAX_CONTEXT_READ)
            val after = input.getTextAfterCursor(requested, 0)?.toString() ?: return false
            // A short read means the editor ends before the requested delta.
            if (after.length < requested) anchor + after.length else anchor + delta
        } else {
            val requested = minOf(-delta, MAX_CONTEXT_READ)
            val before = input.getTextBeforeCursor(requested, 0)?.toString() ?: return false
            if (before.length < requested) anchor - before.length else anchor + delta
        }
        return applySelection(input, target, target)
    }

    override fun selectAll(): Boolean {
        val input = connection ?: return false
        if (runCatching { input.performContextMenuAction(android.R.id.selectAll) }.getOrDefault(false)) {
            invalidateSelectionCache()
            return true
        }
        // Fallback for editors that do not implement the context-menu action:
        // ExtractedText carries the absolute start offset of the read range, so
        // startOffset..startOffset+length selects everything without a whole-document read.
        val extracted = runCatching {
            input.getExtractedText(ExtractedTextRequest(), 0)
        }.getOrNull() ?: return false
        val text = extracted.text ?: return false
        return applySelection(input, extracted.startOffset, extracted.startOffset + text.length)
    }

    override fun copySelection(): Boolean {
        val input = connection ?: return false
        if (runCatching { input.performContextMenuAction(android.R.id.copy) }.getOrDefault(false)) return true
        val selected = input.getSelectedText(0)?.toString()
        if (selected.isNullOrEmpty()) return false
        return writeClipboard(selected)
    }

    override fun cutSelection(): Boolean {
        val input = connection ?: return false
        if (runCatching { input.performContextMenuAction(android.R.id.cut) }.getOrDefault(false)) {
            invalidateSelectionCache()
            return true
        }
        val selected = input.getSelectedText(0)?.toString()
        if (selected.isNullOrEmpty()) return false
        if (!writeClipboard(selected)) return false
        invalidateSelectionCache()
        input.commitText("", 1)
        return true
    }

    override fun paste(): Boolean {
        val input = connection ?: return false
        if (runCatching { input.performContextMenuAction(android.R.id.paste) }.getOrDefault(false)) {
            invalidateSelectionCache()
            return true
        }
        val text = readClipboardText() ?: return false
        invalidateSelectionCache()
        input.commitText(text, 1)
        return true
    }

    override fun moveCursorByWord(delta: Int): Boolean {
        if (delta == 0) return false
        val input = connection ?: return false
        val selection = getSelection() ?: return false
        // A selection collapses in the requested direction before the next move.
        if (selection.start != selection.end) {
            val target = if (delta > 0) {
                maxOf(selection.start, selection.end)
            } else {
                minOf(selection.start, selection.end)
            }
            return applySelection(input, target, target)
        }
        // Word boundaries only need a bounded window around the caret. Anchor
        // the window on the cached absolute caret to recover absolute offsets.
        val before = input.getTextBeforeCursor(MAX_CONTEXT_READ, 0)?.toString().orEmpty()
        val after = input.getTextAfterCursor(MAX_CONTEXT_READ, 0)?.toString().orEmpty()
        val windowStart = (selection.start - before.length).coerceAtLeast(0)
        val localCursor = selection.start - windowStart
        val full = before + after
        val localTarget = if (delta > 0) {
            WordBoundary.nextWordEnd(full, localCursor)
        } else {
            WordBoundary.previousWordStart(full, localCursor)
        }
        val target = windowStart + localTarget
        return applySelection(input, target, target)
    }

    override fun deleteBackwardWord(): Boolean {
        val input = connection ?: return false
        invalidateSelectionCache()
        val selected = input.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            input.commitText("", 1)
            return true
        }
        val before = input.getTextBeforeCursor(MAX_CONTEXT_READ, 0)?.toString().orEmpty()
        val count = WordBoundary.backwardDeletionLength(before, before.length)
        if (count <= 0) return false
        // The count is relative to the cursor, so this never needs the absolute
        // editor length and can never remove the whole document by accident.
        input.deleteSurroundingText(count, 0)
        return true
    }

    override fun sendEnter() {
        val input = connection
        if (input == null) {
            service.sendDefaultEditorAction(false)
            return
        }
        invalidateSelectionCache()
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

    override fun getTextAfterCursor(maxChars: Int): String? {
        if (maxChars <= 0) return null
        return connection?.getTextAfterCursor(maxChars, 0)?.toString()
    }

    override fun getSelection(): EditorSelection? {
        val input = connection ?: return null
        val cached = cachedSelection
        if (cached != null && cachedSelectionConnection === input) return cached
        // No onUpdateSelection callback yet (for example on a fresh focus):
        // SurroundingText carries both the absolute offset of its window and
        // the selection coordinates relative to that window.
        return runCatching {
            val surrounding = input.getSurroundingText(0, 0, 0) ?: return@runCatching null
            val offset = surrounding.offset
            val start = surrounding.selectionStart
            val end = surrounding.selectionEnd
            if (offset < 0 || start < 0 || end < 0) {
                null
            } else {
                EditorSelection(offset + start, offset + end)
            }
        }.getOrNull()
    }

    override fun updateSelection(start: Int, end: Int) {
        val input = connection
        if (input == null) {
            invalidateSelectionCache()
            return
        }
        cachedSelectionConnection = input
        cachedSelection = if (start >= 0 && end >= 0) EditorSelection(start, end) else null
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

    /** Applies an absolute selection and keeps the cache coherent for the next move. */
    private fun applySelection(input: InputConnection, start: Int, end: Int): Boolean {
        val applied = runCatching { input.setSelection(start, end) }.getOrDefault(false)
        if (applied) {
            cachedSelectionConnection = input
            cachedSelection = EditorSelection(start, end)
        }
        return applied
    }

    private fun invalidateSelectionCache() {
        cachedSelection = null
        cachedSelectionConnection = null
    }

    private val clipboardManager: ClipboardManager?
        get() = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun writeClipboard(text: String): Boolean {
        val manager = clipboardManager ?: return false
        return runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            true
        }.getOrDefault(false)
    }

    private fun readClipboardText(): String? {
        val clip = clipboardManager?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return runCatching {
            clip.getItemAt(0).coerceToText(service)?.toString()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private companion object {
        /** Upper bound for every cursor/word context read; keeps moves O(1). */
        const val MAX_CONTEXT_READ = 4_096
        const val CLIP_LABEL = "Bitap"
    }
}
