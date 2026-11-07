package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R03 regression: a confirmation action must never commit raw pinyin or stale
 * candidates while the latest pinyin query is still outstanding. The action is
 * queued and resolved only by the fresh ComposingUpdated result.
 */
class KeyboardReducerR03Test {

    private fun reduce(
        state: KeyboardUiState,
        action: KeyboardAction,
    ): Reduction = KeyboardReducer.reduce(state, action)

    private fun staleState(): KeyboardUiState = KeyboardUiState(
        composing = "nihao",
        candidatesFor = "ni",
        candidates = listOf(Candidate("你", "ni", 100)),
    )

    @Test
    fun spaceWhileQueryPendingQueuesInsteadOfCommittingRawPinyin() {
        val result = reduce(staleState(), KeyboardAction.Space)
        assertTrue("space must wait for fresh candidates", result.commands.isEmpty())
        assertEquals("nihao", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
        assertTrue(result.state.pendingConfirmation is PendingConfirmation.Space)
    }

    @Test
    fun staleCandidateTapWhileQueryPendingQueuesInsteadOfCommitting() {
        val result = reduce(staleState(), KeyboardAction.CandidateTapped(0))
        assertTrue("candidate tap must wait for fresh candidates", result.commands.isEmpty())
        assertEquals("nihao", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
        assertTrue(result.state.pendingConfirmation is PendingConfirmation.Candidate)
    }

    @Test
    fun punctuationWhileQueryPendingQueuesInsteadOfCommittingRawPinyin() {
        val result = reduce(staleState(), KeyboardAction.AppendInput("，"))
        assertTrue("punctuation must wait for fresh candidates", result.commands.isEmpty())
        assertEquals("nihao", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
        assertTrue(result.state.pendingConfirmation is PendingConfirmation.Punctuation)
    }

    @Test
    fun freshQueryResolvesQueuedSpaceWithFirstCandidate() {
        val waiting = reduce(staleState(), KeyboardAction.Space)
        val resolved = reduce(
            waiting.state,
            KeyboardAction.ComposingUpdated(
                "nihao",
                listOf(Candidate("你好", "nihao", 500)),
            ),
        )
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("你好")), resolved.commands)
        assertEquals("", resolved.state.composing)
        assertEquals(null, resolved.state.pendingConfirmation)
    }

    @Test
    fun freshQueryResolvesQueuedCandidateAndPunctuationInOrder() {
        val waitingCandidate = reduce(staleState(), KeyboardAction.CandidateTapped(0))
        val candidateResolved = reduce(
            waitingCandidate.state,
            KeyboardAction.ComposingUpdated(
                "nihao",
                listOf(Candidate("你好", "nihao", 500)),
            ),
        )
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("你好")), candidateResolved.commands)

        val waitingPunctuation = reduce(staleState(), KeyboardAction.AppendInput("，"))
        val punctuationResolved = reduce(
            waitingPunctuation.state,
            KeyboardAction.ComposingUpdated(
                "nihao",
                listOf(Candidate("你好", "nihao", 500)),
            ),
        )
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("你好"), ImeCommand.CommitText("，")),
            punctuationResolved.commands,
        )
        assertEquals("", punctuationResolved.state.composing)
    }

    @Test
    fun explicitCommitRawStillCommitsImmediately() {
        val result = reduce(staleState(), KeyboardAction.CommitRaw)
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("nihao")), result.commands)
        assertEquals("", result.state.composing)
    }

    @Test
    fun typingMoreLettersDoesNotLeaveStaleCandidatesVisible() {
        val result = reduce(
            KeyboardUiState(
                composing = "ni",
                candidatesFor = "ni",
                candidates = listOf(Candidate("你", "ni", 100)),
            ),
            KeyboardAction.AppendInput("h"),
        )
        assertEquals("nih", result.state.composing)
        assertTrue("stale candidates must not remain visible", result.state.candidates.isEmpty())
        assertEquals("", result.state.candidatesFor)
    }
}

/**
 * R04 regression: moving the caret inside a composition (or selecting part of
 * it) must end composition safely without deleting committed text; a caret at
 * the composition end is the normal IME callback and must keep composition.
 */
class KeyboardReducerR04Test {

    private fun composedState(): KeyboardUiState = KeyboardUiState(
        composing = "nihao",
        candidatesFor = "nihao",
        candidates = listOf(Candidate("你好", "nihao", 500)),
    )

    @Test
    fun caretInsideCompositionEndsSafelyWithoutDeleteCommand() {
        val result = KeyboardReducer.reduce(
            composedState(),
            KeyboardAction.SelectionChanged(
                selectionStart = 2,
                selectionEnd = 2,
                candidatesStart = 0,
                candidatesEnd = 5,
            ),
        )
        assertEquals("", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
        assertEquals(listOf<ImeCommand>(ImeCommand.FinishComposing), result.commands)
        assertFalse(result.commands.any { it == ImeCommand.DeleteBackward })
    }

    @Test
    fun selectionRangeInsideCompositionEndsSafely() {
        val result = KeyboardReducer.reduce(
            composedState(),
            KeyboardAction.SelectionChanged(
                selectionStart = 2,
                selectionEnd = 4,
                candidatesStart = 0,
                candidatesEnd = 5,
            ),
        )
        assertEquals("", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
        assertEquals(listOf<ImeCommand>(ImeCommand.FinishComposing), result.commands)
    }

    @Test
    fun caretAtCompositionEndKeepsComposingForNormalImeCallback() {
        val result = KeyboardReducer.reduce(
            composedState(),
            KeyboardAction.SelectionChanged(
                selectionStart = 5,
                selectionEnd = 5,
                candidatesStart = 0,
                candidatesEnd = 5,
            ),
        )
        assertEquals("nihao", result.state.composing)
        assertEquals(1, result.state.candidates.size)
        assertTrue(result.commands.isEmpty())
    }
}
