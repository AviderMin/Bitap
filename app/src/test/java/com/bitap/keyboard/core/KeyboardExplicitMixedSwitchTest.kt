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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explicit Chinese/English switching is the supported mixed-input path in this
 * batch. Continuous no-switch mixed composition is intentionally out of scope.
 *
 * These tests use a real controller plus an editable TextSession fake so text,
 * cursor, duplicate commits and pending composing are all observable.
 */
class KeyboardExplicitMixedSwitchTest {

    @Test
    fun committedChineseThenEnglishThenChineseKeepsTextAndCursor() {
        val session = TestEditorTextSession()
        val bundle = newController(session)
        try {
            "nihao".forEach { char ->
                bundle.controller.dispatch(KeyboardAction.AppendInput(char.toString()))
            }
            bundle.scheduler.advanceUntilIdle()
            assertEquals("你好", bundle.controller.state.value.candidates.firstOrNull()?.word)

            bundle.controller.dispatch(KeyboardAction.CandidateTapped(0))
            assertEquals("你好", session.text)
            assertEquals(2, session.cursor)
            assertEquals(1, session.log.count { it == "commit:你好" })

            // Explicit switch after a committed candidate must not emit another commit.
            bundle.controller.dispatch(KeyboardAction.ToggleLanguage)
            assertEquals(InputLanguage.English, bundle.controller.state.value.language)
            assertEquals("你好", session.text)
            assertEquals(2, session.cursor)
            assertEquals(1, session.log.count { it == "commit:你好" })

            "ABC".forEach { char ->
                bundle.controller.dispatch(KeyboardAction.AppendInput(char.toString()))
            }
            assertEquals("你好ABC", session.text)
            assertEquals(5, session.cursor)

            bundle.controller.dispatch(KeyboardAction.ToggleLanguage)
            assertEquals(InputLanguage.Chinese, bundle.controller.state.value.language)
            assertEquals("你好ABC", session.text)
            assertEquals(5, session.cursor)

            bundle.controller.dispatch(KeyboardAction.AppendInput("，"))
            assertEquals("你好ABC，", session.text)
            assertEquals(6, session.cursor)

            bundle.controller.dispatch(KeyboardAction.AppendInput(" "))
            assertEquals("你好ABC， ", session.text)
            assertEquals(7, session.cursor)

            // Repeated switches with no composing still must not duplicate text.
            bundle.controller.dispatch(KeyboardAction.ToggleLanguage)
            bundle.controller.dispatch(KeyboardAction.ToggleLanguage)
            assertEquals("你好ABC， ", session.text)
            assertEquals(1, session.log.count { it == "commit:你好" })
        } finally {
            bundle.scope.cancel()
        }
    }

    @Test
    fun switchWithUncommittedComposingCommitsRawExactlyOnce() {
        val session = TestEditorTextSession()
        val bundle = newController(session)
        try {
            "nihao".forEach { char ->
                bundle.controller.dispatch(KeyboardAction.AppendInput(char.toString()))
            }
            assertEquals("nihao", bundle.controller.state.value.composing)

            // Current documented behaviour: ToggleLanguage commits the raw composing text.
            bundle.controller.dispatch(KeyboardAction.ToggleLanguage)
            assertEquals(InputLanguage.English, bundle.controller.state.value.language)
            assertEquals("", bundle.controller.state.value.composing)
            assertEquals("nihao", session.text)
            assertEquals(5, session.cursor)
            assertEquals(1, session.log.count { it == "commit:nihao" })

            bundle.controller.dispatch(KeyboardAction.ToggleLanguage)
            assertEquals(InputLanguage.Chinese, bundle.controller.state.value.language)
            assertEquals("nihao", session.text)
            assertEquals(5, session.cursor)
            assertEquals(1, session.log.count { it == "commit:nihao" })
        } finally {
            bundle.scope.cancel()
        }
    }

    @Test
    fun englishSpacePunctuationAndEnterArePredictableThroughController() {
        val session = TestEditorTextSession()
        val bundle = newController(session)
        try {
            bundle.controller.dispatch(KeyboardAction.ToggleLanguage)
            assertEquals(InputLanguage.English, bundle.controller.state.value.language)

            bundle.controller.dispatch(KeyboardAction.AppendInput(" "))
            bundle.controller.dispatch(KeyboardAction.AppendInput(","))
            bundle.controller.dispatch(KeyboardAction.Enter)

            assertEquals(" ,", session.text)
            assertEquals(2, session.cursor)
            assertEquals(1, session.enterCount)
        } finally {
            bundle.scope.cancel()
        }
    }

    @Test
    fun reducerSpacePunctuationAndEnterStayPredictableAcrossSwitch() {
        // Chinese fresh candidate: Space/punctuation consume the candidate; Enter raw-commits.
        val chinese = KeyboardUiState(
            language = InputLanguage.Chinese,
            composing = "nihao",
            candidatesFor = "nihao",
            candidates = listOf(Candidate("你好", "nihao", 100)),
        )
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("你好")),
            KeyboardReducer.reduce(chinese, KeyboardAction.Space).commands,
        )
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("你好"), ImeCommand.CommitText("，")),
            KeyboardReducer.reduce(chinese, KeyboardAction.AppendInput("，")).commands,
        )
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("nihao")),
            KeyboardReducer.reduce(chinese, KeyboardAction.Enter).commands,
        )

        // English: ordinary commit semantics for space/punctuation/enter.
        val english = KeyboardUiState(language = InputLanguage.English)
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText(" ")),
            KeyboardReducer.reduce(english, KeyboardAction.AppendInput(" ")).commands,
        )
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText(",")),
            KeyboardReducer.reduce(english, KeyboardAction.AppendInput(",")).commands,
        )
        assertEquals(
            listOf<ImeCommand>(ImeCommand.SendEnter),
            KeyboardReducer.reduce(english, KeyboardAction.Enter).commands,
        )

        // Switching back to Chinese with no composing keeps the same predictable path.
        val backToChinese = KeyboardReducer.reduce(english, KeyboardAction.ToggleLanguage).state
        assertEquals(InputLanguage.Chinese, backToChinese.language)
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("，")),
            KeyboardReducer.reduce(backToChinese, KeyboardAction.AppendInput("，")).commands,
        )
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText(" ")),
            KeyboardReducer.reduce(backToChinese, KeyboardAction.AppendInput(" ")).commands,
        )
    }

    private fun newController(session: TestEditorTextSession): ControllerBundle {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = PinyinEngine.fromLines(sequenceOf("nihao	你好	100"))
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            pinyinEngine = engine,
            aiService = NoOpAiService(),
            queryDispatcher = dispatcher,
        )
        controller.onStartInput(EditorInputInfo.from(0, 0))
        controller.onAiRuntimeChanged(AiRuntimeState.Ready)
        return ControllerBundle(scope, controller, scheduler)
    }

    private data class ControllerBundle(
        val scope: CoroutineScope,
        val controller: KeyboardController,
        val scheduler: TestCoroutineScheduler,
    )
}

private class NoOpAiService : AiRewriteService {
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
