package com.bitap.keyboard.core

import com.bitap.keyboard.core.engine.CandidateSource
import com.bitap.keyboard.core.engine.ContextPolicy
import com.bitap.keyboard.core.engine.EngineCandidate
import com.bitap.keyboard.core.engine.InputEngine
import com.bitap.keyboard.core.engine.InputSegment
import com.bitap.keyboard.core.engine.PinyinInputEngine
import com.bitap.keyboard.core.engine.QueryContext
import com.bitap.keyboard.core.engine.QueryRequest
import com.bitap.keyboard.core.engine.SelectionEvent
import com.bitap.keyboard.core.engine.toKeyboardCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D03: the replaceable [InputEngine] contract and the [PinyinInputEngine]
 * adapter must preserve the legacy PinyinEngine behavior exactly.
 */
class InputEngineTest {

    private fun engine(): PinyinEngine = PinyinEngine.fromLines(
        sequenceOf(
            "# Bitap D03 test dictionary",
            "",
            "ni	你	100",
            "ni	泥	50",
            "nihao	你好	80",
            "nihaoma	你好吗	20",
            "hao	好	200",
            "xi	西	300",
            "an	安	300",
            "xian	先	500",
        ),
    )

    private fun request(
        input: String,
        version: Long = 7L,
        limit: Int = InputEngine.DEFAULT_LIMIT,
        sessionId: String = "session-1",
    ): QueryRequest = QueryRequest(
        sessionId = sessionId,
        compositionVersion = version,
        rawInput = input,
        limit = limit,
    )

    @Test
    fun adapterMatchesLegacyEngineForSameInput() {
        val legacy = engine()
        val adapter = PinyinInputEngine(legacy)
        listOf("ni", "nihao", "niha", "nihaoma", "xi'an", "nihap", "zzzz", "").forEach { input ->
            val expected = legacy.search(input)
            val result = adapter.query(request(input))
            assertEquals("session echo for '$input'", "session-1", result.sessionId)
            assertEquals("version echo for '$input'", 7L, result.compositionVersion)
            assertEquals("candidate count for '$input'", expected.size, result.candidates.size)
            expected.indices.forEach { index ->
                val baseline = expected[index]
                val adapted = result.candidates[index]
                assertEquals("word[$index] for '$input'", baseline.word, adapted.word)
                assertEquals("pinyin[$index] for '$input'", baseline.pinyin, adapted.pinyin)
                assertEquals("frequency[$index] for '$input'", baseline.frequency, adapted.frequency)
                assertEquals(
                    "consumedInputLength[$index] for '$input'",
                    baseline.consumedInputLength,
                    adapted.consumedInputLength,
                )
                assertEquals("isCorrection[$index] for '$input'", baseline.isCorrection, adapted.isCorrection)
            }
        }
    }

    @Test
    fun adapterPreservesCorrectionFlagAndMarksCorrectionSource() {
        val adapter = PinyinInputEngine(engine())
        val result = adapter.query(request("nihap"))
        val correction = result.candidates.firstOrNull { it.isCorrection }
        assertNotNull("expected a typo-correction candidate for nihap", correction)
        assertEquals(CandidateSource.Correction, correction!!.source)
        assertTrue(
            "non-correction candidates keep the dictionary source",
            result.candidates.filter { !it.isCorrection }.all { it.source == CandidateSource.Dictionary },
        )
    }

    @Test
    fun adapterRespectsLimitAndKeepsFirstCandidateStable() {
        val adapter = PinyinInputEngine(engine())
        val all = adapter.query(request("ni"))
        val one = adapter.query(request("ni", limit = 1))
        assertTrue(all.candidates.size > 1)
        assertEquals(1, one.candidates.size)
        assertEquals(all.candidates.first().word, one.candidates.first().word)
    }

    @Test
    fun diagnosticsReportCountsAndElapsedNanos() {
        var tick = 0L
        val adapter = PinyinInputEngine(engine(), nanoTime = {
            tick += 1_000L
            tick
        })
        val result = adapter.query(request("ni", limit = 1))
        assertEquals(1, result.candidates.size)
        assertEquals(1, result.diagnostics.returnedCount)
        assertEquals(1, result.diagnostics.recallCount)
        assertEquals(1_000L, result.diagnostics.elapsedNanos)
        assertFalse(result.diagnostics.cancelled)
    }

