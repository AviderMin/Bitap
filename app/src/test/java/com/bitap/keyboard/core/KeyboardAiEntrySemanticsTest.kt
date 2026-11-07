package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.ime.KeyboardController
import com.bitap.keyboard.ime.aiEntryContentDescription
import com.bitap.keyboard.ime.aiEntryLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0/P1 semantics: the ✦ AI entry is a local AI *lexicon candidate* generator
 * whose input is pinyin only. It must never imply that selecting Chinese text
 * starts a text rewrite.
 */
class KeyboardAiEntrySemanticsTest {

    @Test
    fun noComposingNoSelectionShowsActionablePinyinOnlyMessage() {
        val service = RecordingAiService()
        val bundle = newController(TestEditorTextSession("今天的天气"), service)
        try {
            bundle.controller.dispatch(KeyboardAction.RequestAiCandidates)
            bundle.scheduler.advanceUntilIdle()

            assertFalse(bundle.controller.state.value.aiCandidatesLoading)
            assertTrue(bundle.controller.state.value.aiCandidates.isEmpty())
            assertEquals(
                KeyboardReducer.AI_LEXICON_QUERY_REQUIRED_MESSAGE,
                bundle.controller.state.value.aiCandidatesError,
            )
            assertEquals(0, service.suggestCalls)
        } finally {
            bundle.scope.cancel()
        }
    }

    @Test
    fun chineseSelectionShowsSelectionNotPinyinMessageWithoutRequest() {
        val service = RecordingAiService()
        val session = TestEditorTextSession("你好世界", initialSelectionStart = 0, initialSelectionEnd = 4)
        val bundle = newController(session, service)
        try {
            bundle.controller.dispatch(KeyboardAction.RequestAiCandidates)
            bundle.scheduler.advanceUntilIdle()

            assertEquals(
                KeyboardReducer.AI_LEXICON_SELECTION_NOT_PINYIN_MESSAGE,
                bundle.controller.state.value.aiCandidatesError,
            )
            assertFalse(bundle.controller.state.value.aiCandidatesLoading)
            assertTrue(bundle.controller.state.value.aiCandidates.isEmpty())
            assertEquals("must not call the AI model for a Chinese selection", 0, service.suggestCalls)
        } finally {
            bundle.scope.cancel()
        }
    }

    @Test
    fun pinyinSelectionUsesRawReplacementPath() {
        val service = RecordingAiService(reply = "你好")
        val session = TestEditorTextSession("ni hao", initialSelectionStart = 0, initialSelectionEnd = 6)
        val bundle = newController(session, service)
        try {
            bundle.controller.dispatch(KeyboardAction.RequestAiCandidates)
            bundle.scheduler.advanceUntilIdle()

            assertEquals("nihao", service.lastPinyin)
            assertEquals(listOf("你好"), bundle.controller.state.value.aiCandidates)
            assertFalse(bundle.controller.state.value.aiCandidatesLoading)

            bundle.controller.dispatch(KeyboardAction.AiCandidateTapped("你好"))
            assertEquals("你好", session.text)
            assertEquals(2, session.cursor)
            assertNull(session.getSelectedText())
        } finally {
            bundle.scope.cancel()
        }
    }

    @Test
    fun normalComposingUsesComposingPinyin() {
        val service = RecordingAiService(reply = "你好")
        val session = TestEditorTextSession()
        val bundle = newController(session, service)
        try {
            "nihao".forEach { char ->
                bundle.controller.dispatch(KeyboardAction.AppendInput(char.toString()))
            }
            bundle.scheduler.advanceUntilIdle()
            assertEquals("nihao", bundle.controller.state.value.composing)

            bundle.controller.dispatch(KeyboardAction.RequestAiCandidates)
            bundle.scheduler.advanceUntilIdle()

            assertEquals("nihao", service.lastPinyin)
            assertEquals(listOf("你好"), bundle.controller.state.value.aiCandidates)

            bundle.controller.dispatch(KeyboardAction.AiCandidateTapped("你好"))
            assertEquals("你好", session.text)
            assertEquals(2, session.cursor)
            assertEquals("", bundle.controller.state.value.composing)
        } finally {
            bundle.scope.cancel()
        }
    }

    @Test
    fun aiEntryButtonSemanticsDescribeLocalLexiconInsteadOfRewrite() {
        assertEquals("AI 词库候选（只根据拼音）", aiEntryContentDescription(aiLoading = false, aiReady = true))
        assertEquals("取消 AI 词库请求", aiEntryContentDescription(aiLoading = true, aiReady = true))
        assertEquals("AI 词库未就绪", aiEntryContentDescription(aiLoading = false, aiReady = false))
        assertEquals("取消 AI", aiEntryLabel(aiLoading = true))
        assertEquals("✦ AI", aiEntryLabel(aiLoading = false))
    }

    @Test
    fun reducerStoresAiLexiconPromptAsCandidateError() {
        val state = KeyboardUiState(
            aiCandidates = listOf("旧候选"),
            aiCandidatesFor = "ni",
            aiCandidatesLoading = true,
        )
        val result = KeyboardReducer.reduce(
            state,
            KeyboardAction.AiCandidatesFailed(KeyboardReducer.AI_LEXICON_QUERY_REQUIRED_MESSAGE),
        )
        assertFalse(result.state.aiCandidatesLoading)
        assertTrue(result.state.aiCandidates.isEmpty())
        assertEquals("", result.state.aiCandidatesFor)
        assertEquals(
            KeyboardReducer.AI_LEXICON_QUERY_REQUIRED_MESSAGE,
            result.state.aiCandidatesError,
        )
    }

    private fun newController(
        session: TestEditorTextSession,
        service: RecordingAiService,
    ): ControllerBundle {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            pinyinEngine = PinyinEngine.empty(),
            aiService = service,
            queryDispatcher = dispatcher,
        )
        controller.onStartInput(EditorInputInfo.from(0, 0))
        controller.onAiRuntimeChanged(AiRuntimeState.Ready)
        return ControllerBundle(scope, controller, scheduler)
    }

    private data class ControllerBundle(
        val scope: CoroutineScope,
        val controller: KeyboardController,
        val scheduler: TestCoroutineScheduler,
    )
}

private class RecordingAiService(
    private val reply: String = "你好",
) : AiRewriteService {
    var suggestCalls = 0
        private set
    var lastPinyin: String? = null
        private set
    var lastContext: String? = null
        private set

    override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.Ready)
    override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo(installed = true))

    override fun refresh() = Unit

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() = Unit

    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = emptyFlow()

    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = flow {
        suggestCalls += 1
        lastPinyin = pinyin
        lastContext = context
        emit(AiEvent.Started)
        emit(AiEvent.Completed(reply))
    }

    override fun cancel() = Unit

    override fun close() = Unit
}
