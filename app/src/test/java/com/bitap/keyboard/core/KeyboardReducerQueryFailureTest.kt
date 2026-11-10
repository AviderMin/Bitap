package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-A pure reducer coverage for candidate-query failure recovery.
 *
 * A failed query must never leave a pending confirmation waiting forever:
 * the reducer ends the wait with the raw-text fallback (commitRaw semantics),
 * replays every key the user pressed behind it in order, and drops stale
 * failure events that belong to an older composition/session.
 */
class KeyboardReducerQueryFailureTest {

    private fun reduce(state: KeyboardUiState, action: KeyboardAction): Reduction =
        KeyboardReducer.reduce(state, action)

    private fun waitingState(
        pending: PendingConfirmation = PendingConfirmation.Space,
        queued: List<KeyboardAction> = emptyList(),
    ): KeyboardUiState = KeyboardUiState(
        composing = "nihao",
        compositionVersion = 7L,
        compositionSessionId = "session-A",
        pendingConfirmation = pending,
        pendingComposing = "nihao",
        queuedActions = queued,
    )

    @Test
    fun failedQueryEndsPendingSpaceWithRawCommit() {
        val result = reduce(
            waitingState(),
            KeyboardAction.PinyinQueryFailed(
                composing = "nihao",
                version = 7L,
                sessionId = "session-A",
                message = "engine exploded",
            ),
        )

        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("nihao")), result.commands)
        assertEquals("", result.state.composing)
        assertNull(result.state.pendingConfirmation)
        assertEquals("", result.state.pendingComposing)
        assertTrue(result.state.queuedActions.isEmpty())
        assertEquals(
            KeyboardReducer.CANDIDATE_QUERY_FALLBACK_MESSAGE,
            result.state.candidateQueryError,
        )
    }

    @Test
    fun failedQueryEndsPendingCandidateWithRawCommit() {
        val result = reduce(
            waitingState(pending = PendingConfirmation.Candidate(0)),
            KeyboardAction.PinyinQueryFailed("nihao", 7L, "session-A"),
        )
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("nihao")), result.commands)
        assertNull(result.state.pendingConfirmation)
        assertEquals("", result.state.composing)
    }

    @Test
    fun failedQueryEndsPendingPunctuationWithRawTextPlusPunctuation() {
        val result = reduce(
            waitingState(pending = PendingConfirmation.Punctuation("，")),
            KeyboardAction.PinyinQueryFailed("nihao", 7L, "session-A"),
        )
        assertEquals(
            listOf(
                ImeCommand.CommitText("nihao"),
                ImeCommand.CommitText("，"),
            ),
            result.commands,
        )
        assertNull(result.state.pendingConfirmation)
        assertEquals("", result.state.composing)
        assertEquals(
            KeyboardReducer.CANDIDATE_QUERY_FALLBACK_MESSAGE,
            result.state.candidateQueryError,
        )
    }

    @Test
    fun failedQueryReplaysQueuedBackspaceAfterRawCommit() {
        val result = reduce(
            waitingState(queued = listOf(KeyboardAction.Backspace)),
            KeyboardAction.PinyinQueryFailed("nihao", 7L, "session-A"),
        )

        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("nihao"), ImeCommand.DeleteBackward),
            result.commands,
        )
        assertEquals("", result.state.composing)
        assertNull(result.state.pendingConfirmation)
        assertTrue(result.state.queuedActions.isEmpty())
    }

    @Test
    fun failedQueryReplaysQueuedTypingInOriginalOrder() {
        val result = reduce(
            waitingState(
                queued = listOf(
                    KeyboardAction.AppendInput("m"),
                    KeyboardAction.AppendInput("a"),
                ),
            ),
            KeyboardAction.PinyinQueryFailed("nihao", 7L, "session-A"),
        )

        assertEquals(
            listOf(
                ImeCommand.CommitText("nihao"),
                ImeCommand.SetComposingText("m"),
                ImeCommand.SetComposingText("ma"),
            ),
            result.commands,
        )
        assertEquals("ma", result.state.composing)
        assertNull(result.state.pendingConfirmation)
        assertNull("replayed input clears the failure hint", result.state.candidateQueryError)
    }

    @Test
    fun failedQueryWithoutPendingMarksCompositionResolvedAndLaterSpaceCommitsRaw() {
        val stale = KeyboardUiState(
            composing = "nihao",
            candidatesFor = "",
            candidates = listOf(Candidate("你", "ni", 100)),
            compositionVersion = 3L,
            compositionSessionId = "session-B",
        )

        val failed = reduce(
            stale,
            KeyboardAction.PinyinQueryFailed(
                composing = "nihao",
                version = 3L,
                sessionId = "session-B",
                message = "自定义提示",
            ),
        )
        assertEquals("自定义提示", failed.state.candidateQueryError)
        assertTrue("stale candidates must not survive a failure", failed.state.candidates.isEmpty())
        assertEquals("failure counts as a completed empty query", "nihao", failed.state.candidatesFor)

        val space = reduce(failed.state, KeyboardAction.Space)
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("nihao")), space.commands)
        assertEquals("", space.state.composing)
        assertNull(space.state.candidateQueryError)
    }

    @Test
    fun failureHintClearsOnNextComposingUpdateOrInput() {
        val failed = KeyboardUiState(
            composing = "ni",
            candidatesFor = "ni",
            candidateQueryError = "旧错误",
        )

        val updated = reduce(
            failed,
            KeyboardAction.ComposingUpdated("ni", listOf(Candidate("你", "ni", 100))),
        )
        assertNull(updated.state.candidateQueryError)
        assertEquals(1, updated.state.candidates.size)

        val typed = reduce(failed, KeyboardAction.AppendInput("h"))
        assertEquals("nih", typed.state.composing)
        assertNull(typed.state.candidateQueryError)
    }

    @Test
    fun staleFailureEventsNeverTouchNewInputOrNewPending() {
        val state = KeyboardUiState(
            composing = "n",
            compositionVersion = 5L,
            compositionSessionId = "session-B",
            candidatesFor = "n",
        )

        val wrongComposing = reduce(state, KeyboardAction.PinyinQueryFailed("ni", 5L, "session-B"))
        assertSame("failure for another composing must be dropped", state, wrongComposing.state)
        assertTrue(wrongComposing.commands.isEmpty())

        val wrongVersion = reduce(state, KeyboardAction.PinyinQueryFailed("n", 4L, "session-B"))
        assertSame("failure for an older version must be dropped", state, wrongVersion.state)

        val wrongSession = reduce(state, KeyboardAction.PinyinQueryFailed("n", 5L, "session-A"))
        assertSame("failure from another session must be dropped", state, wrongSession.state)
        assertNull(state.candidateQueryError)

        val newPending = state.copy(
            pendingConfirmation = PendingConfirmation.Space,
            pendingComposing = "n",
        )
        val staleWhilePending = reduce(
            newPending,
            KeyboardAction.PinyinQueryFailed("ni", 5L, "session-B"),
        )
        assertSame("a stale failure must not resolve a new pending confirmation", newPending, staleWhilePending.state)
        assertNotNull(newPending.pendingConfirmation)
    }
}
