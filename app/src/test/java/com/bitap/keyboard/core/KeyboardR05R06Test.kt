package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R05/R06 action-chain regressions: separator keystrokes must enter composing,
 * incomplete final syllables must continue, and prefix phrase candidates must
 * leave the remaining pinyin composing.
 */
class KeyboardR05R06Test {

    private fun reduce(
        state: KeyboardUiState = KeyboardUiState(),
        action: KeyboardAction,
    ): Reduction = KeyboardReducer.reduce(state, action)

    private fun type(state: KeyboardUiState, text: String): KeyboardUiState {
        var current = state
        text.forEach { char ->
            current = reduce(current, KeyboardAction.AppendInput(char.toString())).state
        }
        return current
    }

    private fun separatorEngine(): PinyinEngine = PinyinEngine.fromLines(
        sequenceOf(
            "xi\t西\t300",
            "an\t安\t300",
            "xian\t先\t500",
            "a\t啊\t100",
        ),
    )

    @Test
    fun actionChainBuildsXianWithSeparatorAndCommitsXiAn() {
        val engine = separatorEngine()
        var state = type(KeyboardUiState(), "xi")
        state = reduce(state, KeyboardAction.AppendInput("'")).state
        state = type(state, "an")
        assertEquals("xi'an", state.composing)

        state = reduce(
            state,
            KeyboardAction.ComposingUpdated(state.composing, engine.search(state.composing)),
        ).state
        val index = state.candidates.indexOfFirst { it.word == "西安" }
        assertTrue("xi'an should produce 西安, got " + state.candidates.map { it.word }, index >= 0)
        assertEquals(5, state.candidates[index].consumedInputLength)

        val committed = reduce(state, KeyboardAction.CandidateTapped(index))
        assertEquals("", committed.state.composing)
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("西安")), committed.commands)
    }

    @Test
    fun separatorWorksAfterSwitchingToNumberLayerWithoutCommittingComposition() {
        var state = KeyboardUiState(composing = "xi", candidatesFor = "xi", candidates = listOf(Candidate("西")))
        val switched = reduce(state, KeyboardAction.ShowLayer(KeyboardLayer.Numbers))
        assertEquals(KeyboardLayer.Numbers, switched.state.layer)
        assertEquals("xi", switched.state.composing)
        assertTrue(switched.commands.isEmpty())

        val separator = reduce(switched.state, KeyboardAction.AppendInput("'"))
        assertEquals("xi'", separator.state.composing)
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText("xi'")), separator.commands)
    }

    @Test
    fun incompleteLastSyllableXiAContinuesComposing() {
        val afterSeparator = reduce(KeyboardUiState(composing = "xi"), KeyboardAction.AppendInput("'"))
        assertEquals("xi'", afterSeparator.state.composing)
        assertEquals(
            listOf<ImeCommand>(ImeCommand.SetComposingText("xi'")),
            afterSeparator.commands,
        )

        val afterA = reduce(afterSeparator.state, KeyboardAction.AppendInput("a"))
        assertEquals("xi'a", afterA.state.composing)
        assertEquals(
            listOf<ImeCommand>(ImeCommand.SetComposingText("xi'a")),
            afterA.commands,
        )

        // Completing the final syllable is still possible.
        val completed = reduce(afterA.state, KeyboardAction.AppendInput("n"))
        assertEquals("xi'an", completed.state.composing)
        assertTrue(completed.commands.all { it is ImeCommand.SetComposingText })
    }

    @Test
    fun repeatedAndLeadingSeparatorsAreDefined() {
        val leading = reduce(KeyboardUiState(), KeyboardAction.AppendInput("'"))
        assertEquals("", leading.state.composing)
        assertTrue(leading.commands.isEmpty())

        var state = type(KeyboardUiState(), "xi")
        state = reduce(state, KeyboardAction.AppendInput("'")).state
        state = reduce(state, KeyboardAction.AppendInput("'")).state
        assertEquals("xi'", state.composing)
        state = reduce(state, KeyboardAction.AppendInput("a")).state
        assertEquals("xi'a", state.composing)
    }

    @Test
    fun backspaceThroughSeparatorKeepsComposingAndDoesNotCommit() {
        var state = KeyboardUiState(composing = "xi'an", candidatesFor = "xi'an")
        val afterApostrophe = reduce(state, KeyboardAction.Backspace)
        assertEquals("xi'a", afterApostrophe.state.composing)
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText("xi'a")), afterApostrophe.commands)

        state = afterApostrophe.state
        val afterA = reduce(state, KeyboardAction.Backspace)
        assertEquals("xi'", afterA.state.composing)
        val afterQuote = reduce(afterA.state, KeyboardAction.Backspace)
        assertEquals("xi", afterQuote.state.composing)
        assertTrue(afterQuote.commands.all { it is ImeCommand.SetComposingText })
    }

    @Test
    fun prefixPhraseCandidateKeepsRemainingPinyinAndCanBeContinued() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "wo\t我\t300",
                "ai\t爱\t200",
                "ni\t你\t300",
            ),
        )
        var state = type(KeyboardUiState(), "woaini")
        state = reduce(
            state,
            KeyboardAction.ComposingUpdated(state.composing, engine.search(state.composing)),
        ).state
        val prefix = state.candidates.firstOrNull { it.word == "我爱" }
        assertTrue("prefix candidate 我爱 should exist: " + state.candidates.map { it.word }, prefix != null)
        assertEquals(4, prefix!!.consumedInputLength)

        val committed = reduce(state, KeyboardAction.CandidateTapped(state.candidates.indexOf(prefix)))
        assertEquals("ni", committed.state.composing)
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("我爱"), ImeCommand.SetComposingText("ni")),
            committed.commands,
        )

        val remaining = reduce(
            committed.state,
            KeyboardAction.ComposingUpdated("ni", engine.search("ni")),
        ).state
        val finished = reduce(remaining, KeyboardAction.Space)
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("你")), finished.commands)
        assertEquals("", finished.state.composing)
    }

    @Test
    fun phraseDecoderUsesLongDictionaryKeysBeyondOldTwelveLetterLimit() {
        val longKey = "abcdefghijklmnop" // 16 letters, old hard-coded max was 12
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "$longKey\t长\t100",
                "x\t短\t100",
            ),
        )
        val candidates = engine.search(longKey + "x")
        assertTrue(
            "long-key phrase should be found: " + candidates.map { it.word },
            candidates.map { it.word }.contains("长短"),
        )
    }
}
