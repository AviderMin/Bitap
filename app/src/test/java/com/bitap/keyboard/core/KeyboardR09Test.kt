package com.bitap.keyboard.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R09: initial layout suggestion vs hard language lock, and numeric capabilities. */
class KeyboardR09Test {

    private fun reduce(
        state: KeyboardUiState = KeyboardUiState(),
        action: KeyboardAction,
    ): Reduction = KeyboardReducer.reduce(state, action)

    private fun editor(inputType: Int): EditorInputInfo =
        EditorInputInfo.from(inputType = inputType, imeOptions = EditorInfo.IME_ACTION_NONE)

    @Test
    fun emailAndUrlDefaultEnglishButAllowDeliberateChineseSearch() {
        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        ).forEach { inputType ->
            val info = editor(inputType)
            assertTrue(info.supportsChineseInput)
            assertEquals(InputLanguage.English, info.preferredLanguage)

            val state = KeyboardUiState(editorInfo = info, language = info.preferredLanguage)
            val switched = reduce(state, KeyboardAction.ToggleLanguage)
            assertEquals(InputLanguage.Chinese, switched.state.language)
            val typed = reduce(switched.state, KeyboardAction.AppendInput("n"))
            assertEquals("n", typed.state.composing)
        }
    }

    @Test
    fun emailSymbolsCommitDirectlyInEnglishMode() {
        val state = KeyboardUiState(
            editorInfo = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            language = InputLanguage.English,
        )
        val at = reduce(state, KeyboardAction.AppendInput("@"))
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText("@")), at.commands)
        val dot = reduce(at.state, KeyboardAction.AppendInput("."))
        assertEquals(listOf<ImeCommand>(ImeCommand.CommitText(".")), dot.commands)
        assertEquals("", dot.state.composing)
    }

    @Test
    fun numericModesMapDecimalSignedIntegerAndPhone() {
        assertNumeric(
            inputType = InputType.TYPE_CLASS_NUMBER,
            mode = NumericInputMode.Integer,
            symbols = emptyList(),
        )
        assertNumeric(
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            mode = NumericInputMode.Decimal,
            symbols = listOf("."),
        )
        assertNumeric(
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
            mode = NumericInputMode.Signed,
            symbols = listOf("+", "-"),
        )
        assertNumeric(
            inputType = InputType.TYPE_CLASS_PHONE,
            mode = NumericInputMode.Phone,
            symbols = listOf("+", "-", "*", "#", " ", "(", ")"),
        )
    }

    @Test
    fun numberModesStartOnNumberLayerAndCannotToggleChinese() {
        listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
            InputType.TYPE_CLASS_PHONE,
        ).forEach { inputType ->
            val info = editor(inputType)
            assertFalse(info.supportsChineseInput)
            assertEquals(KeyboardLayer.Numbers, info.preferredLayer)
            val state = KeyboardUiState(editorInfo = info, language = InputLanguage.English)
            val result = reduce(state, KeyboardAction.ToggleLanguage)
            assertEquals(InputLanguage.English, result.state.language)
            assertTrue(result.commands.isEmpty())
        }
    }

    private fun assertNumeric(
        inputType: Int,
        mode: NumericInputMode,
        symbols: List<String>,
    ) {
        val info = editor(inputType)
        assertEquals(mode, info.numericMode)
        assertEquals(symbols, info.numericSymbols)
        assertEquals(mode, KeyboardUiState(editorInfo = info).numericMode)
        assertEquals(symbols, KeyboardUiState(editorInfo = info).numericSymbols)
    }
}
