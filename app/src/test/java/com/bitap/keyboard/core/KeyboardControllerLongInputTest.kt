package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.core.engine.EngineCandidate
import com.bitap.keyboard.core.engine.InputEngine
import com.bitap.keyboard.core.engine.PinyinInputEngine
import com.bitap.keyboard.core.engine.QueryRequest
import com.bitap.keyboard.core.engine.QueryResult
import com.bitap.keyboard.ime.KeyboardController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 long-input regression coverage for the 64-character engine budget.
 *
 * The controller must keep the FULL composing text in UI state (so deletion and
 * commit-raw stay reliable) while sending only the engine-declared prefix
 * ([InputEngine.queryInputFor]) to [InputEngine.query]. A candidate's
 * [EngineCandidate.consumedInputLength] is an offset into that queried prefix;
 * after it consumes the prefix the unconsumed tail remains composing and is
 * queried again in segments.
 */
class KeyboardControllerLongInputTest {

    @Test
    fun overBudgetComposingQueriesOnlyPrefixAndKeepsFullState() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = RecordingLongInputEngine(maxQueryInputLength = 64)
        val controller = KeyboardController(
            scope = scope,
            textSession = TestEditorTextSession(),
            inputEngine = engine,
            aiService = LongInputAiService(),
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            val composing = "a".repeat(70)
            controller.dispatch(KeyboardAction.AppendInput(composing))
            scheduler.advanceUntilIdle()

            assertEquals("UI state must keep the full composition", 70, controller.state.value.composing.length)
            assertEquals("engine must receive exactly the 64-char prefix", listOf("a".repeat(64)), engine.requestedInputs)
            assertEquals(64, engine.requestedInputs.last().length)
            assertEquals(listOf("你好"), controller.state.value.candidates.map { it.word })
            assertEquals("result must still be attributed to the full composition", composing, controller.state.value.candidatesFor)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun candidateConsumingPrefixLeavesTailComposingAndCommitsWord() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = RecordingLongInputEngine(maxQueryInputLength = 64)
        val session = TestEditorTextSession()
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            inputEngine = engine,
            aiService = LongInputAiService(),
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("a".repeat(70)))
            scheduler.advanceUntilIdle()
            assertEquals(1, engine.requestedInputs.size) // only the initial query so far

            controller.dispatch(KeyboardAction.CandidateTapped(0))
            val stateAfterTap = controller.state.value
            assertEquals("5 consumed characters leave 65 composing", 65, stateAfterTap.composing.length)
            assertEquals("a".repeat(65), stateAfterTap.composing)
            assertTrue("candidate word must be committed", session.log.contains("commit:你好"))

