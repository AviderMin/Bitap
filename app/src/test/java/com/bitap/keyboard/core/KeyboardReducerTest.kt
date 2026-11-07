package com.bitap.keyboard.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.bitap.keyboard.ai.RewriteTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardReducerTest {

    private fun reduce(
        state: KeyboardUiState = KeyboardUiState(),
        action: KeyboardAction,
    ): Reduction = KeyboardReducer.reduce(state, action)

    @Test
    fun typingLetterBuildsPinyinComposing() {
        val result = reduce(action = KeyboardAction.AppendInput("N"))
        assertEquals("n", result.state.composing)
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText("n")), result.commands)
        assertTrue(result.state.candidates.isEmpty())
    }

    @Test
    fun typingKeepsPreviousCandidatesUntilFreshQueryArrives() {
        val state = KeyboardUiState(
            composing = "ni",
            candidates = listOf(Candidate("你", "ni", 100), Candidate("泥", "ni", 50)),
        )
        val result = reduce(state = state, action = KeyboardAction.AppendInput("h"))
        assertEquals("nih", result.state.composing)
        assertEquals(2, result.state.candidates.size)
        assertEquals("你", result.state.candidates.first().word)
    }

    @Test
    fun composingUpdateIgnoresStaleResultsAndCapsAtEight() {
        val composingState = KeyboardUiState(composing = "ni")
        val stale = reduce(
            state = composingState,
            action = KeyboardAction.ComposingUpdated("nih", listOf(Candidate("你"))),
        )
        assertTrue(stale.state.candidates.isEmpty())

        val many = (1..12).map { Candidate("候$it", "ni", it.toLong()) }
        val fresh = reduce(
            state = composingState,
            action = KeyboardAction.ComposingUpdated("ni", many),
        )
        assertEquals(8, fresh.state.candidates.size)
        assertEquals("候1", fresh.state.candidates.first().word)
    }

    @Test
    fun candidateTapCommitsSelectedWordAndClearsComposing() {
        val state = KeyboardUiState(
            composing = "ni",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你", "ni", 100), Candidate("泥", "ni", 50)),
        )
        val result = reduce(state = state, action = KeyboardAction.CandidateTapped(1))
        assertEquals("泥", (result.commands.single() as ImeCommand.CommitText).text)
        assertEquals("", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
    }

    @Test
    fun spaceCommitsFirstCandidate() {
        val state = KeyboardUiState(
            composing = "nihao",
            candidatesFor = "nihao",
            candidates = listOf(Candidate("你好", "nihao", 725), Candidate("你", "ni", 100)),
        )
        val result = reduce(state = state, action = KeyboardAction.Space)
        assertEquals("你好", (result.commands.single() as ImeCommand.CommitText).text)
        assertEquals("", result.state.composing)
    }

    @Test
    fun punctuationWithStaleCandidatesCommitsRawPinyin() {
        val state = KeyboardUiState(
            composing = "nihao",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你", "ni", 100)),
        )
        val result = reduce(state = state, action = KeyboardAction.AppendInput("，"))
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("nihao"), ImeCommand.CommitText("，")),
            result.commands,
        )
        assertEquals("", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
    }

    @Test
    fun punctuationCommitsCurrentCompositionBeforePunctuation() {
        val state = KeyboardUiState(
            composing = "nihao",
            candidatesFor = "nihao",
            candidates = listOf(Candidate("你好", "nihao", 100)),
        )
        val result = reduce(state = state, action = KeyboardAction.AppendInput("，"))
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("你好"), ImeCommand.CommitText("，")),
            result.commands,
        )
        assertEquals("", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
    }

    @Test
    fun staleCandidatesAreNotUsedBySpace() {
        val state = KeyboardUiState(
            composing = "nihao",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你", "ni", 100)),
        )
        val result = reduce(state = state, action = KeyboardAction.Space)
        assertEquals("nihao", (result.commands.single() as ImeCommand.CommitText).text)
        assertEquals("", result.state.composing)
    }

    @Test
    fun spaceCommitsRawPinyinWhenNoCandidates() {
        val state = KeyboardUiState(composing = "nihao")
        val result = reduce(state = state, action = KeyboardAction.Space)
        assertEquals("nihao", (result.commands.single() as ImeCommand.CommitText).text)
        assertEquals("", result.state.composing)
    }

    @Test
    fun spaceTypesSpaceWhenComposingIsEmpty() {
        val result = reduce(action = KeyboardAction.Space)
        assertEquals(" ", (result.commands.single() as ImeCommand.CommitText).text)
    }

    @Test
    fun backspaceShrinksComposingThenDeletesText() {
        val composed = reduce(
            state = KeyboardUiState(composing = "nih"),
            action = KeyboardAction.Backspace,
        )
        assertEquals("ni", composed.state.composing)
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText("ni")), composed.commands)

        val last = reduce(
            state = KeyboardUiState(composing = "n"),
            action = KeyboardAction.Backspace,
        )
        assertEquals("", last.state.composing)
        assertEquals(
            listOf<ImeCommand>(ImeCommand.FinishComposing, ImeCommand.DeleteBackward),
            last.commands,
        )

        val empty = reduce(action = KeyboardAction.Backspace)
        assertEquals(listOf<ImeCommand>(ImeCommand.DeleteBackward), empty.commands)
    }

    @Test
    fun enterCommitsRawComposingOrSendsEnter() {
        val composing = reduce(
            state = KeyboardUiState(composing = "nihao"),
            action = KeyboardAction.Enter,
        )
        assertEquals("nihao", (composing.commands.single() as ImeCommand.CommitText).text)
        assertEquals("", composing.state.composing)

        val idle = reduce(action = KeyboardAction.Enter)
        assertEquals(listOf<ImeCommand>(ImeCommand.SendEnter), idle.commands)
    }

    @Test
    fun shiftTapDoubleTapLocksAndThirdTapClears() {
        val first = reduce(action = KeyboardAction.ShiftTapped(1_000L))
        assertEquals(ShiftState.Shifted, first.state.shift)

        val second = reduce(state = first.state, action = KeyboardAction.ShiftTapped(1_200L))
        assertEquals(ShiftState.Locked, second.state.shift)

        val third = reduce(state = second.state, action = KeyboardAction.ShiftTapped(3_000L))
        assertEquals(ShiftState.Off, third.state.shift)
    }

    @Test
    fun englishTypingAppliesShiftOnce() {
        val shifted = KeyboardUiState(language = InputLanguage.English, shift = ShiftState.Shifted)
        val result = reduce(state = shifted, action = KeyboardAction.AppendInput("a"))
        assertEquals("A", (result.commands.single() as ImeCommand.CommitText).text)
        assertEquals(ShiftState.Off, result.state.shift)

        val locked = shifted.copy(shift = ShiftState.Locked)
        val lockedResult = reduce(state = locked, action = KeyboardAction.AppendInput("b"))
        assertEquals("B", (lockedResult.commands.single() as ImeCommand.CommitText).text)
        assertEquals(ShiftState.Locked, lockedResult.state.shift)
    }

    @Test
    fun toggleLanguageCommitsComposingAndSwitchesToEnglish() {
        val state = KeyboardUiState(composing = "ni", candidates = listOf(Candidate("你")))
        val result = reduce(state = state, action = KeyboardAction.ToggleLanguage)
        assertEquals(InputLanguage.English, result.state.language)
        assertEquals("ni", (result.commands.single() as ImeCommand.CommitText).text)
        assertEquals("", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
    }

    @Test
    fun symbolLayerCommitsComposingAndTypesSymbolsDirectly() {
        val state = KeyboardUiState(composing = "ni")
        val switched = reduce(state = state, action = KeyboardAction.ShowLayer(KeyboardLayer.Numbers))
        assertEquals(KeyboardLayer.Numbers, switched.state.layer)
        assertEquals("ni", (switched.commands.single() as ImeCommand.CommitText).text)

        val symbol = reduce(state = switched.state, action = KeyboardAction.AppendInput("1"))
        assertEquals("1", (symbol.commands.single() as ImeCommand.CommitText).text)
    }

    @Test
    fun aiPanelShowsUnavailableWhenModelIsNotReady() {
        val result = reduce(action = KeyboardAction.AiPanelOpened("hello"))
        assertTrue(result.state.ai.visible)
        assertEquals(AiStatus.Unavailable, result.state.ai.status)
        assertNotNull(result.state.ai.error)
        assertFalse(result.state.ai.canInsert)
    }

    @Test
    fun aiFlowNeverInsertsUntilExplicitInsert() {
        var state = KeyboardUiState(aiReady = true)
        var result = reduce(state, KeyboardAction.AiPanelOpened("hello world"))
        assertEquals(AiStatus.Idle, result.state.ai.status)
        assertEquals("hello world", result.state.ai.source)

        result = reduce(result.state, KeyboardAction.AiToneChosen(RewriteTone.Polish))
        assertEquals(AiStatus.Thinking, result.state.ai.status)
        assertTrue(result.commands.isEmpty())

        result = reduce(result.state, KeyboardAction.AiDelta("你好"))
        assertEquals(AiStatus.Streaming, result.state.ai.status)
        assertEquals("你好", result.state.ai.result)
        assertTrue(result.commands.isEmpty())

        result = reduce(result.state, KeyboardAction.AiCompleted("你好，世界。"))
        assertEquals(AiStatus.Completed, result.state.ai.status)
        assertTrue(result.state.ai.canInsert)
        assertTrue(result.commands.isEmpty())

        result = reduce(result.state, KeyboardAction.AiInsert)
        assertEquals("你好，世界。", (result.commands.single() as ImeCommand.CommitText).text)
        assertFalse(result.state.ai.visible)
    }

    @Test
    fun aiSourceIsCappedAt512Characters() {
        val longSource = "a".repeat(600)
        val result = reduce(
            state = KeyboardUiState(aiReady = true),
            action = KeyboardAction.AiPanelOpened(longSource),
        )
        assertEquals(KeyboardReducer.MAX_AI_SOURCE_CHARS, result.state.ai.source.length)
    }

    @Test
    fun aiCompletedBlankResultBecomesFailure() {
        val state = KeyboardUiState(
            ai = AiPanelUiState(visible = true, source = "x", status = AiStatus.Streaming),
        )
        val result = reduce(state = state, action = KeyboardAction.AiCompleted("   "))
        assertEquals(AiStatus.Failed, result.state.ai.status)
        assertEquals("这次没想好", result.state.ai.error)
    }

    @Test
    fun aiDismissClearsPanel() {
        val state = KeyboardUiState(
            ai = AiPanelUiState(visible = true, source = "x", status = AiStatus.Completed, result = "y"),
        )
        val result = reduce(state = state, action = KeyboardAction.AiDismiss)
        assertFalse(result.state.ai.visible)
        assertEquals("", result.state.ai.result)
    }

    @Test
    fun hapticsPreferenceIsStoredInState() {
        val result = reduce(action = KeyboardAction.HapticsChanged(false))
        assertFalse(result.state.hapticsEnabled)
    }

    @Test
    fun aiAvailabilityDisablesPanelWhenSettingTurnsOff() {
        val state = KeyboardUiState(
            ai = AiPanelUiState(visible = true, source = "x", status = AiStatus.Completed, result = "y"),
        )
        val result = reduce(
            state = state,
            action = KeyboardAction.AiAvailabilityChanged(enabled = false, ready = false),
        )
        assertFalse(result.state.aiEnabled)
        assertFalse(result.state.ai.visible)
        assertNotEquals(AiStatus.Completed, result.state.ai.status)
    }

    // ------------------------------------------------------------------
    // A1: EditorInfo input types, enter actions and selection sync
    // ------------------------------------------------------------------

    @Test
    fun editorInfoNumberUsesNumberLayerAndEnglish() {
        val info = EditorInputInfo.from(
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            imeOptions = EditorInfo.IME_ACTION_DONE,
        )
        val result = reduce(action = KeyboardAction.EditorInfoChanged(info, aiSettingEnabled = true))
        assertEquals(EditorInputKind.Number, result.state.editorInfo.kind)
        assertEquals(KeyboardLayer.Numbers, result.state.layer)
        assertEquals(InputLanguage.English, result.state.language)
        assertEquals(EnterAction.Done, result.state.enterAction)
        assertEquals("完成", result.state.enterAction.label)
    }

    @Test
    fun editorInfoPhoneUsesNumberLayerAndNextAction() {
        val info = EditorInputInfo.from(
            inputType = InputType.TYPE_CLASS_PHONE,
            imeOptions = EditorInfo.IME_ACTION_NEXT,
        )
        val result = reduce(action = KeyboardAction.EditorInfoChanged(info))
        assertEquals(EditorInputKind.Phone, result.state.editorInfo.kind)
        assertEquals(KeyboardLayer.Numbers, result.state.layer)
        assertEquals(InputLanguage.English, result.state.language)
        assertEquals(EnterAction.Next, result.state.enterAction)
    }

    @Test
    fun editorInfoEmailAndUrlUseLettersEnglish() {
        val email = EditorInputInfo.from(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            imeOptions = EditorInfo.IME_ACTION_SEND,
        )
        val emailResult = reduce(action = KeyboardAction.EditorInfoChanged(email))
        assertEquals(EditorInputKind.Email, emailResult.state.editorInfo.kind)
        assertEquals(KeyboardLayer.Letters, emailResult.state.layer)
        assertEquals(InputLanguage.English, emailResult.state.language)
        assertEquals(EnterAction.Send, emailResult.state.enterAction)

        val url = EditorInputInfo.from(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            imeOptions = EditorInfo.IME_ACTION_GO,
        )
        val urlResult = reduce(action = KeyboardAction.EditorInfoChanged(url))
        assertEquals(EditorInputKind.Url, urlResult.state.editorInfo.kind)
        assertEquals(KeyboardLayer.Letters, urlResult.state.layer)
        assertEquals(InputLanguage.English, urlResult.state.language)
        assertEquals(EnterAction.Go, urlResult.state.enterAction)
    }

    @Test
    fun editorInfoPasswordDisablesAiAndContextHints() {
        val info = EditorInputInfo.from(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            imeOptions = EditorInfo.IME_ACTION_DONE,
        )
        val result = reduce(
            state = KeyboardUiState(aiEnabled = true, aiReady = true, composing = "ni", candidatesFor = "ni"),
            action = KeyboardAction.EditorInfoChanged(info, aiSettingEnabled = true),
        )
        assertTrue(result.state.editorInfo.isPassword)
        assertTrue(result.state.isPasswordInput)
        assertFalse(result.state.aiEnabled)
        assertEquals(KeyboardLayer.Letters, result.state.layer)
        assertEquals(InputLanguage.English, result.state.language)
        assertEquals("", result.state.composing)

        val panel = reduce(state = result.state, action = KeyboardAction.AiPanelOpened("secret"))
        assertFalse(panel.state.ai.visible)
    }

    @Test
    fun numericPasswordUsesNumberLayerAndDisallowsContext() {
        val info = EditorInputInfo.from(
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            imeOptions = EditorInfo.IME_ACTION_NONE,
        )
        assertTrue(info.isPassword)
        assertFalse(info.aiContextAllowed)
        assertEquals(KeyboardLayer.Numbers, info.preferredLayer)
        assertEquals(InputLanguage.English, info.preferredLanguage)
    }

    @Test
    fun nonTextEditorCannotToggleToChinese() {
        val state = KeyboardUiState(
            language = InputLanguage.English,
            editorInfo = EditorInputInfo.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                EditorInfo.IME_ACTION_SEND,
            ),
        )
        val result = reduce(state = state, action = KeyboardAction.ToggleLanguage)
        assertEquals(InputLanguage.English, result.state.language)
        assertTrue(result.commands.isEmpty())
    }

    @Test
    fun editorInfoChangedClearsPreviousInputState() {
        val previous = KeyboardUiState(
            language = InputLanguage.English,
            layer = KeyboardLayer.Numbers,
            composing = "123",
            candidatesFor = "123",
            candidates = listOf(Candidate("一")),
            aiCandidates = listOf("AI候选"),
            aiCandidatesLoading = true,
            aiCandidatesError = "旧错误",
        )
        val result = reduce(
            state = previous,
            action = KeyboardAction.EditorInfoChanged(
                EditorInputInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NONE),
                aiSettingEnabled = true,
            ),
        )
        assertEquals(InputLanguage.Chinese, result.state.language)
        assertEquals(KeyboardLayer.Letters, result.state.layer)
        assertEquals("", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
        assertEquals("", result.state.candidatesFor)
        assertTrue(result.state.aiCandidates.isEmpty())
        assertFalse(result.state.aiCandidatesLoading)
        assertEquals(null, result.state.aiCandidatesError)
    }

    @Test
    fun enterActionMappingsCoverImeOptions() {
        assertEquals(EnterAction.Enter, EditorInputInfo.from(0, 0).enterAction)
        assertEquals(EnterAction.Search, EditorInputInfo.from(0, EditorInfo.IME_ACTION_SEARCH).enterAction)
        assertEquals(EnterAction.Send, EditorInputInfo.from(0, EditorInfo.IME_ACTION_SEND).enterAction)
        assertEquals(EnterAction.Next, EditorInputInfo.from(0, EditorInfo.IME_ACTION_NEXT).enterAction)
        assertEquals(EnterAction.Done, EditorInputInfo.from(0, EditorInfo.IME_ACTION_DONE).enterAction)
        assertEquals(EnterAction.Go, EditorInputInfo.from(0, EditorInfo.IME_ACTION_GO).enterAction)
        assertEquals(EnterAction.Previous, EditorInputInfo.from(0, EditorInfo.IME_ACTION_PREVIOUS).enterAction)
        assertEquals(
            EnterAction.Enter,
            EditorInputInfo.from(
                0,
                EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            ).enterAction,
        )
    }

    @Test
    fun selectionOutsideComposingRegionClearsStaleComposing() {
        val state = KeyboardUiState(
            composing = "ni",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你")),
            aiCandidates = listOf("AI候选"),
        )
        val result = reduce(
            state = state,
            action = KeyboardAction.SelectionChanged(
                selectionStart = 3,
                selectionEnd = 3,
                candidatesStart = 0,
                candidatesEnd = 2,
            ),
        )
        assertEquals("", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
        assertEquals("", result.state.candidatesFor)
        assertTrue(result.state.aiCandidates.isEmpty())
        assertEquals(listOf<ImeCommand>(ImeCommand.FinishComposing), result.commands)
    }

    @Test
    fun selectionInsideComposingRegionKeepsComposing() {
        val state = KeyboardUiState(
            composing = "ni",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你")),
        )
        val result = reduce(
            state = state,
            action = KeyboardAction.SelectionChanged(
                selectionStart = 1,
                selectionEnd = 1,
                candidatesStart = 0,
                candidatesEnd = 2,
            ),
        )
        assertEquals("ni", result.state.composing)
        assertTrue(result.commands.isEmpty())
    }

    @Test
    fun selectionWithNoCandidatesRegionClearsComposing() {
        val state = KeyboardUiState(composing = "ni", candidatesFor = "ni")
        val result = reduce(
            state = state,
            action = KeyboardAction.SelectionChanged(
                selectionStart = 0,
                selectionEnd = 0,
                candidatesStart = -1,
                candidatesEnd = -1,
            ),
        )
        assertEquals("", result.state.composing)
        assertEquals(listOf<ImeCommand>(ImeCommand.FinishComposing), result.commands)
    }

    @Test
    fun aiCandidateRequestIsIgnoredInPasswordField() {
        val state = KeyboardUiState(
            aiEnabled = true,
            editorInfo = EditorInputInfo.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_DONE,
            ),
        )
        val result = reduce(state = state, action = KeyboardAction.RequestAiCandidates)
        assertFalse(result.state.aiCandidatesLoading)
        assertTrue(result.commands.isEmpty())
    }
}
