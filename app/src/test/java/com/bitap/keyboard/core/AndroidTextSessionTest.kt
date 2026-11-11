package com.bitap.keyboard.core

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production adapter, including UTF-16 counts sent to the host. */
class AndroidTextSessionTest {
    @Test
    fun backspaceDeletesEmojiInOneHostOperation() {
        val host = Host("A😀B")
        host.session.deleteBackward()
        host.session.deleteBackward()
        assertEquals("A", host.text)
        assertEquals(listOf(1, 2), host.deletions)
    }

    @Test
    fun backspaceDoesNothingAtStartOfField() {
        val host = Host("A😀B", 0)
        host.session.deleteBackward()
        assertEquals("A😀B", host.text)
        assertTrue(host.deletions.isEmpty())
    }

    @Test
    fun multiStepMoveWalksEachClusterRatherThanRepeatingFirstLength() {
        val host = Host("A😀B")
        assertTrue(host.session.moveCursorBy(-2))
        assertEquals(EditorSelection(1, 1), host.session.getSelection())
        assertTrue(host.session.moveCursorBy(2))
        assertEquals(EditorSelection(4, 4), host.session.getSelection())
        assertEquals("A😀B", host.text)
    }

    @Test
    fun preciseEmojiMovesUseUtf16Boundaries() {
        val host = Host("A😀B", 3)
        assertTrue(host.session.moveCursorBy(-1))
        assertEquals(1, host.start)
        assertTrue(host.session.moveCursorBy(1))
        assertEquals(3, host.start)
    }

    @Test
    fun movingAcrossSelectionStartsAtDirectionalEdge() {
        val host = Host("XA😀BY", 1, 5)
        assertTrue(host.session.moveCursorBy(-1))
        assertEquals(0, host.start)
        val other = Host("XA😀BY", 1, 5)
        assertTrue(other.session.moveCursorBy(1))
        assertEquals(6, other.start)
    }

    @Test
    fun getSelectedTextKeepsWhitespaceSelectionAndNullWhenCollapsed() {
        val spaces = Host("a  b", 1, 3)
        assertEquals("  ", spaces.session.getSelectedText())

        val collapsed = Host("a  b")
        assertEquals(null, collapsed.session.getSelectedText())
    }

    @Test
    fun wordDeleteRemovesTheWholeZwjFamilyInOneHostOperation() {
        val host = Host("A👨‍👩‍👧‍👦")
        assertTrue(host.session.deleteBackwardWord())
        assertEquals("A", host.text)
        assertEquals(listOf(11), host.deletions)
    }

    @Test
    fun wordCursorKeepsZwjFamilyWhole() {
        val host = Host("A👨‍👩‍👧‍👦")
        assertTrue(host.session.moveCursorByWord(-1))
        assertEquals(1, host.start)
        assertTrue(host.session.moveCursorByWord(1))
        assertEquals(12, host.start)
    }

    @Test
    fun longMoveClampsAtTextBoundary() {
        val host = Host("A😀B")
        host.session.moveCursorBy(Int.MIN_VALUE)
        assertEquals(0, host.start)
        host.session.moveCursorBy(Int.MAX_VALUE)
        assertEquals(4, host.start)
    }

    @Test
    fun fullWindowEdgeCannotBecomeACaretInsideSurrogatePair() {
        val left = Host("A😀" + "x".repeat(4095))
        left.session.moveCursorBy(Int.MIN_VALUE)
        assertEquals(3, left.start)
        val right = Host("x".repeat(4095) + "😀B", 0)
        right.session.moveCursorBy(Int.MAX_VALUE)
        assertEquals(4095, right.start)
    }

    @Test
    fun selectAllRejectsPartialExtractedTextWindow() {
        val document = "x".repeat(100) + "middle" + "y".repeat(94)
        val host = Host(
            document,
            start = 103,
            extracted = ExtractedText().apply {
                text = "middle"
                startOffset = 100
                selectionStart = 3
                selectionEnd = 3
            },
        )

        assertFalse("a local ExtractedText window is not the whole document", host.session.selectAll())
        assertEquals("an unconfirmed range must not move the selection", 103, host.start)
        assertEquals("setSelection must not be called", 0, host.setSelectionCalls)
    }

