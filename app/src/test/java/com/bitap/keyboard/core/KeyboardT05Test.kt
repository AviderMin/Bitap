package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.ime.KeyboardController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T05: selection callbacks must not cancel valid AI requests or leave loading stuck. */
class KeyboardT05Test {

    @Test
    fun normalComposingEndAndSamePositionCallbacksDoNotCancelAi() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val ai = T05DelayedAiService()
        val controller = KeyboardController(
            scope = scope,
            textSession = T05TextSession(),
            pinyinEngine = PinyinEngine.fromLines(emptySequence()),
            aiService = ai,
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.onAiRuntimeChanged(AiRuntimeState.Ready)
            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(KeyboardAction.AppendInput("i"))
            controller.dispatch(KeyboardAction.RequestAiCandidates)
            assertTrue(controller.state.value.aiCandidatesLoading)

            // Normal composing-end callback (old caret one char behind).
            controller.onSelectionChanged(2, 2, 0, 2, oldSelectionStart = 1, oldSelectionEnd = 1)
            assertTrue("normal composing-end callback must keep AI loading", controller.state.value.aiCandidatesLoading)
            scheduler.advanceUntilIdle()
            assertFalse(controller.state.value.aiCandidatesLoading)
            assertEquals(listOf("你"), controller.state.value.aiCandidates)

            // Repeated identical callback after completion must not clear results.
            controller.onSelectionChanged(2, 2, 0, 2, oldSelectionStart = 2, oldSelectionEnd = 2)
            assertEquals(listOf("你"), controller.state.value.aiCandidates)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun realMovementOrSelectionChangeCancelsAndClearsLoadingThenRetryWorks() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val ai = T05DelayedAiService()
        val controller = KeyboardController(
            scope = scope,
            textSession = T05TextSession(),
            pinyinEngine = PinyinEngine.fromLines(emptySequence()),
            aiService = ai,
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.onAiRuntimeChanged(AiRuntimeState.Ready)
            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(KeyboardAction.AppendInput("i"))
            controller.dispatch(KeyboardAction.RequestAiCandidates)
            assertTrue(controller.state.value.aiCandidatesLoading)

            controller.onSelectionChanged(5, 5, 0, 2, oldSelectionStart = 2, oldSelectionEnd = 2)
            assertFalse("real move must clear loading", controller.state.value.aiCandidatesLoading)
            assertTrue(controller.state.value.aiCandidates.isEmpty())

            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(KeyboardAction.AppendInput("i"))
            controller.dispatch(KeyboardAction.RequestAiCandidates)
            assertTrue(controller.state.value.aiCandidatesLoading)
            controller.onSelectionChanged(1, 3, 0, 2, oldSelectionStart = 2, oldSelectionEnd = 2)
            assertFalse("selection change must clear loading", controller.state.value.aiCandidatesLoading)

            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(KeyboardAction.AppendInput("i"))
            controller.dispatch(KeyboardAction.RequestAiCandidates)
            scheduler.advanceUntilIdle()
            assertEquals(listOf("你"), controller.state.value.aiCandidates)
        } finally {
            scope.cancel()
        }
    }
}

private class T05TextSession : TextSession {
    override val isActive: Boolean = true
    override fun commitText(text: String) = Unit
    override fun setComposingText(text: String) = Unit
    override fun finishComposingText() = Unit
    override fun deleteBackward() = Unit
    override fun sendEnter() = Unit
    override fun getSelectedText(): String? = null
    override fun getTextBeforeCursor(maxChars: Int): String? = null
    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot = EditorTextSnapshot(null, null)
    override fun readAiSource(maxChars: Int): String = "ni"
}

private class T05DelayedAiService : AiRewriteService {
    override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.Ready)
    override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

    override fun refresh() = Unit
    override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() = Unit
    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = flow { }

    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = flow {
        delay(100)
        emit(AiEvent.Completed("你"))
    }

    override fun cancel() = Unit
    override fun close() = Unit
}
