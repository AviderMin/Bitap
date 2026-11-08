package com.bitap.keyboard.qa

import com.bitap.keyboard.core.PinyinEngine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Acceptance checks against the real dictionary asset shipped inside the APK
 * (not a synthetic fixture): docs/EVALUATION.md covers dictionary-backed acceptance (nihao -> 你好).
 */
class QaPinyinAcceptanceTest {

    private fun realEngine(): PinyinEngine {
        val dictionary = File("src/main/assets/pinyin_dict.tsv")
        assumeTrue("bundled dictionary missing: " + dictionary.absolutePath, dictionary.isFile)
        return dictionary.useLines { lines -> PinyinEngine.fromLines(lines) }
    }

    @Test
    fun realDictionaryIsLargeAndReady() {
        val engine = realEngine()
        assertTrue(engine.isReady)
        assertTrue("expected >10k entries, was " + engine.entryCount, engine.entryCount > 10_000)
    }

    @Test
    fun nihaoProducesNiHaoAsFirstCandidate() {
        val candidates = realEngine().search("nihao")
        assertFalse(candidates.isEmpty())
        assertEquals("你好", candidates.first().word)
        assertTrue("candidate list must be capped at 8", candidates.size <= PinyinEngine.DEFAULT_LIMIT)
    }

    @Test
    fun commonSingleSyllableRanksMostFrequentWordFirst() {
        val candidates = realEngine().search("wo")
        assertEquals("我", candidates.first().word)
        assertEquals(candidates.size, candidates.map { it.word }.distinct().size)
    }

    @Test
    fun prefixLookupFindsLongerWordInRealDictionary() {
        assertTrue(realEngine().search("niha").map { it.word }.contains("你好"))
    }

    @Test
    fun unmatchedInputReturnsEmptyWithoutThrow() {
        val engine = realEngine()
        assertTrue(engine.search("zzzzzzzz").isEmpty())
        assertTrue(engine.search("").isEmpty())
        assertTrue(engine.search("  ").isEmpty())
    }
}
