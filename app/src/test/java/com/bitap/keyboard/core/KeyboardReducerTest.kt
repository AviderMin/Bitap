package com.bitap.keyboard.core

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
            candidates = listOf(Candidate("你好", "nihao", 725), Candidate("你", "ni", 100)),
        )
        val result = reduce(state = state, action = KeyboardAction.Space)
        assertEquals("你好", (result.commands.single() as ImeCommand.CommitText).text)
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
        assertEquals(listOf<ImeCommand>(ImeCommand.FinishComposing), last.commands)

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
}
