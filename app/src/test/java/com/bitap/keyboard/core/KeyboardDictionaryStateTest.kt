package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-B pure reducer coverage for the observable dictionary load lifecycle.
 *
 * The dictionary state is orthogonal to composition/query state: load events
 * never resolve a pending confirmation or drop candidates, and a failed
 * dictionary must not block committing the raw composing text.
 */
class KeyboardDictionaryStateTest {

    private fun reduce(state: KeyboardUiState, action: KeyboardAction): Reduction =
        KeyboardReducer.reduce(state, action)

    @Test
    fun newStateStartsInLoading() {
        val state = KeyboardUiState()
        assertEquals(DictionaryLoadState.Loading, state.dictionaryState)
        assertNull(state.dictionaryError)
    }

    @Test
    fun dictionaryStateChangedMovesLoadingReadyFailed() {
        val loading = KeyboardUiState(dictionaryState = DictionaryLoadState.Loading)
        val ready = reduce(
            loading,
            KeyboardAction.DictionaryStateChanged(DictionaryLoadState.Ready),
        )
        assertEquals(DictionaryLoadState.Ready, ready.state.dictionaryState)
        assertNull(ready.state.dictionaryError)

        val failed = reduce(
            ready.state,
            KeyboardAction.DictionaryStateChanged(
                DictionaryLoadState.Failed,
                KeyboardReducer.DICTIONARY_LOAD_FAILED_MESSAGE,
            ),
        )
        assertEquals(DictionaryLoadState.Failed, failed.state.dictionaryState)
        assertEquals(KeyboardReducer.DICTIONARY_LOAD_FAILED_MESSAGE, failed.state.dictionaryError)

        val retried = reduce(failed.state, KeyboardAction.RetryDictionaryLoad)
        assertEquals(DictionaryLoadState.Loading, retried.state.dictionaryState)
        assertNull("retry clears the previous error", retried.state.dictionaryError)
    }

    @Test
    fun failedStateWithoutMessageUsesShortDefaultError() {
        val failed = reduce(
            KeyboardUiState(),
            KeyboardAction.DictionaryStateChanged(DictionaryLoadState.Failed),
        )
        assertEquals(DictionaryLoadState.Failed, failed.state.dictionaryState)
        assertEquals(KeyboardReducer.DICTIONARY_LOAD_FAILED_MESSAGE, failed.state.dictionaryError)
    }

    @Test
    fun readyClearsDictionaryError() {
        val failed = KeyboardUiState(
            dictionaryState = DictionaryLoadState.Failed,
            dictionaryError = "词库加载失败",
        )
        val ready = reduce(failed, KeyboardAction.DictionaryStateChanged(DictionaryLoadState.Ready))
        assertEquals(DictionaryLoadState.Ready, ready.state.dictionaryState)
        assertNull(ready.state.dictionaryError)
    }

    @Test
    fun failedDictionaryDoesNotBlockCommitRaw() {
        val state = KeyboardUiState(
            composing = "nihao",
            compositionVersion = 4L,
            dictionaryState = DictionaryLoadState.Failed,
            dictionaryError = KeyboardReducer.DICTIONARY_LOAD_FAILED_MESSAGE,
        )

        val result = reduce(state, KeyboardAction.CommitRaw)

        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("nihao")), result.commands)
        assertEquals("", result.state.composing)
        assertEquals(
            "the raw fallback must keep the retry entry available",
            DictionaryLoadState.Failed,
            result.state.dictionaryState,
        )
        assertEquals(KeyboardReducer.DICTIONARY_LOAD_FAILED_MESSAGE, result.state.dictionaryError)
    }

    @Test
    fun dictionaryTransitionsDoNotResolvePendingConfirmation() {
        val waiting = KeyboardUiState(
            composing = "ni",
            compositionVersion = 9L,
            pendingConfirmation = PendingConfirmation.Space,
            pendingComposing = "ni",
        )

        val failed = reduce(
            waiting,
            KeyboardAction.DictionaryStateChanged(DictionaryLoadState.Failed, "词库加载失败"),
        )
        assertSame("identity fields must survive a dictionary failure",
            waiting.pendingConfirmation, failed.state.pendingConfirmation)
        assertEquals("ni", failed.state.pendingComposing)
        assertEquals("ni", failed.state.composing)
        assertTrue(failed.commands.isEmpty())

        val retry = reduce(failed.state, KeyboardAction.RetryDictionaryLoad)
        assertSame(waiting.pendingConfirmation, retry.state.pendingConfirmation)
        assertEquals(DictionaryLoadState.Loading, retry.state.dictionaryState)
        assertTrue(retry.commands.isEmpty())
    }

    @Test
    fun retryDoesNotTouchCandidatesOrQueryState() {
        val state = KeyboardUiState(
            composing = "ni",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你", "ni", 100)),
            candidateQueryError = "候选查询失败，请重试或继续输入",
            dictionaryState = DictionaryLoadState.Failed,
            dictionaryError = "词库加载失败",
        )

        val retried = reduce(state, KeyboardAction.RetryDictionaryLoad)

        assertEquals(DictionaryLoadState.Loading, retried.state.dictionaryState)
        assertNull(retried.state.dictionaryError)
        assertEquals("candidates stay intact", state.candidates, retried.state.candidates)
        assertEquals("ni", retried.state.candidatesFor)
        assertEquals(
            "the independent P0-A query hint is not cleared by a dictionary retry",
            "候选查询失败，请重试或继续输入",
            retried.state.candidateQueryError,
        )
    }
}
