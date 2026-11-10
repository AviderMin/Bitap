package com.bitap.keyboard.core

import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
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

    private class Host(var text: String, var start: Int = text.length, var end: Int = start) {
        val deletions = mutableListOf<Int>()
        private val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getTextBeforeCursor" -> text.substring(0, minOf(start, end)).takeLast(args[0] as Int)
                "getTextAfterCursor" -> text.substring(maxOf(start, end)).take(args[0] as Int)
                "getSelectedText" -> text.substring(minOf(start, end), maxOf(start, end))
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
