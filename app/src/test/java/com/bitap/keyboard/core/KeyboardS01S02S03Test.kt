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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S01: layer switching must preserve valid candidates and requery same composing. */
class KeyboardS01Test {

    private fun reduce(
        state: KeyboardUiState,
        action: KeyboardAction,
    ): Reduction = KeyboardReducer.reduce(state, action)

    @Test
    fun showLayerPreservesFreshCandidatesForSameComposing() {
        val state = KeyboardUiState(
            composing = "nihao",
            candidatesFor = "nihao",
            candidates = listOf(Candidate("你好", "nihao", 500)),
        )
        val result = reduce(state, KeyboardAction.ShowLayer(KeyboardLayer.Numbers))
        assertEquals(KeyboardLayer.Numbers, result.state.layer)
        assertEquals("nihao", result.state.candidatesFor)
        assertEquals(1, result.state.candidates.size)
    }

    @Test
    fun controllerCanConfirmAfterLayerSwitchWithoutDeletingOrWaiting() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = S01TextSession()
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            pinyinEngine = PinyinEngine.fromLines(sequenceOf("nihao\t你好\t500")),
            aiService = S01AiService(),
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            "nihao".forEach { char -> controller.dispatch(KeyboardAction.AppendInput(char.toString())) }
            scheduler.advanceUntilIdle()
            assertTrue(controller.state.value.candidates.isNotEmpty())

            controller.dispatch(KeyboardAction.ShowLayer(KeyboardLayer.Numbers))
            assertTrue(controller.state.value.candidates.isNotEmpty())
            controller.dispatch(KeyboardAction.Space)

            assertEquals("commit:你好", session.log.last())
            assertEquals("", controller.state.value.composing)
        } finally {
            scope.cancel()
        }
    }
}

/** S02: pending confirmation freezes its target and later keys replay in order. */
class KeyboardS02Test {

    private fun reduce(
        state: KeyboardUiState,
        action: KeyboardAction,
    ): Reduction = KeyboardReducer.reduce(state, action)

    private fun stalePendingState(): KeyboardUiState = KeyboardUiState(
        composing = "nihao",
        candidatesFor = "ni",
        candidates = listOf(Candidate("你", "ni", 100)),
    )

    @Test
    fun pendingSpaceDoesNotConsumeLaterLettersAndReplaysThem() {
        var state = reduce(stalePendingState(), KeyboardAction.Space).state
        assertNotNull(state.pendingConfirmation)
        assertEquals("nihao", state.composing)

        state = reduce(state, KeyboardAction.AppendInput("m")).state
        state = reduce(state, KeyboardAction.AppendInput("a")).state
        assertEquals("nihao", state.composing)
        assertEquals(2, state.queuedActions.size)

        val resolved = reduce(
            state,
            KeyboardAction.ComposingUpdated("nihao", listOf(Candidate("你好", "nihao", 500))),
        )
        assertEquals("ma", resolved.state.composing)
        assertEquals(
            listOf(
                ImeCommand.CommitText("你好"),
                ImeCommand.SetComposingText("m"),
                ImeCommand.SetComposingText("ma"),
            ),
            resolved.commands,
        )
        assertEquals(null, resolved.state.pendingConfirmation)
        assertTrue(resolved.state.queuedActions.isEmpty())
    }

    @Test
    fun secondPunctuationQueuesAndCommitsAfterFirst() {
        var state = reduce(stalePendingState(), KeyboardAction.AppendInput("，")).state
        state = reduce(state, KeyboardAction.AppendInput("。")).state
        assertEquals(1, state.queuedActions.size)

        val resolved = reduce(
            state,
            KeyboardAction.ComposingUpdated("nihao", listOf(Candidate("你好", "nihao", 500))),
        )
        assertEquals(
            listOf(
                ImeCommand.CommitText("你好"),
                ImeCommand.CommitText("，"),
                ImeCommand.CommitText("。"),
            ),
            resolved.commands,
        )
        assertEquals("", resolved.state.composing)
    }

    @Test
    fun backspaceAndEnterReceivedWhilePendingReplayAfterFrontConfirmation() {
        var state = reduce(stalePendingState(), KeyboardAction.Space).state
        state = reduce(state, KeyboardAction.Backspace).state
        state = reduce(state, KeyboardAction.Enter).state

        val resolved = reduce(
            state,
            KeyboardAction.ComposingUpdated("nihao", listOf(Candidate("你好", "nihao", 500))),
        )
        assertEquals(
            listOf(
                ImeCommand.CommitText("你好"),
                ImeCommand.DeleteBackward,
                ImeCommand.SendEnter,
            ),
            resolved.commands,
        )
    }
}

/** S03: partial candidates must not swallow the rest of composing on punctuation. */
class KeyboardS03Test {

    private fun reduce(
        state: KeyboardUiState,
        action: KeyboardAction,
    ): Reduction = KeyboardReducer.reduce(state, action)

    private fun partialState(): KeyboardUiState = KeyboardUiState(
        composing = "nihaox",
        candidatesFor = "nihaox",
        candidates = listOf(Candidate("你好", "nihao", 500, consumedInputLength = 5)),
    )

    @Test
    fun punctuationRespectsPartialCandidateConsumption() {
        val result = reduce(partialState(), KeyboardAction.AppendInput("，"))
        assertEquals(
            listOf(
                ImeCommand.CommitText("你好"),
                ImeCommand.CommitText("，"),
                ImeCommand.SetComposingText("x"),
            ),
            result.commands,
        )
        assertEquals("x", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
    }

    @Test
    fun pendingPunctuationUsesFreshPartialCandidateWithoutDroppingSuffix() {
        val waiting = reduce(
            partialState().copy(candidatesFor = "nihao"),
            KeyboardAction.AppendInput("，"),
        )
        assertTrue(waiting.commands.isEmpty())
        assertNotNull(waiting.state.pendingConfirmation)

        val resolved = reduce(
            waiting.state,
            KeyboardAction.ComposingUpdated(
                "nihaox",
                listOf(Candidate("你好", "nihao", 500, consumedInputLength = 5)),
            ),
        )
        assertEquals(
            listOf(
                ImeCommand.CommitText("你好"),
                ImeCommand.CommitText("，"),
                ImeCommand.SetComposingText("x"),
            ),
            resolved.commands,
        )
        assertEquals("x", resolved.state.composing)
    }

    @Test
    fun spaceOnPartialCandidateKeepsSuffixComposing() {
        val result = reduce(partialState(), KeyboardAction.Space)
        assertEquals(
            listOf(ImeCommand.CommitText("你好"), ImeCommand.SetComposingText("x")),
            result.commands,
        )
        assertEquals("x", result.state.composing)
    }
}

private class S01TextSession : TextSession {
    val log = mutableListOf<String>()

    override val isActive: Boolean = true

    override fun commitText(text: String) {
        log += "commit:" + text
    }

    override fun setComposingText(text: String) = Unit

    override fun finishComposingText() = Unit

    override fun deleteBackward() = Unit

    override fun sendEnter() = Unit

    override fun getSelectedText(): String? = null

    override fun getTextBeforeCursor(maxChars: Int): String? = null

    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot = EditorTextSnapshot(null, null)

    override fun readAiSource(maxChars: Int): String = ""
}

private class S01AiService : AiRewriteService {
    override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.Ready)
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
