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
}
