package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.ime.KeyboardController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1 editing-tool coverage: selection, clipboard and word-level operations. */
class KeyboardControllerEditingTest {

    @Test
    fun selectAllSelectsWholeEditorAndCopyKeepsSelection() {
        val runtime = Runtime("hello 世界")
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))

            runtime.controller.dispatch(KeyboardAction.SelectAll)
            assertEquals("select all starts at the document start", 0, runtime.session.selectionStart)
            assertEquals("select all covers the document end", "hello 世界".length, runtime.session.selectionEnd)
            assertTrue("select all must advertise a selection", runtime.controller.state.value.hasSelection)

            runtime.controller.dispatch(KeyboardAction.CopySelection)
            assertEquals("hello 世界", runtime.session.clipboardText)
            assertEquals("copy must not edit the text", "hello 世界", runtime.session.text)
            assertTrue("copy keeps the editor selection", runtime.controller.state.value.hasSelection)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun copyWithoutSelectionDoesNotChangeTextOrClipboard() {
        val runtime = Runtime(initialText = "abc", initialClipboard = "keep")
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))

            runtime.controller.dispatch(KeyboardAction.CopySelection)

            assertEquals("no selection must leave the clipboard untouched", "keep", runtime.session.clipboardText)
            assertEquals("no selection must leave the text untouched", "abc", runtime.session.text)
            assertFalse(runtime.controller.state.value.hasSelection)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun cutRemovesSelectionCopiesItAndClearsUndo() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("，"))
            assertEquals("，", runtime.session.text)
            assertTrue("a text commit must be undoable before the cut", runtime.controller.state.value.canUndo)

            runtime.session.select(0, 1)
            runtime.controller.onSelectionChanged(0, 1, -1, -1)
            assertTrue("selecting text must set hasSelection", runtime.controller.state.value.hasSelection)

            runtime.controller.dispatch(KeyboardAction.CutSelection)

            assertEquals("cut removes the selected range", "", runtime.session.text)
            assertEquals("cut copies the selected text", "，", runtime.session.clipboardText)
            assertFalse("cut clears hasSelection", runtime.controller.state.value.hasSelection)
            assertFalse("cut clears stale undo history", runtime.controller.state.value.canUndo)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun pasteInsertsClipboardAtCursorAndClearsUndo() {
        val runtime = Runtime(initialText = "ab", initialClipboard = "XY")
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("，"))
            assertEquals("ab，", runtime.session.text)
            assertTrue(runtime.controller.state.value.canUndo)

            runtime.controller.dispatch(KeyboardAction.Paste)

            assertEquals("paste inserts at the cursor", "ab，XY", runtime.session.text)
            assertEquals(5, runtime.session.cursor)
            assertFalse("paste clears hasSelection", runtime.controller.state.value.hasSelection)
            assertFalse("paste clears stale undo history", runtime.controller.state.value.canUndo)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun wordCursorActionsMoveAcrossEnglishAndChineseWords() {
        val english = Runtime("hello world")
        try {
            english.controller.onStartInput(EditorInputInfo.from(0, 0))
            english.controller.dispatch(KeyboardAction.MoveCursorByWord(-1))
            assertEquals("word-left jumps to the start of world", 6, english.session.cursor)
            english.controller.dispatch(KeyboardAction.MoveCursorByWord(-1))
            assertEquals("word-left jumps to the start of hello", 0, english.session.cursor)
            english.controller.dispatch(KeyboardAction.MoveCursorByWord(1))
            assertEquals("word-right jumps to the end of hello", 5, english.session.cursor)
            english.controller.dispatch(KeyboardAction.MoveCursorByWord(1))
            assertEquals("word-right jumps to the end of world", 11, english.session.cursor)
            assertEquals("word cursor movement must not edit text", "hello world", english.session.text)
        } finally {
            english.dispose()
        }

        val chinese = Runtime("你好世界")
        try {
            chinese.controller.onStartInput(EditorInputInfo.from(0, 0))
            chinese.controller.dispatch(KeyboardAction.MoveCursorByWord(-1))
            assertEquals("word-left moves one CJK character", 3, chinese.session.cursor)
            chinese.controller.dispatch(KeyboardAction.MoveCursorByWord(-1))
            assertEquals("word-left moves another CJK character", 2, chinese.session.cursor)
            chinese.controller.dispatch(KeyboardAction.MoveCursorByWord(1))
            assertEquals("word-right moves one CJK character", 3, chinese.session.cursor)
            assertEquals("你好世界", chinese.session.text)
        } finally {
            chinese.dispose()
        }
    }

    @Test
    fun deleteWordRemovesWholeEnglishWordAndSingleChineseCharacter() {
        val english = Runtime("hello world")
        try {
            english.controller.onStartInput(EditorInputInfo.from(0, 0))
            english.controller.dispatch(KeyboardAction.DeleteWord)
            assertEquals("delete word removes the whole English word", "hello ", english.session.text)
            assertEquals(6, english.session.cursor)
        } finally {
            english.dispose()
        }

        val chinese = Runtime("你好")
        try {
            chinese.controller.onStartInput(EditorInputInfo.from(0, 0))
            chinese.controller.dispatch(KeyboardAction.DeleteWord)
            assertEquals("delete word removes one CJK character", "你", chinese.session.text)
            assertEquals(1, chinese.session.cursor)
        } finally {
            chinese.dispose()
        }
    }

    @Test
    fun editingToolsVisibilityTogglesAndResetsOnNewInput() {
        val runtime = Runtime("abc")
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            assertFalse(runtime.controller.state.value.editingToolsVisible)

            runtime.controller.dispatch(KeyboardAction.ToggleEditingTools)
            assertTrue("first toggle opens the editing tools", runtime.controller.state.value.editingToolsVisible)
            runtime.controller.dispatch(KeyboardAction.ToggleEditingTools)
            assertFalse("second toggle closes the editing tools", runtime.controller.state.value.editingToolsVisible)
            runtime.controller.dispatch(KeyboardAction.ToggleEditingTools)
            assertTrue(runtime.controller.state.value.editingToolsVisible)

            runtime.session.select(0, 1)
            runtime.controller.onSelectionChanged(0, 1, -1, -1)
            assertTrue(runtime.controller.state.value.hasSelection)

            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            assertFalse("new input must hide the editing tools", runtime.controller.state.value.editingToolsVisible)
            assertFalse("new input must clear hasSelection", runtime.controller.state.value.hasSelection)

            runtime.controller.dispatch(KeyboardAction.ToggleEditingTools)
            assertTrue(runtime.controller.state.value.editingToolsVisible)
            runtime.controller.onFinishInput()
            assertFalse("finish input must hide the editing tools", runtime.controller.state.value.editingToolsVisible)
            assertFalse("finish input must clear hasSelection", runtime.controller.state.value.hasSelection)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun selectionChangedUpdatesHasSelectionWithoutComposing() {
        val runtime = Runtime("hello")
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))

            runtime.controller.onSelectionChanged(0, 3, -1, -1)
            assertTrue("a real range must set hasSelection", runtime.controller.state.value.hasSelection)

            runtime.controller.onSelectionChanged(3, 3, -1, -1)
            assertFalse("a collapsed caret must clear hasSelection", runtime.controller.state.value.hasSelection)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun reducerKeepsHasSelectionThroughComposingCallbacksAndResetsOnEditorChange() {
        // A normal IME composing-end callback is collapsed: it must keep the
        // composition but never leave a stale hasSelection behind.
        val composing = KeyboardUiState(composing = "ni", hasSelection = true)
        val normalCallback = KeyboardReducer.reduce(
            composing,
            KeyboardAction.SelectionChanged(
                selectionStart = 2,
                selectionEnd = 2,
                candidatesStart = 0,
                candidatesEnd = 2,
            ),
        )
        assertEquals("ni", normalCallback.state.composing)
        assertFalse("normal composing callback must clear stale hasSelection", normalCallback.state.hasSelection)

        // A real range inside/around composing still ends the composition but
        // records the selection.
        val realSelection = KeyboardReducer.reduce(
            composing,
            KeyboardAction.SelectionChanged(
                selectionStart = 1,
                selectionEnd = 2,
                candidatesStart = 0,
                candidatesEnd = 2,
            ),
        )
        assertEquals("", realSelection.state.composing)
        assertTrue("a real range must set hasSelection", realSelection.state.hasSelection)

        // Switching editors resets both transient editing fields.
        val switched = KeyboardReducer.reduce(
            KeyboardUiState(hasSelection = true, editingToolsVisible = true),
            KeyboardAction.EditorInfoChanged(EditorInputInfo.from(0, 0), sessionId = "session-editing"),
        )
        assertFalse(switched.state.hasSelection)
        assertFalse(switched.state.editingToolsVisible)
    }

    private class Runtime(
        initialText: String = "",
        initialClipboard: String = "",
    ) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession(initialText = initialText, initialClipboard = initialClipboard)
        val controller = KeyboardController(scope, session, PinyinEngine.empty(), EditingAiService(), dispatcher)

        fun dispose() {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    private class EditingAiService : AiRewriteService {
        override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.NotInstalled)
        override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

        override fun refresh() = Unit

        override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
            Result.failure(UnsupportedOperationException("not used"))

        override fun deleteModel() = Unit
        override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = emptyFlow()
        override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = emptyFlow()
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
