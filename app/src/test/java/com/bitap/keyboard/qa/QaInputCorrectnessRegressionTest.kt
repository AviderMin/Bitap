package com.bitap.keyboard.qa

import com.bitap.keyboard.core.Candidate
import com.bitap.keyboard.core.ImeCommand
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardReducer
import com.bitap.keyboard.core.KeyboardUiState
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.Reduction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A4 independent regression: input correctness at the pure state-machine level.
 *
 * Covers docs/06-quality-bar.md A4 plus the regressions fixed in d48480f:
 * continuous pinyin sentences, punctuation, stale candidates, last-letter
 * backspace and repeated backspace (what a held key emits).
 *
 * NOTE: actual long-press press/release timing lives in KeyboardScreen's
 * Compose pointerInput and is NOT reachable from JVM tests; see task-9 notes.
 */
class QaInputCorrectnessRegressionTest {

    private fun reduce(
        state: KeyboardUiState = KeyboardUiState(),
        action: KeyboardAction,
    ): Reduction = KeyboardReducer.reduce(state, action)

    private fun syntheticEngine(): PinyinEngine = PinyinEngine.fromLines(
        sequenceOf(
            "wo	我	300",
            "ai	爱	200",
            "ni	你	300",
            "nihao	你好	80",
            "hao	好	200",
            "ma	吗	100",
        ),
    )

    // ------------------------------------------------------------------
    // 1. Continuous pinyin -> whole-sentence candidate
    // ------------------------------------------------------------------

    @Test
    fun realDictionaryProducesWholeSentenceCandidates() {
        val dictionary = File("src/main/assets/pinyin_dict.tsv")
        assumeTrue("bundled dictionary missing: " + dictionary.absolutePath, dictionary.isFile)
        val engine = dictionary.useLines { lines -> PinyinEngine.fromLines(lines) }

        assertEquals("今天天气很好", engine.search("jintiantianqihenhao").first().word)
        assertTrue(engine.search("woaini").map { it.word }.contains("我爱你"))
        assertTrue(engine.search("woxiangchifan").map { it.word }.contains("我想吃饭"))
    }

