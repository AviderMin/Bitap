package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.core.engine.InputEngine
import com.bitap.keyboard.core.engine.QueryRequest
import com.bitap.keyboard.core.engine.QueryResult
import com.bitap.keyboard.ime.KeyboardController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * D07: literal English runs typed inside Chinese mode.
 *
 * Rule summary: with an empty composition and Shift active, the first Latin
 * letter starts a literal English run. Letters/digits/underscore/hyphen append
 * literally with the typed case preserved; the run never runs a pinyin query.
 * Space commits the literal plus a space, Enter commits the literal, other
 * punctuation commits the literal then the punctuation, Backspace shrinks the
 * run, and ToggleLanguage commits it exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardMixedInputTest {

    // ------------------------------------------------------------------
    // Reducer state machine
    // ------------------------------------------------------------------

    @Test
    fun android16PreservesCaseAndDigitsFromUiShapedLowercaseKeys() {
        var state = shiftedChinese()
        val commands = mutableListOf<ImeCommand>()
        // LetterLayout always dispatches the lowercase key identity; Shift is
        // applied by the reducer for the first letter only.
        "android16".forEach { char ->
            val result = reduce(state, KeyboardAction.AppendInput(char.toString()))
            commands += result.commands
            state = result.state
        }

        assertEquals("Android16", state.composing)
        assertTrue("the run must be marked literal", KeyboardReducer.isLiteralComposing(state))
        assertTrue("literal runs never expose pinyin candidates", state.candidates.isEmpty())
        assertTrue(
            "every literal key must update the composing text literally",
            commands.all { it is ImeCommand.SetComposingText },
        )
        assertEquals(
            "typed case must not be lowercased",
            listOf(
                "A", "An", "And", "Andr", "Andro", "Androi", "Android",
                "Android1", "Android16",
            ),
            commands.map { (it as ImeCommand.SetComposingText).text },
        )
    }

    @Test
    fun userIdentifierPreservesUnderscoreAndCharacterCase() {
        // A literal run can contain lowercase identifiers; only the first
        // letter is Shift-transformed (user_id is typically continued lowercase).
        var state = literalState("user")
        "user_id".removePrefix("user").forEach { char ->
            state = reduce(state, KeyboardAction.AppendInput(char.toString())).state
        }
        assertEquals("user_id", state.composing)
        assertTrue(KeyboardReducer.isLiteralComposing(state))
    }

    @Test
    fun capsLockUppercasesLiteralLetters() {
        var state = KeyboardUiState(language = InputLanguage.Chinese, shift = ShiftState.Locked)
        "abc".forEach { char ->
            state = reduce(state, KeyboardAction.AppendInput(char.toString())).state
        }
        assertEquals("ABC", state.composing)
        assertTrue(KeyboardReducer.isLiteralComposing(state))
    }

    @Test
    fun hyphenStaysInsideLiteralRun() {
        var state = literalState("v2")
        listOf("-", "b", "e", "t", "a").forEach { char ->
            state = reduce(state, KeyboardAction.AppendInput(char)).state
        }
        assertEquals("v2-beta", state.composing)
        assertTrue(KeyboardReducer.isLiteralComposing(state))
    }

    @Test
    fun literalRunKeepsAppendingDigitsAcrossNumberAndSymbolLayers() {
        var state = literalState("Android")
        state = reduce(state, KeyboardAction.ShowLayer(KeyboardLayer.Numbers)).state
        assertEquals("Android", state.composing)
        assertTrue("layer switch must keep the literal marker", KeyboardReducer.isLiteralComposing(state))

        var result = reduce(state, KeyboardAction.AppendInput("1"))
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText("Android1")), result.commands)
        assertEquals("Android1", result.state.composing)
        assertTrue(KeyboardReducer.isLiteralComposing(result.state))
        state = result.state

        state = reduce(state, KeyboardAction.ShowLayer(KeyboardLayer.Symbols)).state
        result = reduce(state, KeyboardAction.AppendInput("_"))
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText("Android1_")), result.commands)
        assertEquals("Android1_", result.state.composing)
        assertTrue(KeyboardReducer.isLiteralComposing(result.state))
    }

    @Test
    fun spaceCommitsLiteralPlusSpace() {
        val state = literalState("Android16")
        val result = reduce(state, KeyboardAction.Space)

        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("Android16 ")), result.commands)
        assertEquals("", result.state.composing)
        assertFalse(KeyboardReducer.isLiteralComposing(result.state))
        assertTrue(result.state.candidates.isEmpty())
    }

    @Test
    fun enterCommitsLiteralWithoutEditorAction() {
        val state = literalState("Android16")
        val result = reduce(state, KeyboardAction.Enter)

        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("Android16")), result.commands)
        assertEquals("", result.state.composing)
        assertFalse(KeyboardReducer.isLiteralComposing(result.state))
    }

    @Test
    fun punctuationCommitsLiteralThenPunctuation() {
        val state = literalState("abc")
        val result = reduce(state, KeyboardAction.AppendInput("，"))

        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("abc"), ImeCommand.CommitText("，")),
            result.commands,
        )
        assertEquals("", result.state.composing)
        assertFalse(KeyboardReducer.isLiteralComposing(result.state))
    }

    @Test
    fun backspaceShrinksLiteralAndLastBackspaceDeletes() {
        var state = literalState("abc")

        var result = reduce(state, KeyboardAction.Backspace)
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText("ab")), result.commands)
        assertEquals("ab", result.state.composing)
        assertTrue(KeyboardReducer.isLiteralComposing(result.state))
        state = result.state

        result = reduce(state, KeyboardAction.Backspace)
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText("a")), result.commands)
        state = result.state

        result = reduce(state, KeyboardAction.Backspace)
        assertEquals(
            "the last literal character needs finish+delete like pinyin composing",
            listOf<ImeCommand>(ImeCommand.FinishComposing, ImeCommand.DeleteBackward),
            result.commands,
        )
        assertEquals("", result.state.composing)
        assertFalse(KeyboardReducer.isLiteralComposing(result.state))
    }

    @Test
    fun toggleLanguageCommitsLiteralRunExactlyOnce() {
        val state = literalState("abc")
        val result = reduce(state, KeyboardAction.ToggleLanguage)

        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("abc")), result.commands)
        assertEquals(InputLanguage.English, result.state.language)
        assertEquals("", result.state.composing)
        assertFalse(KeyboardReducer.isLiteralComposing(result.state))
    }

    @Test
    fun editorChangeResetsLiteralRunWithoutCommit() {
        val state = literalState("abc")
        val result = reduce(state, KeyboardAction.EditorInfoChanged(EditorInputInfo()))

        assertTrue("switching editors must not duplicate the literal commit", result.commands.isEmpty())
        assertEquals("", result.state.composing)
        assertFalse(KeyboardReducer.isLiteralComposing(result.state))
    }

    @Test
    fun shiftIsRequiredToStartLiteralRun() {
        val result = reduce(KeyboardUiState(), KeyboardAction.AppendInput("a"))
        assertEquals("a", result.state.composing)
        assertFalse("without Shift this is pinyin composing", KeyboardReducer.isLiteralComposing(result.state))
        assertEquals("", result.state.candidatesFor)
    }

    @Test
    fun shiftWithExistingPinyinComposingContinuesPinyinInsteadOfLiteral() {
        val state = KeyboardUiState(composing = "ni")
        var next = reduce(state, KeyboardAction.ShiftTapped(1_000L)).state
        next = reduce(next, KeyboardAction.AppendInput("a")).state
        assertEquals("nia", next.composing)
        assertFalse(KeyboardReducer.isLiteralComposing(next))
    }

    // ------------------------------------------------------------------
    // Controller event matrix
    // ------------------------------------------------------------------

    @Test
    fun pinyinToLiteralEnglishToSpaceToPinyinMatrix() {
        val runtime = ControllerRuntime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            "ni".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            runtime.scheduler.advanceUntilIdle()
            assertEquals(listOf("你"), runtime.controller.state.value.candidates.map { it.word })

            runtime.controller.dispatch(KeyboardAction.CandidateTapped(0))
            assertEquals("你", runtime.session.text)

            runtime.controller.dispatch(KeyboardAction.ShiftTapped(1_000L))
            "android16".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            assertEquals("你Android16", runtime.session.text)
            assertTrue(KeyboardReducer.isLiteralComposing(runtime.controller.state.value))

            runtime.controller.dispatch(KeyboardAction.Space)
            assertEquals("你Android16 ", runtime.session.text)
            assertEquals("", runtime.controller.state.value.composing)

            // After the literal block, Chinese pinyin must work normally again.
            "ni".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            runtime.scheduler.advanceUntilIdle()
            assertEquals("ni", runtime.controller.state.value.composing)
            assertEquals(listOf("你"), runtime.controller.state.value.candidates.map { it.word })
            runtime.controller.dispatch(KeyboardAction.CandidateTapped(0))
            assertEquals("你Android16 你", runtime.session.text)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun literalRunNeverQueriesPinyinEngineAndShowsNoCandidates() {
        val engine = CountingInputEngine()
        val runtime = ControllerRuntime(engine)
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ShiftTapped(1_000L))
            "android16".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            runtime.scheduler.advanceUntilIdle()
            runtime.controller.onPinyinReady()
            runtime.scheduler.advanceUntilIdle()

            assertEquals("literal run must not query pinyin", 0, engine.queryCount)
            assertTrue(runtime.controller.state.value.candidates.isEmpty())
            assertTrue(KeyboardReducer.isLiteralComposing(runtime.controller.state.value))
            assertEquals("Android16", runtime.session.text)

            runtime.controller.dispatch(KeyboardAction.Space)
            assertEquals("Android16 ", runtime.session.text)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun literalBackspaceShrinksEditorTextThroughController() {
        val runtime = ControllerRuntime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ShiftTapped(1_000L))
            "android16".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            assertEquals("Android16", runtime.session.text)

            runtime.controller.dispatch(KeyboardAction.Backspace)
            assertEquals("Android1", runtime.session.text)
            assertEquals("Android1", runtime.controller.state.value.composing)
            assertTrue(KeyboardReducer.isLiteralComposing(runtime.controller.state.value))
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun literalToggleLanguageCommitsEditorTextExactlyOnce() {
        val runtime = ControllerRuntime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ShiftTapped(1_000L))
            "abc".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            assertEquals("Abc", runtime.session.text)

            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)
            assertEquals(InputLanguage.English, runtime.controller.state.value.language)
            assertEquals("Abc", runtime.session.text)
            assertEquals(1, runtime.session.log.count { it == "commit:Abc" })
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun literalRunContinuesThroughNumberAndSymbolLayersInController() {
        val runtime = ControllerRuntime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ShiftTapped(1_000L))
            "android".forEach { runtime.controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            assertEquals("Android", runtime.session.text)

            runtime.controller.dispatch(KeyboardAction.ShowLayer(KeyboardLayer.Numbers))
            runtime.controller.dispatch(KeyboardAction.AppendInput("1"))
            assertEquals("Android1", runtime.session.text)
            assertTrue(KeyboardReducer.isLiteralComposing(runtime.controller.state.value))

            runtime.controller.dispatch(KeyboardAction.ShowLayer(KeyboardLayer.Symbols))
            runtime.controller.dispatch(KeyboardAction.AppendInput("_"))
            assertEquals("Android1_", runtime.session.text)
            assertTrue(KeyboardReducer.isLiteralComposing(runtime.controller.state.value))
            assertTrue(runtime.controller.state.value.candidates.isEmpty())
        } finally {
            runtime.dispose()
        }
    }

    private fun reduce(state: KeyboardUiState, action: KeyboardAction): Reduction =
        KeyboardReducer.reduce(state, action)

    private fun shiftedChinese(): KeyboardUiState =
        reduce(KeyboardUiState(language = InputLanguage.Chinese), KeyboardAction.ShiftTapped(1_000L)).state

    /** A directly constructed literal run; commit/backspace tests start here. */
    private fun literalState(text: String): KeyboardUiState = KeyboardUiState(
        language = InputLanguage.Chinese,
        composing = text,
        candidatesFor = KeyboardReducer.LITERAL_COMPOSING_MARKER,
        compositionVersion = 1L,
    )

    private class ControllerRuntime(engine: InputEngine? = null) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession()
        val controller: KeyboardController

        init {
            controller = if (engine != null) {
                KeyboardController(
                    scope = scope,
                    textSession = session,
                    inputEngine = engine,
                    aiService = MixedAiService(),
                    queryDispatcher = dispatcher,
                )
            } else {
                KeyboardController(
                    scope = scope,
                    textSession = session,
                    pinyinEngine = PinyinEngine.fromLines(sequenceOf("ni\t你\t100")),
                    aiService = MixedAiService(),
                    queryDispatcher = dispatcher,
                )
            }
        }

        fun dispose() {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    private class CountingInputEngine : InputEngine {
        override val engineId: String = "counting-test-engine"
        override val isReady: Boolean = true
        var queryCount: Int = 0
            private set

        override fun query(request: QueryRequest): QueryResult {
            queryCount += 1
            return QueryResult(
                sessionId = request.sessionId,
                compositionVersion = request.compositionVersion,
            )
        }

        override fun close() = Unit
    }

    private class MixedAiService : AiRewriteService {
        override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.NotInstalled)
        override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

        override fun refresh() = Unit

        override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
            Result.failure(UnsupportedOperationException("not used"))

        override fun deleteModel() = Unit
        override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = emptyFlow()
        override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = emptyFlow()
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
