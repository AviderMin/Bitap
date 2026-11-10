package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** T07: one backspace must remove one visible Unicode character, not one code unit. */
class UnicodeGraphemeTest {

    private fun backspaceOnce(text: String): String = UnicodeGrapheme.dropLastGrapheme(text)

    @Test
    fun ordinaryLatinAndCjkCharactersDeleteOneCodePoint() {
        assertEquals("ab", backspaceOnce("abc"))
        assertEquals("你", backspaceOnce("你好"))
        assertEquals("", backspaceOnce("a"))
    }

    @Test
    fun emojiSurrogatePairsAndSkinToneModifiersStayWhole() {
        assertEquals("", backspaceOnce("👍"))
        assertEquals("", backspaceOnce("👍🏽"))
        assertEquals("ab", backspaceOnce("ab👍🏽"))
    }

    @Test
    fun zwjFamilySequencesAndVariationSelectorsStayWhole() {
        assertEquals("", backspaceOnce("👨‍👩‍👧‍👦"))
        assertEquals("a", backspaceOnce("a👩🏽‍🚀"))
        assertEquals("a", backspaceOnce("a❤️"))
        assertEquals("", backspaceOnce("1️⃣"))
    }

    @Test
    fun regionalIndicatorFlagsDeleteAsPairs() {
        assertEquals("", backspaceOnce("🇨🇳"))
        assertEquals("a", backspaceOnce("a🇨🇳"))
        // Two flags in sequence: one backspace removes the last flag, not both.
        assertEquals("🇨🇳", backspaceOnce("🇨🇳🇺🇸"))
        assertEquals("🇨🇳🇺🇸", backspaceOnce("🇨🇳🇺🇸a"))
    }

    @Test
    fun combiningMarksDeleteWithTheirBaseCharacter() {
        assertEquals("", backspaceOnce("e\u0301"))
        assertEquals("a", backspaceOnce("ae\u0301"))
        assertEquals("", backspaceOnce("a\u0301\u0323"))
    }

    @Test
    fun lastGraphemeLengthUsesUtf16CodeUnits() {
        assertEquals(1, UnicodeGrapheme.lastGraphemeLength("abc"))
        assertEquals(2, UnicodeGrapheme.lastGraphemeLength("a👍"))
        assertEquals(4, UnicodeGrapheme.lastGraphemeLength("x🇨🇳"))
        assertEquals(11, UnicodeGrapheme.lastGraphemeLength("👨‍👩‍👧‍👦"))
        assertEquals(2, UnicodeGrapheme.lastGraphemeLength("e\u0301"))
    }

    @Test
    fun forwardTraversalMovesOneGraphemeAtATime() {
        val text = "A😀B"
        assertEquals(1, UnicodeGrapheme.nextBoundary(text, 0))
        assertEquals(3, UnicodeGrapheme.nextBoundary(text, 1))
        assertEquals(4, UnicodeGrapheme.nextBoundary(text, 3))
        assertEquals(4, UnicodeGrapheme.nextBoundary(text, 4))
        assertEquals(3, UnicodeGrapheme.moveBy(text, 0, 2))
    }

    @Test
    fun backwardTraversalMovesOneGraphemeAtATime() {
        val text = "A😀B"
        assertEquals(3, UnicodeGrapheme.previousBoundary(text, 4))
        assertEquals(1, UnicodeGrapheme.previousBoundary(text, 3))
        assertEquals(0, UnicodeGrapheme.previousBoundary(text, 1))
        assertEquals(0, UnicodeGrapheme.previousBoundary(text, 0))
        assertEquals(1, UnicodeGrapheme.moveBy(text, 4, -2))
    }

    @Test
    fun forwardAndBackwardTraversalKeepsFlagsWhole() {
        val text = "a🇨🇳🇺🇸b"
        assertEquals(5, UnicodeGrapheme.nextBoundary(text, 1))
        assertEquals(9, UnicodeGrapheme.nextBoundary(text, 5))
        assertEquals(10, UnicodeGrapheme.nextBoundary(text, 9))
        assertEquals(9, UnicodeGrapheme.previousBoundary(text, 10))
        assertEquals(5, UnicodeGrapheme.previousBoundary(text, 9))
        assertEquals(1, UnicodeGrapheme.previousBoundary(text, 5))
        assertEquals(2, UnicodeGrapheme.graphemeCount("🇨🇳🇺🇸"))
    }

    @Test
    fun forwardAndBackwardTraversalKeepsZwjFamilyWhole() {
        val text = "X👨‍👩‍👧‍👦Y"
        assertEquals(12, UnicodeGrapheme.nextBoundary(text, 1))
        assertEquals(13, UnicodeGrapheme.nextBoundary(text, 12))
        assertEquals(12, UnicodeGrapheme.previousBoundary(text, 13))
        assertEquals(1, UnicodeGrapheme.previousBoundary(text, 12))
        assertEquals(1, UnicodeGrapheme.moveBy(text, 13, -2))
    }

    @Test
    fun forwardAndBackwardTraversalKeepsCombiningMarksWithBase() {
        val text = "ae\u0301b"
        assertEquals(1, UnicodeGrapheme.nextBoundary(text, 0))
        assertEquals(3, UnicodeGrapheme.nextBoundary(text, 1))
        assertEquals(1, UnicodeGrapheme.previousBoundary(text, 3))
        assertEquals(3, UnicodeGrapheme.moveBy(text, 1, 1))
        assertEquals(1, UnicodeGrapheme.moveBy(text, 3, -1))
    }

    @Test
    fun graphemeCountCountsVisibleCharacters() {
        assertEquals(3, UnicodeGrapheme.graphemeCount("A😀B"))
        assertEquals(1, UnicodeGrapheme.graphemeCount("👨‍👩‍👧‍👦"))
        assertEquals(2, UnicodeGrapheme.graphemeCount("e\u0301a"))
        assertEquals(0, UnicodeGrapheme.graphemeCount(""))
    }

    @Test
    fun wordBoundariesNeverSplitSurrogatePairs() {
        assertEquals(1, WordBoundary.nextWordEnd("A😀B", 0))
        assertEquals(3, WordBoundary.previousWordStart("A😀B", 4))
        assertEquals(1, WordBoundary.previousWordStart("A😀B", 3))
    }
}
