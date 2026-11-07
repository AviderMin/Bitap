package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generic mechanism tests for the long-segment decoder. They use synthetic
 * dictionaries and never any acceptance sentence from the evaluation corpus.
 */
class PinyinEngineLongSegmentMechanismTest {

    private fun engine(vararg lines: String, tuning: PinyinEngine.EngineTuning? = null): PinyinEngine {
        val sequence = lines.asSequence()
        return if (tuning == null) {
            PinyinEngine.fromLines(sequence)
        } else {
            PinyinEngine.fromLinesWithTuning(sequence, tuning)
        }
    }

    @Test
    fun productionDefaultsUseLongSegmentTuning() {
        val tuning = PinyinEngine.EngineTuning()
        assertEquals(64, tuning.beamSize)
        assertEquals(8, tuning.entriesPerKey)
        assertEquals(0.4, tuning.dictionaryWordNgramWeight, 1e-9)
        assertEquals(4.0, tuning.partialCoverageWeight, 1e-9)
        assertEquals(1, tuning.partialEarlySlotCount)
    }

    /**
     * A long input whose lattice produces far more full sentences than the
     * candidate cap must still expose partial first-segment picks. This is the
     * generic shape of the "prefix disappears behind a full sentence" bug.
     */
    @Test
    fun longInputKeepsPartialPrefixCandidates() {
        val lines = ArrayList<String>()
        lines += "shan\t山\t10000"
        val homophones = listOf("山山", "山善", "善山", "善善", "姗山")
        homophones.forEachIndexed { index, word ->
            lines += "shan\t" + word + "\t" + (1000 - index)
        }
        val tone = engine(*lines.toTypedArray())
        val input = "shan".repeat(8)
        val candidates = tone.search(input, limit = 8)
        assertTrue("expected a candidate page", candidates.isNotEmpty())
        assertTrue(
            "a long input must expose a partial prefix selection: " + candidates.map { it.word },
            candidates.any { it.consumedInputLength < input.length },
        )
    }

    /** A wider beam/entry budget must expose strictly more lattice paths. */
    @Test
    fun widerBeamExposesMorePaths() {
        val lines = ArrayList<String>()
        lines += "shan\t山\t10000"
        repeat(40) { index ->
            lines += "shan\t" + "山" + ('一'.code + index).toChar() + "\t" + (1000 - index)
        }
        val tone = engine(*lines.toTypedArray())
        val narrow = tone.debugSearch("shanshanshan", beamSize = 8, entriesPerKey = 1)
        val wide = tone.debugSearch("shanshanshan", beamSize = 64, entriesPerKey = 8)
        assertTrue(
            "wide beam should keep more full paths: narrow=" + narrow.latticePaths.size +
                " wide=" + wide.latticePaths.size,
            wide.latticePaths.size > narrow.latticePaths.size,
        )
    }

    /**
     * Dictionary character-bigram scoring should prefer a word whose internal
     * bigram is well attested relative to its first character, without any
     * sentence-specific rule. 甲甲 is diluted by many other 甲 words; 乙乙 only
     * occurs in this word.
     */
    @Test
    fun dictionaryBigramWeightReranksIsolatedWord() {
        val lines = arrayOf(
            "aa\t甲甲\t1001",
            "aa\t乙乙\t1000",
            "ba\t甲丙\t5000",
            "ca\t甲丁\t5000",
            "da\t甲戊\t5000",
            "ea\t甲己\t5000",
        )
        val plain = engine(*lines, tuning = PinyinEngine.EngineTuning(dictionaryWordNgramWeight = 0.0))
        val reranked = engine(*lines, tuning = PinyinEngine.EngineTuning(dictionaryWordNgramWeight = 1.0))
        assertEquals("甲甲", plain.search("aa", limit = 2).first().word)
        assertEquals("乙乙", reranked.search("aa", limit = 2).first().word)
    }
}
