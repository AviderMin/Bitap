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

/** P1 editing-tool coverage for bounded multi-step undo of IME commits. */
class KeyboardControllerUndoTest {

    @Test
    fun undoRemovesLastCommittedCandidate() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()
            assertEquals(listOf("你"), runtime.controller.state.value.candidates.map { it.word })

            runtime.controller.dispatch(KeyboardAction.CandidateTapped(0))
            assertEquals("你", runtime.session.text)
            assertTrue("candidate commit must offer undo", runtime.controller.state.value.canUndo)

            runtime.controller.dispatch(KeyboardAction.Undo)
            assertEquals("undo must remove the exact committed text", "", runtime.session.text)
            assertFalse(runtime.controller.state.value.canUndo)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun redoReappliesTheMostRecentUndoneCommit() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()
            runtime.controller.dispatch(KeyboardAction.CandidateTapped(0))

            runtime.controller.dispatch(KeyboardAction.Undo)
            assertEquals("", runtime.session.text)
            assertTrue("undo must offer redo", runtime.controller.state.value.canRedo)

            runtime.controller.dispatch(KeyboardAction.Redo)
            assertEquals("redo must restore the exact committed text", "你", runtime.session.text)
            assertTrue(runtime.controller.state.value.canUndo)
            assertFalse(runtime.controller.state.value.canRedo)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun multipleCommitsCanBeUndoneStepByStep() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()
            runtime.controller.dispatch(KeyboardAction.CandidateTapped(0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()
            runtime.controller.dispatch(KeyboardAction.CandidateTapped(0))
            assertEquals("你你", runtime.session.text)

            runtime.controller.dispatch(KeyboardAction.Undo)
            assertEquals("first undo removes the latest commit", "你", runtime.session.text)
            assertTrue(runtime.controller.state.value.canUndo)

            runtime.controller.dispatch(KeyboardAction.Undo)
            assertEquals("second undo removes the earlier commit", "", runtime.session.text)
            assertFalse(runtime.controller.state.value.canUndo)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun undoIsRejectedAfterExternalCaretMoveBeforeTheCommittedText() {
        val runtime = Runtime(initialText = "x")
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)
            runtime.controller.dispatch(KeyboardAction.AppendInput("x"))
            assertEquals("xx", runtime.session.text)
            assertTrue("the typed x must be undoable", runtime.controller.state.value.canUndo)

            // External cursor move before the second x. The commit end no longer
            // matches the caret, so undo must not delete either x.
            runtime.session.select(1, 1)
            runtime.controller.onSelectionChanged(1, 1, -1, -1, oldSelectionStart = 2, oldSelectionEnd = 2)
            runtime.controller.dispatch(KeyboardAction.Undo)

            assertEquals("undo must not touch text after a caret move", "xx", runtime.session.text)
            assertFalse("a caret move invalidates undo", runtime.controller.state.value.canUndo)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun undoStillWorksWhenOurOwnCommitSelectionCallbackArrives() {
        val runtime = Runtime(initialText = "x")
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.ToggleLanguage)
            runtime.controller.dispatch(KeyboardAction.AppendInput("y"))
            // IME-generated callback for the commit we just made (old caret 1 ->
            // new caret 2). It must not be mistaken for an external move.
            runtime.controller.onSelectionChanged(2, 2, -1, -1, oldSelectionStart = 1, oldSelectionEnd = 1)
            assertTrue("own commit callback must keep undo", runtime.controller.state.value.canUndo)

            runtime.controller.dispatch(KeyboardAction.Undo)
            assertEquals("undo removes the commit at the recorded end", "x", runtime.session.text)
        } finally {
            runtime.dispose()
        }
    }

    private inner class Runtime(initialText: String = "") {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession(initialText = initialText)
        val engine = PinyinEngine.fromLines(sequenceOf("ni\t你\t100"))
        val controller = KeyboardController(scope, session, engine, UndoAiService(), dispatcher)

        fun dispose() {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    private class UndoAiService : AiRewriteService {
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
