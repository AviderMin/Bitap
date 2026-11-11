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
import org.junit.Test

/**
 * P1 regression: a composing-end selection callback must not be deduped just
 * because the caret did not move; the candidate region is part of the snapshot.
 */
class KeyboardControllerComposingEndTest {

    @Test
    fun composingEndWithoutCaretMoveClearsComposingAndCommitRawDoesNotDuplicate() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession()
        val controller = KeyboardController(scope, session, PinyinEngine.empty(), NoOpAiService(), dispatcher)
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("ni"))
            scheduler.advanceUntilIdle()
            assertEquals("ni", controller.state.value.composing)

            // Normal composing callback: caret at the composing end.
            controller.onSelectionChanged(2, 2, 0, 2, oldSelectionStart = 0, oldSelectionEnd = 0)
            assertEquals("normal composing callback must keep the composition", "ni", controller.state.value.composing)

            // Host ends composing without moving the caret; only the candidate
            // region changes (0..2 -> -1..-1), so the callback must be handled.
            session.finishComposingText()
            controller.onSelectionChanged(2, 2, -1, -1, oldSelectionStart = 2, oldSelectionEnd = 2)

            assertEquals(
                "composing-end callback must clear composing even when the caret did not move",
                "",
                controller.state.value.composing,
            )

            controller.dispatch(KeyboardAction.CommitRaw)
            assertEquals("CommitRaw must replace the host composing text instead of appending it", "ni", session.text)
        } finally {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    @Test
    fun fullyIdenticalRepeatedCallbackRemainsNoOp() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession()
        val controller = KeyboardController(scope, session, PinyinEngine.empty(), NoOpAiService(), dispatcher)
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("ni"))
            scheduler.advanceUntilIdle()
            controller.onSelectionChanged(2, 2, 0, 2, oldSelectionStart = 0, oldSelectionEnd = 0)
            assertEquals("ni", controller.state.value.composing)

            // The same complete snapshot again must stay a no-op: composing,
            // editor text and the following input path are unchanged.
            controller.onSelectionChanged(2, 2, 0, 2, oldSelectionStart = 2, oldSelectionEnd = 2)
            assertEquals("ni", controller.state.value.composing)
            assertEquals("ni", session.text)

            controller.dispatch(KeyboardAction.AppendInput("a"))
            assertEquals("nia", controller.state.value.composing)
            assertEquals("nia", session.text)
        } finally {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    @Test
    fun composingEndThenPasteInsertsOnlyOnce() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession(initialClipboard = "XY")
        val controller = KeyboardController(scope, session, PinyinEngine.empty(), NoOpAiService(), dispatcher)
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("ni"))
            scheduler.advanceUntilIdle()
            controller.onSelectionChanged(2, 2, 0, 2, oldSelectionStart = 0, oldSelectionEnd = 0)
            session.finishComposingText()
            controller.onSelectionChanged(2, 2, -1, -1, oldSelectionStart = 2, oldSelectionEnd = 2)
            assertEquals("", controller.state.value.composing)

            controller.dispatch(KeyboardAction.Paste)

            assertEquals(
                "paste after the host ended composing must not duplicate the composing text",
                "niXY",
                session.text,
            )
            assertEquals(4, session.cursor)
            assertEquals("", controller.state.value.composing)
        } finally {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    @Test
    fun composingEndThenCursorMoveKeepsText() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession()
        val controller = KeyboardController(scope, session, PinyinEngine.empty(), NoOpAiService(), dispatcher)
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("ni"))
            scheduler.advanceUntilIdle()
            controller.onSelectionChanged(2, 2, 0, 2, oldSelectionStart = 0, oldSelectionEnd = 0)
            session.finishComposingText()
            controller.onSelectionChanged(2, 2, -1, -1, oldSelectionStart = 2, oldSelectionEnd = 2)
            assertEquals("", controller.state.value.composing)

            controller.dispatch(KeyboardAction.MoveCursor(-1))

            assertEquals("cursor movement after the host ended composing must not edit text", "ni", session.text)
            assertEquals(1, session.cursor)
            assertEquals("", controller.state.value.composing)
        } finally {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    @Test
    fun composingEndThenCutSelectionEditsExactlyOnce() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession(initialText = "abcdef")
        val controller = KeyboardController(scope, session, PinyinEngine.empty(), NoOpAiService(), dispatcher)
        try {
            // Start composing over a real selection: setComposingText replaces it.
            session.select(2, 4)
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("ni"))
            scheduler.advanceUntilIdle()
            assertEquals("composing must replace the selected text", "abnief", session.text)

            controller.onSelectionChanged(4, 4, 2, 4, oldSelectionStart = 2, oldSelectionEnd = 2)
            session.finishComposingText()
            controller.onSelectionChanged(4, 4, -1, -1, oldSelectionStart = 4, oldSelectionEnd = 4)
            assertEquals("", controller.state.value.composing)

            session.select(0, 2)
            controller.onSelectionChanged(0, 2, -1, -1, oldSelectionStart = 4, oldSelectionEnd = 4)
            controller.dispatch(KeyboardAction.CutSelection)

            // Composing replaced "cd" with "ni" ("abnief"), so cutting the
            // leading "ab" must leave exactly "nief" — one deletion, no re-edit.
            assertEquals("cut after the host ended composing must edit exactly once", "nief", session.text)
            assertEquals("ab", session.clipboardText)
            assertEquals("", controller.state.value.composing)
        } finally {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    private class NoOpAiService : AiRewriteService {
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
