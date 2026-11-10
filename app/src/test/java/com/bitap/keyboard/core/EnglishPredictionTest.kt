package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM coverage for the on-device English completion/next-word model. */
class EnglishPredictionTest {

    @Test
    fun seedWordCompletionWorksWithoutLearning() {
        val predictor = EnglishPredictor()
        assertTrue("hello must complete from hel", predictor.suggestions("hel", null).contains("hello"))
        assertTrue("good must complete from go", predictor.suggestions("go", null).contains("good"))
        assertEquals("prefix with no match returns no suggestions", emptyList<String>(), predictor.suggestions("zzzz", null))
    }

    @Test
    fun learnedWordsAreRankedAndPersistInSnapshot() {
        val predictor = EnglishPredictor()
        predictor.recordCommittedWord("bitap", null)
        predictor.recordCommittedWord("bitap", null)
        assertTrue(predictor.suggestions("bit", null).contains("bitap"))

        val restored = EnglishPredictor(predictor.snapshot())
        assertTrue("snapshot restores learned completions", restored.suggestions("bit", null).contains("bitap"))
    }

    @Test
    fun bigramLearningOverridesAndExtendsSeedPairs() {
        val predictor = EnglishPredictor()
        assertEquals(listOf("you"), predictor.suggestions("", "thank"))
        predictor.recordCommittedWord("you", "thank")
        predictor.recordCommittedWord("again", "thank")
        assertEquals(listOf("you", "again"), predictor.suggestions("", "thank"))
        assertEquals(listOf("to"), predictor.suggestions("", "nice"))
    }

    @Test
    fun snapshotRoundTripKeepsCountsAndBigrams() {
        val predictor = EnglishPredictor(
            EnglishPredictionData(
                wordCounts = mapOf("hello" to 3, "world" to 1),
                bigrams = mapOf("hello" to mapOf("world" to 2)),
            ),
        )
        val decoded = EnglishPredictionData.decode(EnglishPredictionData.encode(predictor.snapshot()))
        assertEquals(mapOf("hello" to 3, "world" to 1), decoded.wordCounts)
        assertEquals(mapOf("world" to 2), decoded.bigrams["hello"])
    }

    @Test
    fun clearRemovesLearnedWordsButKeepsSeedCompletion() {
        val predictor = EnglishPredictor()
        predictor.recordCommittedWord("bitap", null)
        predictor.clear()
        assertTrue("seed words survive clear", predictor.suggestions("hel", null).contains("hello"))
        assertTrue("learned words are removed", predictor.snapshot().wordCounts.isEmpty())
    }

    @Test
    fun invalidWordsAreIgnored() {
        val predictor = EnglishPredictor()
        predictor.recordCommittedWord("", null)
        predictor.recordCommittedWord("hello world", null)
        predictor.recordCommittedWord("hello!", null)
        assertTrue(predictor.snapshot().wordCounts.isEmpty())
    }
}
