package com.bitap.keyboard.ai

import com.bitap.keyboard.ai.internal.AiPinyinDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPinyinDictionaryTest {

    private val dictionary = AiPinyinDictionary.parse(
        sequenceOf(
            "# comment",
            "",
            "nihao\t你好\t120000",
            "xian\t西安\t900",
            "zhang\t长\t100",
        ),
    )

    @Test
    fun parsesPinyinWordRows() {
        assertEquals(3, dictionary.size)
        assertTrue(dictionary.containsWord("你好"))
        assertTrue(dictionary.containsWord("西安"))
        assertTrue(dictionary.containsWord("长"))
    }

    @Test
    fun matchesExactFullPinyin() {
        assertTrue(dictionary.matches("你好", "nihao"))
        assertTrue(dictionary.matches("西安", "xian"))
    }

    @Test
    fun matchesPrefixForCompletion() {
        assertTrue(dictionary.matches("你好", "ni"))
        assertTrue(dictionary.matches("你好", "nih"))
    }

    @Test
    fun rejectsUnrelatedPronunciation() {
        assertFalse(dictionary.matches("你好", "tianqi"))
        assertFalse(dictionary.matches("西安", "nihao"))
        assertFalse(dictionary.matches("未知词", "nihao"))
    }

    @Test
    fun normalizesApostropheAndCase() {
        assertEquals("xian", AiPinyinDictionary.normalizePinyin("Xi'An"))
        assertTrue(dictionary.matches("西安", "xi'an"))
        assertTrue(dictionary.matches("你好", "NiHao"))
    }

    @Test
    fun keepsMultipleReadingsFromTrustedMap() {
        val polyphonic = AiPinyinDictionary.fromMap(
            mapOf("行" to setOf("xing", "hang")),
        )
        assertTrue(polyphonic.matches("行", "xing"))
        assertTrue(polyphonic.matches("行", "hang"))
    }

    @Test
    fun parseAddsCommonPolyphonicReadings() {
        assertTrue(dictionary.matches("长", "chang"))
        assertTrue(dictionary.matches("长", "zhang"))
    }

    @Test
    fun emptyDictionaryRejectsEverything() {
        assertFalse(AiPinyinDictionary.EMPTY.matches("你好", "nihao"))
        assertEquals(0, AiPinyinDictionary.EMPTY.size)
    }

    @Test
    fun composesNewWordsFromKnownCharacters() {
        val composed = AiPinyinDictionary.fromMap(
            mapOf(
                "长" to setOf("zhang", "chang"),
                "城" to setOf("cheng"),
                "天" to setOf("tian"),
                "气" to setOf("qi"),
            ),
        )
        assertTrue(composed.matches("长城", "changcheng"))
        assertTrue(composed.matches("长城", "zhangcheng"))
        assertTrue(composed.matches("长城", "chang"))
        assertTrue(composed.matches("天气", "tianqi"))
        assertFalse(composed.matches("长城", "beijing"))
    }

    @Test
    fun composedWordNeedsEveryCharacterReading() {
        val partial = AiPinyinDictionary.fromMap(mapOf("天" to setOf("tian")))
        assertFalse(partial.matches("天气", "tianqi"))
    }
}
