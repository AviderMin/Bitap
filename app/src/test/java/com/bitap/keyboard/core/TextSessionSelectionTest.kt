package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract used by controller cursor tracking: a [TextSession] exposes an
 * absolute selection plus bounded text reads around it.
 */
class TextSessionSelectionTest {

    @Test
    fun updateSelectionMovesTheFakeEditorCaret() {
        val session = TestEditorTextSession(initialText = "hello world")
        session.updateSelection(6, 11)

        assertEquals(EditorSelection(6, 11), session.getSelection())
        assertEquals("world", session.getSelectedText())
        assertEquals("hello ", session.getTextBeforeCursor(4_096))
        assertEquals("", session.getTextAfterCursor(4_096))
    }

    @Test
    fun afterCursorReadIsBoundedAndStartsAtSelectionEnd() {
        val session = TestEditorTextSession(initialText = "abcdef")
        session.select(2, 4)

        assertEquals("ef", session.getTextAfterCursor(4_096))
        assertEquals("cd", session.getSelectedText())
        assertEquals("ab", session.getTextBeforeCursor(4_096))
        assertEquals("e", session.getTextAfterCursor(1))
        assertNull(session.getTextAfterCursor(0))
    }

    @Test
    fun fakeTracksSelectionThroughCommits() {
        val session = TestEditorTextSession(initialText = "ab")
        session.select(2, 2)
        session.commitText("c")

        assertEquals(EditorSelection(3, 3), session.getSelection())
        assertEquals("", session.getTextAfterCursor(8))
    }

    @Test
    fun moveCursorByNeverSelectsInsideSurrogatePair() {
        val session = TestEditorTextSession(initialText = "A😀B")
        // The fake starts with the caret after B at UTF-16 offset 4.
        assertTrue(session.moveCursorBy(-1))
        assertEquals("left one grapheme stops before B", 3, session.cursor)
        assertNotInsideSurrogate(session)

        assertTrue(session.moveCursorBy(-1))
        assertEquals("left again stops before the emoji", 1, session.cursor)
        assertNotInsideSurrogate(session)

        session.updateSelection(1, 1)
        assertTrue(session.moveCursorBy(1))
        assertEquals("right stops after the emoji", 3, session.cursor)
        assertNotInsideSurrogate(session)

        session.updateSelection(3, 3)
        assertTrue(session.moveCursorBy(-1))
        assertEquals("the classic emoji regression must land before the emoji", 1, session.cursor)
        assertNotInsideSurrogate(session)
    }

    @Test
    fun moveCursorByKeepsZwjFamilyWhole() {
        val session = TestEditorTextSession(initialText = "X👨‍👩‍👧‍👦Y")
        assertTrue(session.moveCursorBy(-1))
        assertEquals(12, session.cursor)
        assertTrue(session.moveCursorBy(-1))
        assertEquals(1, session.cursor)
        assertTrue(session.moveCursorBy(1))
        assertEquals(12, session.cursor)
    }

    private fun assertNotInsideSurrogate(session: TestEditorTextSession) {
        val cursor = session.cursor
        if (cursor in 1 until session.text.length) {
            assertFalse(
                "cursor " + cursor + " splits a surrogate pair",
                Character.isLowSurrogate(session.text[cursor]),
            )
        }
    }

    @Test
    fun defaultTextSessionSelectionApisAreOptional() {
        val session = object : TextSession {
            override val isActive: Boolean = true
            override fun commitText(text: String) = Unit
            override fun setComposingText(text: String) = Unit
            override fun finishComposingText() = Unit
            override fun deleteBackward() = Unit
            override fun sendEnter() = Unit
            override fun getSelectedText(): String? = null
            override fun getTextBeforeCursor(maxChars: Int): String? = null
            override fun getSurroundingText(maxChars: Int): EditorTextSnapshot =
                EditorTextSnapshot(null, null)
            override fun readAiSource(maxChars: Int): String = ""
        }

        assertNull(session.getSelection())
        assertNull(session.getTextAfterCursor(64))
        session.updateSelection(1, 2)
    }
}