    @Test
    fun shouldContinueFalseStopsTheEngineWithoutCandidates() {
        val adapter = PinyinInputEngine(engine())
        val result = adapter.query(
            QueryRequest(
                sessionId = "session-1",
                compositionVersion = 1,
                rawInput = "nihao",
                shouldContinue = { false },
            ),
        )
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.diagnostics.cancelled)
    }

    @Test
    fun resultReportsLetterRunSegmentBoundaries() {
        val adapter = PinyinInputEngine(engine())
        assertEquals(
            listOf(InputSegment(0, 2), InputSegment(3, 5)),
            adapter.query(request("xi'an")).segments,
        )
        assertEquals(
            listOf(InputSegment(0, 5)),
            adapter.query(request("NIHAO")).segments,
        )
        assertEquals(
            emptyList<InputSegment>(),
            adapter.query(request("")).segments,
        )
    }

    @Test
    fun restrictedContextIsBoundedAndNeverFullHistory() {
        val context = QueryContext.restricted(
            committedBefore = "a".repeat(200),
            focusedSegment = "b".repeat(200),
        )
        assertEquals(ContextPolicy.Restricted, context.policy)
        assertEquals(QueryContext.MAX_CONTEXT_CHARS, context.committedBefore.length)
        assertEquals(QueryContext.MAX_CONTEXT_CHARS, context.focusedSegment.length)
        assertEquals(ContextPolicy.None, QueryContext.None.policy)
        assertEquals("", QueryContext.None.committedBefore)
        assertEquals("", QueryContext.None.focusedSegment)

        val request = QueryRequest(
            sessionId = "session-1",
            compositionVersion = 1,
            rawInput = "ni",
            context = context,
        )
        assertEquals(ContextPolicy.Restricted, request.contextPolicy)
        assertEquals(ContextPolicy.None, QueryRequest("session-1", 1, "ni").contextPolicy)
    }

    @Test
    fun selectionEventIsForwardedToBaselineLearning() {
        val legacy = engine()
        val adapter = PinyinInputEngine(legacy)
        adapter.recordSelection(
            SelectionEvent(word = "你好", pinyin = "nihao", source = "candidate", consumedInputLength = 5),
        )
        assertEquals(1, legacy.selectionCount("你好"))
        adapter.close()
    }

    @Test
    fun learningLifecycleDelegatesToBaselineEngine() {
        val legacy = engine()
        val adapter = PinyinInputEngine(legacy)
        adapter.setLearningEnabled(false)
        adapter.recordSelection(SelectionEvent(word = "你好", pinyin = "nihao"))
        assertEquals(0, legacy.selectionCount("你好"))

        adapter.setLearningEnabled(true)
        adapter.recordSelection(SelectionEvent(word = "你好", pinyin = "nihao"))
        assertEquals(1, legacy.selectionCount("你好"))

        adapter.clearLearning()
        assertEquals(0, legacy.selectionCount("你好"))
        // No attached store: flush is a safe no-op and must not throw.
        adapter.persistSelectionWeights()
    }

    @Test
    fun engineCandidateMapsToKeyboardCandidateWithVersionAndSession() {
        val engineCandidate = EngineCandidate(
            word = "你好",
            pinyin = "nihao",
            frequency = 80L,
            consumedInputLength = 5,
            isCorrection = false,
        )
        val candidate = engineCandidate.toKeyboardCandidate(compositionVersion = 9L, sessionId = "session-9")
        assertEquals("你好", candidate.word)
        assertEquals("nihao", candidate.pinyin)
        assertEquals(80L, candidate.frequency)
        assertEquals(5, candidate.consumedInputLength)
        assertFalse(candidate.isCorrection)
        assertEquals(9L, candidate.compositionVersion)
        assertEquals("session-9", candidate.sessionId)

        val legacy = engineCandidate.toKeyboardCandidate()
        assertEquals(Candidate.UNVERSIONED, legacy.compositionVersion)
        assertNull(legacy.sessionId)
    }

    @Test
    fun adapterResultCommitsThroughReducerWhenVersionMatches() {
        val adapter = PinyinInputEngine(engine())
        val result = adapter.query(request("nihao", version = 12L, sessionId = "session-12"))
        val state = KeyboardUiState(
            composing = "nihao",
            compositionVersion = 12L,
            compositionSessionId = "session-12",
        )
        val updated = KeyboardReducer.reduce(
            state,
            KeyboardAction.ComposingUpdated(
                composing = "nihao",
                candidates = result.candidates.map {
                    it.toKeyboardCandidate(result.compositionVersion, result.sessionId)
                },
                version = result.compositionVersion,
                sessionId = result.sessionId,
            ),
        )
        val index = updated.state.candidates.indexOfFirst { it.word == "你好" }
        assertTrue("expected 你好 in adapter result", index >= 0)
        val committed = KeyboardReducer.reduce(updated.state, KeyboardAction.CandidateTapped(index))
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("你好")), committed.commands)
        assertEquals("", committed.state.composing)
    }

    @Test
    fun emptyEngineIsNotReadyAndReturnsNoCandidates() {
        val adapter = PinyinInputEngine(PinyinEngine.empty())
        assertFalse(adapter.isReady)
        assertEquals(PinyinInputEngine.ENGINE_ID, adapter.engineId)
        assertTrue(adapter.query(request("ni")).candidates.isEmpty())
    }
}
