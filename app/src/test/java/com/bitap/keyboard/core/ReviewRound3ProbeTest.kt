package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Copies of the two round-3 probes that belong to the core T01/T03 fixes.
 * The third round-3 probe (T04 raw-context whitespace) belongs to ai-dev/task-38
 * and is intentionally not duplicated here.
 */
class ReviewRound3ProbeTest {

    @Test
    fun secondPendingKeepsItsQueuedSuffix() {
        var state = KeyboardUiState(composing = "nihao")
        for (action in listOf(
            KeyboardAction.Space,
            KeyboardAction.AppendInput("m"),
            KeyboardAction.AppendInput("a"),
            KeyboardAction.Space,
            KeyboardAction.AppendInput("x"),
        )) state = KeyboardReducer.reduce(state, action).state
        val result = KeyboardReducer.reduce(
            state,
            KeyboardAction.ComposingUpdated(
                "nihao",
                listOf(Candidate("你好", "nihao", 100)),
            ),
        )
        assertEquals("ma", result.state.composing)
        assertNotNull(result.state.pendingConfirmation)
        assertEquals(listOf(KeyboardAction.AppendInput("x")), result.state.queuedActions)
    }

    @Test
    fun separatedPartialCandidateHasOnlyItsOwnPronunciation() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "xi\t西\t100",
                "an\t安\t100",
                "xian\t西安\t1000",
            ),
        )
        val partial = engine.search("xi'an").first { it.word == "西" }
        assertEquals(2, partial.consumedInputLength)
        assertEquals("xi", partial.pinyin)
    }
}
