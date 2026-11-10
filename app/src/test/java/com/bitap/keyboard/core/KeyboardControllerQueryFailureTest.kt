package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.core.engine.EngineCandidate
import com.bitap.keyboard.core.engine.InputEngine
import com.bitap.keyboard.core.engine.QueryRequest
import com.bitap.keyboard.core.engine.QueryResult
import com.bitap.keyboard.ime.KeyboardController
import com.bitap.keyboard.ime.runPinyinQueryCatchingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P0-A end-to-end controller coverage for candidate-query failure recovery.
 *
 * Uses the real [KeyboardController] with a real [TestEditorTextSession] and a
 * throwing [InputEngine]: when the pinyin query fails while Space/Candidate/
 * Punctuation is waiting, the editor must receive the raw composing text, the
 * queued keys must replay in order, and a cancellation must stay a cancellation.
 */
class KeyboardControllerQueryFailureTest {

    @Test
    fun spaceWhileQueryFailsCommitsRawTextAndEndsWaiting() {
        val runtime = Runtime(ThrowingInputEngine(failureFactory = { IllegalStateException("engine exploded") }))
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.controller.dispatch(KeyboardAction.Space)
            assertTrue(
                runtime.controller.state.value.pendingConfirmation is PendingConfirmation.Space,
            )

            runtime.scheduler.advanceUntilIdle()

            assertEquals("the raw pinyin must reach the editor", "ni", runtime.session.text)
            assertEquals("", runtime.controller.state.value.composing)
            assertNull(runtime.controller.state.value.pendingConfirmation)
            assertEquals(
                KeyboardReducer.CANDIDATE_QUERY_FALLBACK_MESSAGE,
                runtime.controller.state.value.candidateQueryError,
            )
            assertTrue(runtime.session.log.contains("commit:ni"))
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun queuedBackspaceAfterFailedSpaceStillDeletesText() {
        val runtime = Runtime(ThrowingInputEngine(failureFactory = { IllegalStateException("boom") }))
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("nihao"))
            runtime.controller.dispatch(KeyboardAction.Space)
            runtime.controller.dispatch(KeyboardAction.Backspace)
            assertTrue(
                runtime.controller.state.value.pendingConfirmation is PendingConfirmation.Space,
            )

            runtime.scheduler.advanceUntilIdle()

            assertEquals("raw commit followed by the queued backspace", "niha", runtime.session.text)
            assertEquals("", runtime.controller.state.value.composing)
            assertNull(runtime.controller.state.value.pendingConfirmation)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun punctuationWhileQueryFailsCommitsRawTextThenPunctuation() {
        val runtime = Runtime(ThrowingInputEngine(failureFactory = { IllegalStateException("boom") }))
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("nihao"))
            runtime.controller.dispatch(KeyboardAction.AppendInput("，"))
            assertTrue(
                runtime.controller.state.value.pendingConfirmation is PendingConfirmation.Punctuation,
            )

            runtime.scheduler.advanceUntilIdle()

            assertEquals("the punctuation must not be dropped", "nihao，", runtime.session.text)
            assertEquals("", runtime.controller.state.value.composing)
            assertNull(runtime.controller.state.value.pendingConfirmation)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun queuedTypingAfterFailedSpaceReplaysAndRequerysNewComposition() {
        val engine = ThrowingInputEngine(
            failureFactory = { IllegalStateException("first query fails") },
            failCalls = 1,
            candidates = { request ->
                listOf(
                    EngineCandidate(
                        word = "好",
                        pinyin = "h",
                        frequency = 10L,
                        consumedInputLength = request.rawInput.length,
                    ),
                )
            },
        )
        val runtime = Runtime(engine)
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.controller.dispatch(KeyboardAction.Space)
            runtime.controller.dispatch(KeyboardAction.AppendInput("h"))

            runtime.scheduler.advanceUntilIdle()

            assertEquals("raw 'ni' then the queued letter", "nih", runtime.session.text)
            assertEquals("h", runtime.controller.state.value.composing)
            assertEquals(listOf("好"), runtime.controller.state.value.candidates.map { it.word })
            assertNull(runtime.controller.state.value.candidateQueryError)
            assertEquals(listOf("ni", "h"), engine.requestedInputs)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun typingAfterQueryFailureRecoversWithFreshQuery() {
        val engine = ThrowingInputEngine(
            failureFactory = { IllegalStateException("first query fails") },
            failCalls = 1,
            candidates = { request ->
                listOf(
                    EngineCandidate(
                        word = "你好",
                        pinyin = request.rawInput,
                        frequency = 100L,
                        consumedInputLength = request.rawInput.length,
                    ),
                )
            },
        )
        val runtime = Runtime(engine)
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()

            assertEquals("ni", runtime.controller.state.value.composing)
            assertNotNull(
                "a failed query must surface a short hint",
                runtime.controller.state.value.candidateQueryError,
            )
            assertEquals("ni", runtime.controller.state.value.candidatesFor)

            runtime.controller.dispatch(KeyboardAction.AppendInput("h"))
            assertNull(
                "new input clears the failure hint",
                runtime.controller.state.value.candidateQueryError,
            )
            runtime.scheduler.advanceUntilIdle()

            assertEquals("nih", runtime.session.text)
            assertEquals("nih", runtime.controller.state.value.composing)
            assertEquals(listOf("你好"), runtime.controller.state.value.candidates.map { it.word })
            assertEquals(listOf("ni", "nih"), engine.requestedInputs)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun cancellationExceptionIsNotReportedAsQueryFailure() {
        val runtime = Runtime(ThrowingInputEngine(failureFactory = { CancellationException("cancelled") }))
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.controller.dispatch(KeyboardAction.Space)

            runtime.scheduler.advanceUntilIdle()

            // The editor still holds the composing region (TestEditorTextSession.text
            // includes it), but no CommitText may have happened.
            assertEquals("ni", runtime.session.text)
            assertTrue(
                "cancellation must never commit raw text",
                runtime.session.log.none { it.startsWith("commit:") },
            )
            assertEquals("ni", runtime.controller.state.value.composing)
            assertTrue(
                "cancellation leaves the confirmation waiting",
                runtime.controller.state.value.pendingConfirmation is PendingConfirmation.Space,
            )
            assertNull(
                "cancellation must not become a user-visible query failure",
                runtime.controller.state.value.candidateQueryError,
            )

            val state = runtime.controller.state.value
            runtime.controller.dispatch(
                KeyboardAction.ComposingUpdated(
                    composing = "ni",
                    candidates = listOf(Candidate("你", "ni", 100)),
                    version = state.compositionVersion,
                    sessionId = state.compositionSessionId,
                ),
            )
            assertEquals("a later real result still resolves the confirmation", "你", runtime.session.text)
            assertNull(runtime.controller.state.value.pendingConfirmation)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun queryFailureCatcherRethrowsCancellationExceptionUnchanged() {
        val cancellation = CancellationException("stop now")
        try {
            runBlocking {
                runPinyinQueryCatchingFailure<Unit> { throw cancellation }
            }
            fail("CancellationException must be rethrown, not swallowed")
        } catch (thrown: CancellationException) {
            assertSame("the original exception instance must propagate", cancellation, thrown)
        }
    }

    @Test
    fun queryFailureCatcherConvertsOtherThrowablesToFailureResult() {
        val boom = IllegalStateException("engine exploded")
        val result = runBlocking {
            runPinyinQueryCatchingFailure<Unit> { throw boom }
        }
        assertTrue(result.isFailure)
        assertSame(boom, result.exceptionOrNull())
    }

    private inner class Runtime(
        engine: InputEngine,
        val session: TestEditorTextSession = TestEditorTextSession(),
    ) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            inputEngine = engine,
            aiService = QueryFailureAiService(),
            queryDispatcher = dispatcher,
        )

        fun dispose() {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }
}

/** Throws for the first [failCalls] queries, then returns mapped candidates. */
private class ThrowingInputEngine(
    private val failureFactory: () -> Throwable,
    private val failCalls: Int = Int.MAX_VALUE,
    private val candidates: (QueryRequest) -> List<EngineCandidate> = { emptyList() },
) : InputEngine {
    val requestedInputs = mutableListOf<String>()
    private var calls = 0

    override val engineId: String = "throwing-query"
    override val isReady: Boolean = true

    override fun query(request: QueryRequest): QueryResult {
        requestedInputs += request.rawInput
        val call = calls++
        if (call < failCalls) throw failureFactory()
        return QueryResult(
            sessionId = request.sessionId,
            compositionVersion = request.compositionVersion,
            candidates = candidates(request),
        )
    }

    override fun close() = Unit
}

private class QueryFailureAiService : AiRewriteService {
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
