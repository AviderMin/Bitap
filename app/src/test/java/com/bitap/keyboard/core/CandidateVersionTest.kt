package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D03: candidates carry the composition version/session they were produced for.
 * A stale candidate (older version or older input session) must never write
 * text; legacy unversioned candidates keep the pre-D03 behavior.
 */
class CandidateVersionTest {

    private fun reduce(state: KeyboardUiState, action: KeyboardAction): Reduction =
        KeyboardReducer.reduce(state, action)

    private fun versionedState(
        version: Long = 7L,
        sessionId: String = "session-A",
        composing: String = "nihao",
        candidates: List<Candidate> = listOf(
            Candidate(
                word = "你好",
                pinyin = "nihao",
                frequency = 500L,
                consumedInputLength = 5,
                compositionVersion = version,
                sessionId = sessionId,
            ),
        ),
    ): KeyboardUiState = KeyboardUiState(
        composing = composing,
        candidatesFor = composing,
        candidates = candidates,
        compositionVersion = version,
        compositionSessionId = sessionId,
    )

    private fun staleCandidate(): Candidate = Candidate(
        word = "你好",
        pinyin = "nihao",
        frequency = 500L,
        consumedInputLength = 5,
        compositionVersion = 6L,
        sessionId = "session-A",
    )

    @Test
    fun candidateDefaultsAreLegacyUnversioned() {
        val candidate = Candidate("你")
        assertEquals(Candidate.UNVERSIONED, candidate.compositionVersion)
        assertNull(candidate.sessionId)
    }