    @Test
    fun selectAllRejectsEmptyDocument() {
        val host = Host(
            "",
            start = 0,
            extracted = ExtractedText().apply {
                text = ""
                startOffset = 0
            },
        )

        assertFalse("an empty document has nothing to select", host.session.selectAll())
        assertEquals("setSelection must not be called", 0, host.setSelectionCalls)
    }

    @Test
    fun selectAllSelectsCompleteDocumentWithCollapsedCaret() {
        val document = "hello world"
        // Collapsed caret: the Host returns null from getSelectedText(0), as the
        // real InputConnection does when there is no selection.
        val host = Host(
            document,
            start = 5,
            extracted = ExtractedText().apply {
                text = document
                startOffset = 0
            },
        )

        assertTrue(host.session.selectAll())
        assertEquals(0, host.start)
        assertEquals(document.length, host.end)
        assertEquals(1, host.setSelectionCalls)
        assertEquals("the fallback must request a maximal text window", 1_000_000, host.extractedRequests.single().hintMaxChars)
    }

    @Test
    fun selectAllReturnsFalseWhenContextReadThrows() {
        val host = Host(
            "hello",
            start = 2,
            extracted = ExtractedText().apply {
                text = "hello"
                startOffset = 0
            },
            throwOnContextRead = true,
        )

        assertFalse("a throwing host read must not crash selectAll", host.session.selectAll())
        assertEquals("setSelection must not be called", 0, host.setSelectionCalls)
    }

    @Test
    fun selectAllUsesContextMenuFastPathWithoutExtractedText() {
        val host = Host("abc", contextMenuSelectAll = true)

        assertTrue(host.session.selectAll())
        assertTrue("the fast path must not read ExtractedText", host.extractedRequests.isEmpty())
        assertEquals("the host context-menu action applies the selection", 0, host.setSelectionCalls)
    }

    @Test
    fun backspaceDeletesLongCombiningClusterInOneHostOperation() {
        val text = "A" + "e" + "\u0301".repeat(70)
        val host = Host(text)

        host.session.deleteBackward()

        assertEquals("A", host.text)
        assertEquals(listOf(71), host.deletions)
    }

    @Test
    fun backspaceInLongWordDeletesExactlyOneUnitInOneHostCall() {
        val text = "a".repeat(100)
        val host = Host(text)

        host.session.deleteBackward()

        assertEquals("a".repeat(99), host.text)
        assertEquals(1, host.deletions.size)
        assertEquals(1, host.deletions.single())
    }

    private class Host(
        var text: String,
        var start: Int = text.length,
        var end: Int = start,
        private val extracted: ExtractedText? = null,
        private val contextMenuSelectAll: Boolean = false,
        private val throwOnContextRead: Boolean = false,
    ) {
        val deletions = mutableListOf<Int>()
        val extractedRequests = mutableListOf<ExtractedTextRequest>()
        var setSelectionCalls = 0
            private set
        private val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getTextBeforeCursor" ->
                    if (throwOnContextRead) {
                        throw IllegalStateException("host context read failed")
                    } else {
                        text.substring(0, minOf(start, end)).takeLast(args[0] as Int)
                    }
                "getTextAfterCursor" -> text.substring(maxOf(start, end)).take(args[0] as Int)
                "getSelectedText" ->
                    if (start == end) {
                        null
                    } else {
                        text.substring(minOf(start, end), maxOf(start, end))
                    }
                "performContextMenuAction" -> contextMenuSelectAll && (args[0] as Int) == android.R.id.selectAll
                "getExtractedText" -> {
                    extractedRequests += args[0] as ExtractedTextRequest
                    extracted
                }
                "deleteSurroundingText" -> {
                    val count = args[0] as Int
                    deletions += count
                    val from = (start - count).coerceAtLeast(0)
                    text = text.removeRange(from, start)
                    start = from
                    end = from
                    true
                }
                "setSelection" -> {
                    setSelectionCalls++
                    start = args[0] as Int
                    end = args[1] as Int
                    true
                }
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
        } as InputConnection
        val session = AndroidTextSession({ connection }).also { it.updateSelection(start, end) }
    }
}
