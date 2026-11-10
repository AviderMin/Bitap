package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.ime.KeyboardController
import com.bitap.keyboard.ime.candidatePanelMaxHeightDp
import com.bitap.keyboard.ime.candidatePanelPageSize
import com.bitap.keyboard.ime.usesCandidatePanelOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 regression coverage for the device pre-check blockers:
 * 1) a second ✦ AI tap while loading must cancel (not restart) and late results
 *    must never be committed; retry must work;
 * 2) compact/landscape expanded candidate panel policy uses a fixed-height
 *    overlay instead of growing the keyboard;
 * 3) Candidate.isCorrection survives the reducer path for the UI 纠错 marker.
 */
class KeyboardP0InputFixTest {

    @Test
    fun secondAiRequestWhileLoadingCancelsInsteadOfRestarting() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = CountingAiService(delayMs = 50)
        val controller = KeyboardController(
            scope = scope,
            textSession = P0TextSession(),
            pinyinEngine = PinyinEngine.empty(),
            aiService = service,
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(KeyboardAction.RequestAiCandidates)
            scheduler.runCurrent()
            assertTrue(controller.state.value.aiCandidatesLoading)
            assertEquals(1, service.suggestCalls)

            // Second tap on ✦ AI while loading is a cancel, not a new request.
            controller.dispatch(KeyboardAction.RequestAiCandidates)
            assertFalse("cancel must clear loading immediately", controller.state.value.aiCandidatesLoading)
            assertTrue(controller.state.value.aiCandidates.isEmpty())
            assertEquals("must not start another request", 1, service.suggestCalls)

            scheduler.advanceUntilIdle()
            assertTrue("late result must not land after cancel", controller.state.value.aiCandidates.isEmpty())
            assertFalse(controller.state.value.aiCandidatesLoading)
            assertEquals(1, service.suggestCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aiCancelWhileLoadingClearsImmediatelyIgnoresLateResultAndAllowsRetry() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = CountingAiService(delayMs = 50)
        val controller = KeyboardController(
            scope = scope,
            textSession = P0TextSession(),
            pinyinEngine = PinyinEngine.empty(),
            aiService = service,
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(KeyboardAction.RequestAiCandidates)
            scheduler.runCurrent()
            assertTrue(controller.state.value.aiCandidatesLoading)
            assertEquals(1, service.suggestCalls)

            controller.dispatch(KeyboardAction.AiCancel)
            assertFalse(controller.state.value.aiCandidatesLoading)
            assertTrue(controller.state.value.aiCandidates.isEmpty())
            assertNull(controller.state.value.aiCandidatesError)

            scheduler.advanceUntilIdle()
            assertTrue("cancelled request must not submit old candidates", controller.state.value.aiCandidates.isEmpty())
            assertFalse(controller.state.value.aiCandidatesLoading)

            // After cancel the same entry can request again and the new result lands.
            controller.dispatch(KeyboardAction.RequestAiCandidates)
            scheduler.runCurrent()
            assertTrue(controller.state.value.aiCandidatesLoading)
            assertEquals(2, service.suggestCalls)
            scheduler.advanceUntilIdle()
            assertEquals(listOf("新候选"), controller.state.value.aiCandidates)
            assertFalse(controller.state.value.aiCandidatesLoading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reducerAiCancelClearsCandidateRequestStateEvenWithoutPanel() {
        val state = KeyboardUiState(
            composing = "ni",
            candidates = listOf(Candidate("你", "ni", 100)),
            aiCandidates = listOf("旧候选"),
            aiCandidatesFor = "ni",
            aiCandidatesLoading = true,
            aiCandidatesError = "旧错误",
        )
        val result = KeyboardReducer.reduce(state, KeyboardAction.AiCancel)
        assertTrue(result.state.aiCandidates.isEmpty())
        assertEquals("", result.state.aiCandidatesFor)
        assertFalse(result.state.aiCandidatesLoading)
        assertNull(result.state.aiCandidatesError)
    }

    @Test
    fun correctionCandidateFlagDefaultsFalseAndSurvivesCommit() {
        assertFalse(Candidate("你").isCorrection)

        val correction = Candidate(
            word = "拟",
            pinyin = "ni",
            frequency = 10,
            consumedInputLength = 2,
            isCorrection = true,
        )
        assertTrue(correction.isCorrection)

        val state = KeyboardUiState(
            composing = "ni",
            candidatesFor = "ni",
            candidates = listOf(correction),
        )
        val result = KeyboardReducer.reduce(state, KeyboardAction.CandidateTapped(0))
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("拟")),
            result.commands,
        )
    }

    @Test
    fun compactCandidatePanelUsesFixedHeightOverlayPolicy() {
        assertTrue(usesCandidatePanelOverlay(compact = true))
        assertFalse(usesCandidatePanelOverlay(compact = false))
        // Flow geometry: page capacity is 8 compact / 12 portrait; the height
        // budget still fits two/four visible rows plus the fixed 48 dp footer.
        assertEquals(8, candidatePanelPageSize(compact = true))
        assertEquals(12, candidatePanelPageSize(compact = false))
        assertEquals(164, candidatePanelMaxHeightDp(compact = true))
        assertEquals(264, candidatePanelMaxHeightDp(compact = false))
    }
}

private class CountingAiService(
    private val delayMs: Long,
) : AiRewriteService {
    var suggestCalls = 0

    override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.Ready)
    override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo(installed = true))

    override fun refresh() = Unit

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() = Unit

    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = emptyFlow()

    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = flow {
        val call = ++suggestCalls
        delay(delayMs)
        emit(AiEvent.Completed(if (call == 1) "旧候选" else "新候选"))
    }

    override fun cancel() = Unit

    override fun close() = Unit
}

private class P0TextSession : TextSession {
    override val isActive: Boolean = true

    override fun commitText(text: String) = Unit

    override fun setComposingText(text: String) = Unit

    override fun finishComposingText() = Unit

    override fun deleteBackward() = Unit

    override fun sendEnter() = Unit

    override fun getSelectedText(): String? = null

    override fun getTextBeforeCursor(maxChars: Int): String? = null

    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot = EditorTextSnapshot(null, null)

    override fun readAiSource(maxChars: Int): String = ""
}
