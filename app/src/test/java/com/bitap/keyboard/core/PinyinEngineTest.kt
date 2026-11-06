package com.bitap.keyboard.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PinyinEngineTest {

    private fun engine(): PinyinEngine = PinyinEngine.fromLines(
        sequenceOf(
            "# Bitap test dictionary",
            "",
            "ni\t你\t100",
            "ni\t泥\t50",
            "ni\t你\t10",
            "nihao\t你好\t80",
            "nihaoma\t你好吗\t20",
            "hao\t好\t200",
        ),
    )

    @Test
    fun exactPinyinReturnsWordFirst() {
        val candidates = engine().search("nihao")
        assertFalse(candidates.isEmpty())
        assertEquals("你好", candidates.first().word)
    }

    @Test
    fun singleCharacterCandidatesAreAvailable() {
        val candidates = engine().search("ni")
        val words = candidates.map { it.word }
        assertTrue(words.contains("你"))
        assertTrue(words.contains("泥"))
        assertEquals("你", candidates.first().word)
    }

    @Test
    fun prefixOfLongerPinyinFindsWord() {
        val candidates = engine().search("niha")
        assertTrue(candidates.map { it.word }.contains("你好"))
    }

    @Test
    fun queryIsCaseInsensitiveAndTrimsSpaces() {
        val candidates = engine().search("  NIHAO  ")
        assertEquals("你好", candidates.first().word)
    }

    @Test
    fun noMatchReturnsEmptyList() {
        assertTrue(engine().search("zzzz").isEmpty())
        assertTrue(engine().search("").isEmpty())
    }

    @Test
    fun limitIsRespectedAndNeverAboveEight() {
        val lines = (1..20).map { index -> "ni\t字" + index + "\t" + index }
        val many = PinyinEngine.fromLines(lines.asSequence())
        assertEquals(2, many.search("ni", limit = 2).size)
        assertEquals(8, many.search("ni", limit = 100).size)
    }

    @Test
    fun malformedLinesAreIgnored() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "# comment",
                "not-a-tsv-line",
                "ab\t",
                "\tword\t1",
                "ha\t哈\t5",
            ),
        )
        assertEquals(1, engine.entryCount)
        assertEquals("哈", engine.search("ha").first().word)
    }

    @Test
    fun duplicateWordsAreDeduplicated() {
        val candidates = engine().search("ni", limit = 8)
        assertEquals(1, candidates.count { it.word == "你" })
    }

    @Test
    fun exactMatchesAreRankedBeforePrefixMatches() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "ni\t你\t10",
                "nihao\t你好\t999999",
            ),
        )
        assertEquals("你", engine.search("ni").first().word)
    }

    @Test
    fun realDictionaryContainsNihao() {
        val dictionary = File("src/main/assets/pinyin_dict.tsv")
        assumeTrue("bundled dictionary not found at " + dictionary.absolutePath, dictionary.isFile)
        val engine = dictionary.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue(engine.isReady)
        assertTrue(engine.entryCount > 10_000)
        val candidates = engine.search("nihao")
        assertTrue(candidates.map { it.word }.contains("你好"))
        assertTrue(engine.search("ni").isNotEmpty())
        assertTrue(engine.search("zzzzzz").isEmpty())
    }
}
