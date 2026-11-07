package com.bitap.keyboard.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08: independent numeric capabilities, signed+decimal, and date/time policy. */
class KeyboardS08Test {

    private fun info(inputType: Int): EditorInputInfo =
        EditorInputInfo.from(inputType = inputType, imeOptions = EditorInfo.IME_ACTION_NONE)

    private fun reduce(
        state: KeyboardUiState,
        action: KeyboardAction,
    ): Reduction = KeyboardReducer.reduce(state, action)

    @Test
    fun signedAndDecimalFlagsCombine() {
        val editor = info(
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED,
        )
        assertTrue(editor.allowsDecimal)
        assertTrue(editor.allowsSigned)
        assertTrue(editor.numericSymbols.containsAll(listOf(".", "+", "-")))
        assertEquals(NumericInputMode.Decimal, editor.numericMode)

        var state = KeyboardUiState(
            editorInfo = editor,
            language = InputLanguage.English,
            layer = KeyboardLayer.Numbers,
        )
        val commands = mutableListOf<ImeCommand>()
        "-12.5".forEach { char ->
            val result = reduce(state, KeyboardAction.AppendInput(char.toString()))
            state = result.state
            commands += result.commands
        }
        assertEquals(
            listOf(
                ImeCommand.CommitText("-"),
                ImeCommand.CommitText("1"),
                ImeCommand.CommitText("2"),
                ImeCommand.CommitText("."),
                ImeCommand.CommitText("5"),
            ),
            commands,
        )
    }

    @Test
    fun integerLayoutRejectsDecimalAndSignKeys() {
        val editor = info(InputType.TYPE_CLASS_NUMBER)
        val state = KeyboardUiState(
            editorInfo = editor,
            language = InputLanguage.English,
            layer = KeyboardLayer.Numbers,
        )
        assertTrue(editor.numericSymbols.isEmpty())
        assertTrue(reduce(state, KeyboardAction.AppendInput(".")).commands.isEmpty())
        assertTrue(reduce(state, KeyboardAction.AppendInput("-")).commands.isEmpty())
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("1")),
            reduce(state, KeyboardAction.AppendInput("1")).commands,
        )
    }

    @Test
    fun phoneAndDateTimeExposeTheirOwnSymbols() {
        val phone = info(InputType.TYPE_CLASS_PHONE)
        assertEquals(NumericInputMode.Phone, phone.numericMode)
        assertTrue(phone.numericSymbols.containsAll(listOf("+", "-", "*", "#", "(", ")")))

        val dateTime = info(InputType.TYPE_CLASS_DATETIME)
        assertEquals(EditorInputKind.DateTime, dateTime.kind)
        assertEquals(NumericInputMode.DateTime, dateTime.numericMode)
        assertEquals(listOf("/", ":", "-", " "), dateTime.numericSymbols)
        assertEquals(KeyboardLayer.Numbers, dateTime.preferredLayer)
        assertTrue(dateTime.isDateTime)
    }

    @Test
    fun numericEditorRejectsLettersButAllowsDeclaredSymbols() {
        val editor = info(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val state = KeyboardUiState(
            editorInfo = editor,
            language = InputLanguage.English,
            layer = KeyboardLayer.Numbers,
        )
        assertTrue(reduce(state, KeyboardAction.AppendInput("a")).commands.isEmpty())
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText(".")),
            reduce(state, KeyboardAction.AppendInput(".")).commands,
        )
    }

    @Test
    fun dateTimeRejectsLettersButAllowsDateSeparators() {
        val editor = info(InputType.TYPE_CLASS_DATETIME)
        val state = KeyboardUiState(
            editorInfo = editor,
            language = InputLanguage.English,
            layer = KeyboardLayer.Numbers,
        )
        assertTrue(reduce(state, KeyboardAction.AppendInput("a")).commands.isEmpty())
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText("/")),
            reduce(state, KeyboardAction.AppendInput("/")).commands,
        )
        assertEquals(
            listOf<ImeCommand>(ImeCommand.CommitText(":")),
            reduce(state, KeyboardAction.AppendInput(":")).commands,
        )
    }
}
