package com.bitap.keyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2: testable mixed Chinese/English input path and predictable Space /
 * punctuation / Enter behaviour. The supported path is explicit raw commit:
 * toggle to English (or 上屏原文) for names/abbreviations, then switch back.
 * Continuous no-switch mixed composition (English letters embedded directly in
 * pinyin composing) is intentionally not supported and documented as such.
 */
class MixedInputPathTest {

    private fun reduce(state: KeyboardUiState, action: KeyboardAction): Reduction =
        KeyboardReducer.reduce(state, action)

    @Test
    fun chinesePrefixRawCommitThenEnglishThenChineseAgain() {
        var state = KeyboardUiState()
        "woyaomai".forEach { char ->
            state = reduce(state, KeyboardAction.AppendInput(char.toString())).state
        }
        assertEquals("woyaomai", state.composing)

        // Language switch commits the current composing as explicit raw text.
        val toEnglish = reduce(state, KeyboardAction.ToggleLanguage)
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("woyaomai")), toEnglish.commands)
        assertEquals(InputLanguage.English, toEnglish.state.language)
        assertEquals("", toEnglish.state.composing)
        state = toEnglish.state

        "phone".forEach { char ->
            val result = reduce(state, KeyboardAction.AppendInput(char.toString()))
            assertEquals(listOf<ImeCommand>(ImeCommand.CommitText(char.toString())), result.commands)
            state = result.state
        }

        val toChinese = reduce(state, KeyboardAction.ToggleLanguage)
        assertEquals(InputLanguage.Chinese, toChinese.state.language)
        state = toChinese.state
        "mingtian".forEach { char ->
            state = reduce(state, KeyboardAction.AppendInput(char.toString())).state
        }
        assertEquals("mingtian", state.composing)
        state = reduce(
            state,
            KeyboardAction.ComposingUpdated("mingtian", listOf(Candidate("明天", "mingtian", 100))),
        ).state
        val space = reduce(state, KeyboardAction.Space)
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("明天")), space.commands)
    }

    @Test
    fun rawAbbreviationCanAlwaysBeSubmittedInChineseMode() {
        var state = KeyboardUiState()
        "app".forEach { char ->
            state = reduce(state, KeyboardAction.AppendInput(char.toString())).state
        }
        val raw = reduce(state, KeyboardAction.CommitRaw)
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("app")), raw.commands)
        assertEquals("", raw.state.composing)
    }

    @Test
    fun englishSpacePunctuationAndEnterArePredictable() {
        var state = reduce(KeyboardUiState(), KeyboardAction.ToggleLanguage).state
        assertEquals(InputLanguage.English, state.language)
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText(" ")),
            reduce(state, KeyboardAction.AppendInput(" ")).commands,
        )
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("，")),
            reduce(state, KeyboardAction.AppendInput("，")).commands,
        )
        assertEquals(
            listOf<ImeCommand>(ImeCommand.SendEnter),
            reduce(state, KeyboardAction.Enter).commands,
        )
    }

    @Test
    fun chinesePunctuationCommitsFreshCandidateThenPunctuation() {
        var state = KeyboardUiState(composing = "nihao", candidatesFor = "nihao")
        state = state.copy(candidates = listOf(Candidate("你好", "nihao", 100)))
        val result = reduce(state, KeyboardAction.AppendInput("，"))
        assertEquals(
            listOf(ImeCommand.CommitText("你好"), ImeCommand.CommitText("，")),
            result.commands,
        )
        assertTrue(result.state.composing.isEmpty())
    }
}