    @Test
    fun continuousPinyinTypingAccumulatesComposingThenCommitsWholeSentence() {
        val engine = syntheticEngine()
        var state = KeyboardUiState()
        val typed = "woaini"

        typed.forEach { letter ->
            val result = reduce(state = state, action = KeyboardAction.AppendInput(letter.toString()))
            state = result.state
            assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText(state.composing)), result.commands)
        }
        assertEquals(typed, state.composing)

        // A stale query result for the first syllable must not be treated as final.
        val early = reduce(
            state = state,
            action = KeyboardAction.ComposingUpdated("wo", listOf(Candidate("我", "wo", 300))),
        )
        assertTrue(early.state.candidates.isEmpty())

        val finalCandidates = engine.search(typed)
        assertEquals("我爱你", finalCandidates.first().word)
        state = reduce(
            state = state,
            action = KeyboardAction.ComposingUpdated(typed, finalCandidates),
        ).state
        assertEquals(typed, state.candidatesFor)

        val committed = reduce(state = state, action = KeyboardAction.Space)
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("我爱你")), committed.commands)
        assertEquals("", committed.state.composing)
        assertEquals("", committed.state.candidatesFor)
        assertTrue(committed.state.candidates.isEmpty())
    }

    // ------------------------------------------------------------------
    // 2. Punctuation must commit the composition, never leave it behind
    // ------------------------------------------------------------------

    @Test
    fun punctuationAfterFreshCompositionCommitsCandidateThenPunctuation() {
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
        assertEquals("", result.state.candidatesFor)
    }

    @Test
    fun punctuationWithoutCompositionCommitsOnlyPunctuation() {
        val result = reduce(action = KeyboardAction.AppendInput("。"))
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("。")), result.commands)
        assertEquals("", result.state.composing)
    }

    @Test
    fun punctuationClearsAiCandidateSideState() {
        val state = KeyboardUiState(
            composing = "ni",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你", "ni", 100)),
            aiCandidates = listOf("你好"),
            aiCandidatesLoading = true,
            aiCandidatesError = "旧错误",
        )
        val result = reduce(state = state, action = KeyboardAction.AppendInput("，"))
        assertTrue(result.state.aiCandidates.isEmpty())
        assertFalse(result.state.aiCandidatesLoading)
        assertEquals(null, result.state.aiCandidatesError)
    }

    // ------------------------------------------------------------------
    // 3. Old / stale candidates must never be committed
    // ------------------------------------------------------------------

    @Test
    fun punctuationWithStaleCandidatesCommitsRawComposingNotOldCandidate() {
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
    }

    @Test
    fun staleCandidatesAreNotCommittedBySpace() {
        val state = KeyboardUiState(
            composing = "nihao",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你", "ni", 100)),
        )
        val result = reduce(state = state, action = KeyboardAction.Space)
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("nihao")), result.commands)
    }

    @Test
    fun staleCandidatesAreNotCommittedByTap() {
        val state = KeyboardUiState(
            composing = "nihao",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你", "ni", 100)),
        )
        val result = reduce(state = state, action = KeyboardAction.CandidateTapped(0))
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("nihao")), result.commands)
        assertEquals("", result.state.composing)
    }

    @Test
    fun typingMoreLettersKeepsOldCandidatesVisibleButMarksThemStale() {
        val state = KeyboardUiState(
            composing = "ni",
            candidatesFor = "ni",
            candidates = listOf(Candidate("你", "ni", 100), Candidate("泥", "ni", 50)),
        )
        val result = reduce(state = state, action = KeyboardAction.AppendInput("h"))
        assertEquals("nih", result.state.composing)
        assertEquals(2, result.state.candidates.size)
        assertEquals("ni", result.state.candidatesFor)
    }

    // ------------------------------------------------------------------
    // 4. Backspace, including the last composing letter
    // ------------------------------------------------------------------

    @Test
    fun backspaceOnLastComposingLetterFinishesThenDeletes() {
        val state = KeyboardUiState(
            composing = "n",
            candidatesFor = "n",
            candidates = listOf(Candidate("你", "n", 10)),
        )
        val result = reduce(state = state, action = KeyboardAction.Backspace)
        assertEquals(
            listOf<ImeCommand>(ImeCommand.FinishComposing, ImeCommand.DeleteBackward),
            result.commands,
        )
        assertEquals("", result.state.composing)
        assertTrue(result.state.candidates.isEmpty())
        assertEquals("", result.state.candidatesFor)
    }

    @Test
    fun backspaceWithEmptyComposingOnlyDeletes() {
        val result = reduce(action = KeyboardAction.Backspace)
        assertEquals(listOf<ImeCommand>(ImeCommand.DeleteBackward), result.commands)
    }

    @Test
    fun repeatedBackspaceWhileHeldConsumesComposingThenDeletesEachTime() {
        // What a held backspace key emits: repeated Backspace actions. The UI
        // press/release timing itself is not JVM-testable (see class comment).
        var state = KeyboardUiState(composing = "abc")
        val emissions = ArrayList<List<ImeCommand>>()
        repeat(5) {
            val result = reduce(state = state, action = KeyboardAction.Backspace)
            emissions += result.commands
            state = result.state
        }
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText("ab")), emissions[0])
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposingText("a")), emissions[1])
        assertEquals(
            listOf<ImeCommand>(ImeCommand.FinishComposing, ImeCommand.DeleteBackward),
            emissions[2],
        )
        assertEquals(listOf<ImeCommand>(ImeCommand.DeleteBackward), emissions[3])
        assertEquals(listOf<ImeCommand>(ImeCommand.DeleteBackward), emissions[4])
        assertEquals("", state.composing)
    }
}
