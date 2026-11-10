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

/** P1 editing-tool coverage for plain cursor movement. */
class KeyboardControllerCursorTest {

    @Test
    fun arrowActionsMoveCursorWithoutChangingText() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = TestEditorTextSession(initialText = "你好")
        val controller = KeyboardController(scope, session, PinyinEngine.empty(), CursorAiService(), dispatcher)
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.MoveCursor(-1))
            assertEquals("left arrow moves one character", 1, session.cursor)
            controller.dispatch(KeyboardAction.MoveCursor(1))
            assertEquals("right arrow returns to the end", 2, session.cursor)
            assertEquals("cursor movement must not edit text", "你好", session.text)
        } finally {
            runCatching { controller.dispose() }
            scope.cancel()
        }
    }

    private class CursorAiService : AiRewriteService {
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
