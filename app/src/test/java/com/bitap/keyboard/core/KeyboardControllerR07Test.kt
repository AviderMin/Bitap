package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.ime.KeyboardController
import com.bitap.keyboard.settings.BitapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R07 regression: AI lexicon requests are invalidated by settings/field/cursor
 * changes, and stale AI candidates can never be submitted.
 */
class KeyboardControllerR07Test {

    @Test
    fun disablingAiDuringDelayedRequestClearsCandidatesAndIgnoresLateCompletion() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = DelayedAiService(delayMs = 50)
        val controller = KeyboardController(scope, R07TextSession(), PinyinEngine.empty(), service, dispatcher)
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(KeyboardAction.RequestAiCandidates)
            assertTrue(controller.state.value.aiCandidatesLoading)

            scheduler.advanceTimeBy(10)
            controller.onSettingsChanged(BitapSettings(aiEnabled = false))
            assertFalse(controller.state.value.aiEnabled)
            assertFalse(controller.state.value.aiCandidatesLoading)
            assertTrue(controller.state.value.aiCandidates.isEmpty())

            scheduler.advanceUntilIdle()
            assertTrue(controller.state.value.aiCandidates.isEmpty())
            assertFalse(controller.state.value.aiCandidatesLoading)
            assertEquals("", controller.state.value.aiCandidatesFor)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun movingCursorDuringRawContextRequestInvalidatesCandidates() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = R07TextSession(beforeCursor = "nihao")
        val service = DelayedAiService(delayMs = 50)
        val controller = KeyboardController(scope, session, PinyinEngine.empty(), service, dispatcher)
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.RequestAiCandidates)
            assertTrue(controller.state.value.aiCandidatesLoading)

            scheduler.advanceTimeBy(10)
            controller.onSelectionChanged(
                selectionStart = 0,
                selectionEnd = 0,
                candidatesStart = -1,
                candidatesEnd = -1,
            )
            scheduler.advanceUntilIdle()

            assertFalse(controller.state.value.aiCandidatesLoading)
            assertTrue(controller.state.value.aiCandidates.isEmpty())
            assertEquals("", controller.state.value.aiCandidatesFor)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aiCandidateTapRequiresCurrentCandidateListAndNonLoadingState() {
        val state = KeyboardUiState(
            composing = "ni",
            aiCandidates = listOf("你好"),
            aiCandidatesFor = "ni",
            aiCandidatesLoading = true,
        )
        val loadingResult = KeyboardReducer.reduce(state, KeyboardAction.AiCandidateTapped("你好"))
        assertTrue(loadingResult.commands.isEmpty())
        assertEquals("ni", loadingResult.state.composing)

        val stale = state.copy(aiCandidatesLoading = false, aiCandidatesFor = "ha")
        val staleResult = KeyboardReducer.reduce(stale, KeyboardAction.AiCandidateTapped("你好"))
        assertTrue(staleResult.commands.isEmpty())

        val unknown = state.copy(aiCandidatesLoading = false, aiCandidates = emptyList())
        val unknownResult = KeyboardReducer.reduce(unknown, KeyboardAction.AiCandidateTapped("你好"))
        assertTrue(unknownResult.commands.isEmpty())

        val accepted = state.copy(aiCandidatesLoading = false)
        val acceptedResult = KeyboardReducer.reduce(accepted, KeyboardAction.AiCandidateTapped("你好"))
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("你好")), acceptedResult.commands)
    }

    @Test
    fun availabilityChangeDisablesAndClearsAiCandidateState() {
        val state = KeyboardUiState(
            aiCandidates = listOf("你好"),
            aiCandidatesFor = "ni",
            aiCandidatesLoading = true,
            aiCandidatesError = "old",
        )
        val result = KeyboardReducer.reduce(
            state,
            KeyboardAction.AiAvailabilityChanged(enabled = false, ready = true),
        )
        assertFalse(result.state.aiEnabled)
        assertTrue(result.state.aiCandidates.isEmpty())
        assertEquals("", result.state.aiCandidatesFor)
        assertFalse(result.state.aiCandidatesLoading)
        assertEquals(null, result.state.aiCandidatesError)
    }
}

private class R07TextSession(
    var beforeCursor: String? = null,
) : TextSession {
    override val isActive: Boolean = true

    override fun commitText(text: String) = Unit

    override fun setComposingText(text: String) = Unit

    override fun finishComposingText() = Unit

    override fun deleteBackward() = Unit

    override fun sendEnter() = Unit

    override fun getSelectedText(): String? = null

    override fun getTextBeforeCursor(maxChars: Int): String? = beforeCursor

    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot = EditorTextSnapshot(null, beforeCursor)

    override fun readAiSource(maxChars: Int): String = (beforeCursor ?: "").take(maxChars)
}

private class DelayedAiService(
    private val delayMs: Long,
) : AiRewriteService {
    override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.Ready)
    override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo(installed = true))

    override fun refresh() = Unit

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() = Unit

    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = emptyFlow()

    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = flow {
        delay(delayMs)
        emit(AiEvent.Completed("天气"))
    }

    override fun cancel() = Unit

    override fun close() = Unit
}
