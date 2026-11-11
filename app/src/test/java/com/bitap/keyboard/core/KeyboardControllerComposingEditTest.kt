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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1: editing tools that touch raw editor text must first finish an active
 * composition, otherwise CommitRaw can resurrect discarded composing text or
 * the UI keeps stale candidates.
 */
class KeyboardControllerComposingEditTest {

    @Test
    fun deleteWordEndsActiveCompositionWithoutResurrection() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()
            runtime.controller.dispatch(
                KeyboardAction.ComposingUpdated("ni", listOf(Candidate("你", "ni", 100))),
            )
            assertEquals("ni", runtime.controller.state.value.composing)
            assertEquals(1, runtime.controller.state.value.candidates.size)

            runtime.controller.dispatch(KeyboardAction.DeleteWord)

            assertEquals("DeleteWord must remove the composing text once", "", runtime.session.text)
            assertEquals("", runtime.controller.state.value.composing)
            assertTrue(runtime.controller.state.value.candidates.isEmpty())
            assertEquals("", runtime.controller.state.value.candidatesFor)

            runtime.controller.dispatch(KeyboardAction.CommitRaw)
            assertEquals(
                "CommitRaw must not resurrect a composition discarded by DeleteWord",
                "",
                runtime.session.text,
            )
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun pasteEndsActiveCompositionWithoutResurrection() {
        val runtime = Runtime(initialClipboard = "XY")
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()
            assertEquals("ni", runtime.session.text)

            runtime.controller.dispatch(KeyboardAction.Paste)

            assertEquals("niXY", runtime.session.text)
            assertEquals("", runtime.controller.state.value.composing)
            assertTrue(runtime.controller.state.value.candidates.isEmpty())

            runtime.controller.dispatch(KeyboardAction.CommitRaw)
            assertEquals(
                "CommitRaw must not append the composing text after Paste",
                "niXY",
                runtime.session.text,
            )
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun moveCursorEndsActiveCompositionWithoutResurrection() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()

            runtime.controller.dispatch(KeyboardAction.MoveCursor(-1))

            assertEquals("ni", runtime.session.text)
            assertEquals(1, runtime.session.cursor)
            assertEquals("", runtime.controller.state.value.composing)

            runtime.controller.dispatch(KeyboardAction.CommitRaw)
            assertEquals("ni", runtime.session.text)
            assertEquals(1, runtime.session.cursor)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun selectAllCutPasteEndsCompositionAndIgnoresStaleCallback() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()
            runtime.controller.dispatch(
                KeyboardAction.ComposingUpdated("ni", listOf(Candidate("你", "ni", 100))),
            )

            runtime.controller.dispatch(KeyboardAction.SelectAll)
            assertEquals("SelectAll must not edit the composing text", "ni", runtime.session.text)
            assertTrue(runtime.controller.state.value.hasSelection)
            assertEquals("", runtime.controller.state.value.composing)

            runtime.controller.dispatch(KeyboardAction.CutSelection)
            assertEquals("", runtime.session.text)
            assertEquals("ni", runtime.session.clipboardText)

            runtime.controller.dispatch(KeyboardAction.Paste)
            assertEquals("ni", runtime.session.text)

            // A delayed callback with the pre-edit composing values must not
            // bring the discarded composition or its candidates back.
            runtime.controller.onSelectionChanged(2, 2, 0, 2, oldSelectionStart = 0, oldSelectionEnd = 0)
            assertEquals("", runtime.controller.state.value.composing)
            assertTrue(runtime.controller.state.value.candidates.isEmpty())

            runtime.controller.dispatch(KeyboardAction.CommitRaw)
            assertEquals("ni", runtime.session.text)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun editingWithoutHostCallbackStillEndsComposition() {
        val runtime = Runtime()
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()

            // No onSelectionChanged at all before the edit or before CommitRaw.
            runtime.controller.dispatch(KeyboardAction.MoveCursorByWord(-1))

            assertEquals("ni", runtime.session.text)
            assertEquals(0, runtime.session.cursor)
            assertEquals("", runtime.controller.state.value.composing)

            runtime.controller.dispatch(KeyboardAction.CommitRaw)
            assertEquals("ni", runtime.session.text)
            assertEquals(0, runtime.session.cursor)
        } finally {
            runtime.dispose()
        }
    }

    @Test
    fun fullyIdenticalHostCallbackAfterEditDoesNotResurrectState() {
        val runtime = Runtime(initialClipboard = "XY")
        try {
            runtime.controller.onStartInput(EditorInputInfo.from(0, 0))
            runtime.controller.dispatch(KeyboardAction.AppendInput("ni"))
            runtime.scheduler.advanceUntilIdle()
            // Establish the pre-edit snapshot through a normal composing callback.
            runtime.controller.onSelectionChanged(2, 2, 0, 2, oldSelectionStart = 0, oldSelectionEnd = 0)
            assertEquals("ni", runtime.controller.state.value.composing)

            runtime.controller.dispatch(KeyboardAction.Paste)
            assertEquals("niXY", runtime.session.text)
            assertEquals("", runtime.controller.state.value.composing)

            // The exact same callback after the edit is a no-op, never a revive.
            runtime.controller.onSelectionChanged(2, 2, 0, 2, oldSelectionStart = 0, oldSelectionEnd = 0)
            assertEquals("", runtime.controller.state.value.composing)
            assertTrue(runtime.controller.state.value.candidates.isEmpty())

            runtime.controller.dispatch(KeyboardAction.CommitRaw)
            assertEquals("niXY", runtime.session.text)
        } finally {
            runtime.dispose()
        }
    }

    private class Runtime(
        initialText: String = "",
        initialClipboard: String = "",
    ) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession(initialText = initialText, initialClipboard = initialClipboard)
        val controller = KeyboardController(scope, session, PinyinEngine.empty(), NoOpAiService(), dispatcher)

        fun dispose() {
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