    @Test
    fun legacyUnversionedCandidateStillCommits() {
        val state = KeyboardUiState(
            composing = "ni",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你", "ni", 100)),
        )
        val result = reduce(state, KeyboardAction.CandidateTapped(0))
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("你")), result.commands)
        assertEquals("", result.state.composing)
    }

    @Test
    fun candidateWithCurrentVersionAndSessionCommits() {
        val result = reduce(versionedState(), KeyboardAction.CandidateTapped(0))
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("你好")), result.commands)
        assertEquals("", result.state.composing)
    }

    @Test
    fun staleVersionCandidateTapProducesNoImeCommand() {
        val state = versionedState(candidates = listOf(staleCandidate()))
        val result = reduce(state, KeyboardAction.CandidateTapped(0))
        assertTrue("stale candidate must not emit any ImeCommand", result.commands.isEmpty())
        assertEquals("nihao", result.state.composing)
        assertEquals(7L, result.state.compositionVersion)
    }

    @Test
    fun staleVersionCandidateSpaceProducesNoImeCommand() {
        val state = versionedState(candidates = listOf(staleCandidate()))
        val result = reduce(state, KeyboardAction.Space)
        assertTrue("space must not commit a stale candidate", result.commands.isEmpty())
        assertEquals("nihao", result.state.composing)
    }

    @Test
    fun staleCandidatePendingConfirmationProducesNoImeCommand() {
        val waiting = versionedState(
            candidates = emptyList(),
            composing = "nihao",
        ).copy(
            candidatesFor = "",
            pendingConfirmation = PendingConfirmation.Candidate(0),
            pendingComposing = "nihao",
        )
        // version = 0 is the legacy escape hatch, so this result is accepted as
        // an envelope; the candidate inside is still explicitly stale (v6 vs v7).
        val result = reduce(
            waiting,
            KeyboardAction.ComposingUpdated(
                composing = "nihao",
                candidates = listOf(staleCandidate()),
                version = 0L,
            ),
        )
        assertTrue("pending confirmation must not commit a stale candidate", result.commands.isEmpty())
        assertEquals("nihao", result.state.composing)
        assertNull(result.state.pendingConfirmation)
    }

    @Test
    fun staleCandidatePendingSpaceConfirmationProducesNoImeCommand() {
        val waiting = versionedState(candidates = emptyList()).copy(
            candidatesFor = "",
            pendingConfirmation = PendingConfirmation.Space,
            pendingComposing = "nihao",
        )
        val result = reduce(
            waiting,
            KeyboardAction.ComposingUpdated(
                composing = "nihao",
                candidates = listOf(staleCandidate()),
                version = 0L,
            ),
        )
        assertTrue("pending space must not commit a stale candidate", result.commands.isEmpty())
        assertEquals("nihao", result.state.composing)
    }

    @Test
    fun staleCandidatePunctuationPathProducesNoImeCommand() {
        val state = versionedState(candidates = listOf(staleCandidate()))
        val result = reduce(state, KeyboardAction.AppendInput("，"))
        assertTrue("punctuation must not commit a stale candidate", result.commands.isEmpty())
        assertEquals("nihao", result.state.composing)
    }

    @Test
    fun composingUpdateStampsVersionAndSessionOnCandidates() {
        val state = KeyboardUiState(
            composing = "nihao",
            compositionVersion = 3L,
            compositionSessionId = "session-3",
        )
        val updated = reduce(
            state,
            KeyboardAction.ComposingUpdated(
                composing = "nihao",
                candidates = listOf(Candidate("你好", "nihao", 100)),
                version = 3L,
                sessionId = "session-3",
            ),
        )
        val candidate = updated.state.candidates.single()
        assertEquals(3L, candidate.compositionVersion)
        assertEquals("session-3", candidate.sessionId)

        val committed = reduce(updated.state, KeyboardAction.CandidateTapped(0))
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("你好")), committed.commands)
    }

    @Test
    fun composingUpdateDoesNotLaunderExplicitlyStaleCandidate() {
        val stale = Candidate(
            word = "你好",
            pinyin = "nihao",
            frequency = 500L,
            consumedInputLength = 5,
            compositionVersion = 2L,
            sessionId = "session-A",
        )
        val state = KeyboardUiState(
            composing = "nihao",
            compositionVersion = 3L,
            compositionSessionId = "session-A",
        )
        val updated = reduce(
            state,
            KeyboardAction.ComposingUpdated(
                composing = "nihao",
                candidates = listOf(stale),
                version = 3L,
                sessionId = "session-A",
            ),
        )
        assertEquals("explicit candidate version must not be overwritten", 2L, updated.state.candidates.single().compositionVersion)
        val committed = reduce(updated.state, KeyboardAction.CandidateTapped(0))
        assertTrue("a result must not launder a stale candidate", committed.commands.isEmpty())
    }

    @Test
    fun legacyComposingUpdateKeepsUnversionedCandidatesCommitting() {
        // version = 0 is the compatibility path used by older callers/tests;
        // it must not stamp 0 into candidates or old flows would break.
        val state = KeyboardUiState(composing = "ni", compositionVersion = 5L)
        val updated = reduce(
            state,
            KeyboardAction.ComposingUpdated(
                composing = "ni",
                candidates = listOf(Candidate("你", "ni", 100)),
                version = 0L,
            ),
        )
        assertEquals(Candidate.UNVERSIONED, updated.state.candidates.single().compositionVersion)
        val committed = reduce(updated.state, KeyboardAction.CandidateTapped(0))
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("你")), committed.commands)
    }

    @Test
    fun candidateFromOldSessionIsRejectedEvenWhenVersionMatches() {
        val state = versionedState(
            version = 7L,
            sessionId = "session-B",
            candidates = listOf(
                Candidate(
                    word = "你好",
                    pinyin = "nihao",
                    frequency = 500L,
                    consumedInputLength = 5,
                    compositionVersion = 7L,
                    sessionId = "session-A",
                ),
            ),
        )
        val result = reduce(state, KeyboardAction.CandidateTapped(0))
        assertTrue("candidate from an old session must be rejected", result.commands.isEmpty())
        assertEquals("nihao", result.state.composing)
    }

    @Test
    fun resultFromOldSessionIsRejectedEvenWhenVersionMatches() {
        val state = KeyboardUiState(
            composing = "ni",
            compositionVersion = 9L,
            compositionSessionId = "session-B",
        )
        val result = reduce(
            state,
            KeyboardAction.ComposingUpdated(
                composing = "ni",
                candidates = listOf(Candidate("你", "ni", 100)),
                version = 9L,
                sessionId = "session-A",
            ),
        )
        assertTrue("result from an old session must be rejected", result.state.candidates.isEmpty())
        assertEquals("", result.state.candidatesFor)
        assertEquals("session-B", result.state.compositionSessionId)
    }

    @Test
    fun editorSwitchRegeneratesSessionAndDropsOldCandidates() {
        val state = KeyboardUiState(
            composing = "ni",
            candidatesFor = "ni",
            candidates = listOf(
                Candidate(
                    word = "你",
                    pinyin = "ni",
                    frequency = 100L,
                    compositionVersion = 1L,
                    sessionId = "session-A",
                ),
            ),
            compositionVersion = 1L,
            compositionSessionId = "session-A",
        )
        val result = reduce(
            state,
            KeyboardAction.EditorInfoChanged(
                info = KeyboardUiState().editorInfo,
                sessionId = "session-B",
            ),
        )
        assertEquals("session-B", result.state.compositionSessionId)
        assertEquals(2L, result.state.compositionVersion)
        assertEquals("", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
        assertTrue(result.commands.isEmpty())
    }

    @Test
    fun editorSwitchWithoutExplicitIdGeneratesFreshSessionId() {
        val info = KeyboardUiState().editorInfo
        val first = reduce(KeyboardUiState(), KeyboardAction.EditorInfoChanged(info))
        val second = reduce(first.state, KeyboardAction.EditorInfoChanged(info))
        assertEquals("editor-1", first.state.compositionSessionId)
        assertEquals("editor-2", second.state.compositionSessionId)
    }

    @Test
    fun oldSessionResultAfterEditorSwitchAndRetypeIsRejected() {
        val info = KeyboardUiState().editorInfo
        var state = reduce(KeyboardUiState(), KeyboardAction.EditorInfoChanged(info, sessionId = "session-A")).state
        state = reduce(state, KeyboardAction.AppendInput("n")).state
        state = reduce(state, KeyboardAction.AppendInput("i")).state
        val staleVersion = state.compositionVersion
        val oldResult = KeyboardAction.ComposingUpdated(
            composing = "ni",
            candidates = listOf(
                Candidate(
                    word = "你",
                    pinyin = "ni",
                    frequency = 100L,
                    compositionVersion = staleVersion,
                    sessionId = "session-A",
                ),
            ),
            version = staleVersion,
            sessionId = "session-A",
        )

        state = reduce(state, KeyboardAction.EditorInfoChanged(info, sessionId = "session-B")).state
        state = reduce(state, KeyboardAction.AppendInput("n")).state
        state = reduce(state, KeyboardAction.AppendInput("i")).state
        val result = reduce(state, oldResult)
        assertTrue("old session result must not populate candidates", result.state.candidates.isEmpty())
        assertEquals("ni", result.state.composing)
        assertEquals("session-B", result.state.compositionSessionId)
    }

    @Test
    fun partialCandidateConsumesOnlyPrefix() {
        val state = KeyboardUiState(
            composing = "nihaoma",
            candidatesFor = "nihaoma",
            candidates = listOf(
                Candidate(
                    word = "你好",
                    pinyin = "nihao",
                    frequency = 500L,
                    consumedInputLength = 5,
                    compositionVersion = 4L,
                    sessionId = "session-A",
                ),
            ),
            compositionVersion = 4L,
            compositionSessionId = "session-A",
        )
        val result = reduce(state, KeyboardAction.CandidateTapped(0))
        assertEquals(
            listOf(
                ImeCommand.CommitText("你好"),
                ImeCommand.SetComposingText("ma"),
            ),
            result.commands,
        )
        assertEquals("ma", result.state.composing)
        assertEquals(5L, result.state.compositionVersion)
        assertEquals(5, result.learnedSelection?.consumedInputLength)
        assertEquals("你好", result.learnedSelection?.word)
    }
}