            // The controller re-queries the remaining tail after the tap; let it
            // settle and assert final state/text rather than assuming no call.
            scheduler.advanceUntilIdle()
            assertEquals(65, controller.state.value.composing.length)
            assertEquals("你好" + "a".repeat(65), session.text)
            assertEquals("tail query is again bounded to 64 chars", "a".repeat(64), engine.requestedInputs.last())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun legacyZeroConsumptionMeansQueriedPrefixOnBudgetedEngine() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = RecordingLongInputEngine(maxQueryInputLength = 64, consumedInputLength = 0)
        val session = TestEditorTextSession()
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            inputEngine = engine,
            aiService = LongInputAiService(),
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("a".repeat(70)))
            scheduler.advanceUntilIdle()

            controller.dispatch(KeyboardAction.CandidateTapped(0))
            // The legacy 0 convention means "whole queried composition" (64),
            // never the unqueried 6-char tail.
            assertEquals("a".repeat(6), controller.state.value.composing)
            assertEquals("你好" + "a".repeat(6), session.text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun backspaceFromOverBudgetComposingStaysExact() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = RecordingLongInputEngine(maxQueryInputLength = 64)
        val session = TestEditorTextSession()
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            inputEngine = engine,
            aiService = LongInputAiService(),
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("a".repeat(70)))
            scheduler.advanceUntilIdle()

            // Delete down to exactly the 64-character budget first.
            repeat(6) {
                controller.dispatch(KeyboardAction.Backspace)
                scheduler.advanceUntilIdle()
            }
            assertEquals("a".repeat(64), controller.state.value.composing)
            assertEquals("a".repeat(64), session.text)
            assertEquals("a".repeat(64), engine.requestedInputs.last())

            // Crossing the budget boundary must stay exact too.
            controller.dispatch(KeyboardAction.Backspace)
            scheduler.advanceUntilIdle()
            assertEquals("a".repeat(63), controller.state.value.composing)
            assertEquals("a".repeat(63), session.text)
            assertEquals("a".repeat(63), engine.requestedInputs.last())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun commitRawStillCommitsFullOverBudgetComposing() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = RecordingLongInputEngine(maxQueryInputLength = 64)
        val session = TestEditorTextSession()
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            inputEngine = engine,
            aiService = LongInputAiService(),
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            val composing = "a".repeat(70)
            controller.dispatch(KeyboardAction.AppendInput(composing))
            scheduler.advanceUntilIdle()

            controller.dispatch(KeyboardAction.CommitRaw)
            assertEquals("", controller.state.value.composing)
            assertEquals("commit-raw must use the full text, not the query prefix", composing, session.text)
            assertTrue(session.log.contains("commit:" + composing))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun engineWithoutBudgetStillReceivesFullComposing() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = RecordingLongInputEngine() // default maxQueryInputLength = Int.MAX_VALUE
        val controller = KeyboardController(
            scope = scope,
            textSession = TestEditorTextSession(),
            inputEngine = engine,
            aiService = LongInputAiService(),
            queryDispatcher = dispatcher,
        )
        try {
            val composing = "a".repeat(70)
            assertEquals(Int.MAX_VALUE, engine.maxQueryInputLength)
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput(composing))
            scheduler.advanceUntilIdle()

            assertEquals("default engines keep the pre-existing full-input contract", composing, engine.requestedInputs.single())
            assertEquals(70, controller.state.value.composing.length)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun queryInputForBoundariesRespectEngineBudget() {
        val length64 = "a".repeat(64)
        val length65 = "a".repeat(65)
        val length70 = "a".repeat(70)

        val defaultEngine = RecordingLongInputEngine()
        assertEquals(Int.MAX_VALUE, defaultEngine.maxQueryInputLength)
        assertEquals(length64, defaultEngine.queryInputFor(length64))
        assertEquals(length65, defaultEngine.queryInputFor(length65))
        assertEquals(length70, defaultEngine.queryInputFor(length70))

        val budgetFive = RecordingLongInputEngine(maxQueryInputLength = 5)
        assertEquals("abcde", budgetFive.queryInputFor("abcde"))
        assertEquals("abcde", budgetFive.queryInputFor("abcdefg"))

        val pinyin = PinyinInputEngine(PinyinEngine.empty())
        assertEquals(PinyinEngine.MAX_SEARCH_INPUT_LENGTH, pinyin.maxQueryInputLength)
        assertEquals(64, pinyin.maxQueryInputLength)
        assertEquals(length64, pinyin.queryInputFor(length64))
        assertEquals(length64, pinyin.queryInputFor(length65))
        assertEquals(length64, pinyin.queryInputFor(length70))
    }

    @Test
    fun pinyinEngineHardGuardRemainsForDirectCallers() {
        val adapter = PinyinInputEngine(
            PinyinEngine.fromLines(sequenceOf("a	啊	100")),
        )
        val overBudget = QueryRequest(
            sessionId = "session-1",
            compositionVersion = 1L,
            rawInput = "a".repeat(65),
        )
        assertTrue(
            "the engine-side 64-char hard guard must stay unchanged",
            adapter.query(overBudget).candidates.isEmpty(),
        )
        assertEquals(64, adapter.maxQueryInputLength)
    }
}

/** Records every query input; returns one candidate that consumes 5 chars. */
private class RecordingLongInputEngine(
    override val maxQueryInputLength: Int = Int.MAX_VALUE,
    private val consumedInputLength: Int = 5,
) : InputEngine {
    val requestedInputs = mutableListOf<String>()

    override val engineId: String = "recording-long-input"
    override val isReady: Boolean = true

    override fun query(request: QueryRequest): QueryResult {
        requestedInputs += request.rawInput
        return QueryResult(
            sessionId = request.sessionId,
            compositionVersion = request.compositionVersion,
            candidates = listOf(
                EngineCandidate(
                    word = "你好",
                    pinyin = request.rawInput.take(5),
                    frequency = 100L,
                    consumedInputLength = consumedInputLength,
                ),
            ),
        )
    }

    override fun close() = Unit
}

private class LongInputAiService : AiRewriteService {
    override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.NotInstalled)
    override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

    override fun refresh() = Unit

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() = Unit

    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = emptyFlow()

    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = emptyFlow()

    override fun cancel() = Unit

    override fun close() = Unit
}
