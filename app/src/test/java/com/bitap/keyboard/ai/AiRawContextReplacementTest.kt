package com.bitap.keyboard.ai

import android.net.Uri
import com.bitap.keyboard.ai.internal.AiRawContextResolver
import com.bitap.keyboard.core.EditorTextSnapshot
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.TextSession
import com.bitap.keyboard.ime.KeyboardController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiRawContextResolverTest {

    @Test
    fun resolvesCommittedPinyinBeforeCursor() {
        val plan = AiRawContextResolver.resolve(selected = null, beforeCursor = "你好nihao")
        assertEquals("nihao", plan?.query)
        assertEquals("你好", plan?.context)
        assertEquals("nihao", plan?.original)
        assertEquals(false, plan?.selected)
    }

    @Test
    fun resolvesSelectedPinyin() {
        val plan = AiRawContextResolver.resolve(selected = "xi'an", beforeCursor = "前缀")
        assertEquals("xian", plan?.query)
        assertEquals("xi'an", plan?.original)
        assertEquals(true, plan?.selected)
    }

    @Test
    fun rejectsChineseSelectionAndMissingTrailingPinyin() {
        assertNull(AiRawContextResolver.resolve(selected = "你好", beforeCursor = "你好"))
        assertNull(AiRawContextResolver.resolve(selected = null, beforeCursor = "你好"))
        assertNull(AiRawContextResolver.resolve(selected = null, beforeCursor = null))
    }

    @Test
    fun capsRawContextAndQuery() {
        val longContext = "前".repeat(300) + "nihao"
        val plan = AiRawContextResolver.resolve(selected = null, beforeCursor = longContext)
        assertEquals("nihao", plan?.query)
        assertEquals(160, plan?.context?.length)

        val tooLong = "a".repeat(AiRawContextResolver.MAX_QUERY_CHARS + 1)
        assertNull(AiRawContextResolver.resolve(selected = null, beforeCursor = tooLong))
    }
}

class AiRawContextReplacementTest {

    @Test
    fun replacesCommittedPinyinInsteadOfAppending() {
        val session = EditableTextSession("nihao")
        val engine = PinyinEngine.fromLines(sequenceOf("nihao\t你好\t100"))
        val controller = newController(session, engine)
        try {
            controller.controller.dispatch(KeyboardAction.RequestAiCandidates)
            controller.scheduler.advanceUntilIdle()
            assertEquals(listOf("你好"), controller.controller.state.value.aiCandidates)

            controller.controller.dispatch(KeyboardAction.AiCandidateTapped("你好"))
            assertEquals("你好", session.text)
            assertEquals("你好", session.getTextBeforeCursor(10))
            assertEquals(1, engine.selectionCount("你好"))
        } finally {
            controller.scope.cancel()
        }
    }

    @Test
    fun replacesOnlyTrailingPinyinWithPrecedingText() {
        val session = EditableTextSession("你好nihao")
        val controller = newController(session, PinyinEngine.empty())
        try {
            controller.controller.dispatch(KeyboardAction.RequestAiCandidates)
            controller.scheduler.advanceUntilIdle()
            controller.controller.dispatch(KeyboardAction.AiCandidateTapped("你好"))
            assertEquals("你好你好", session.text)
        } finally {
            controller.scope.cancel()
        }
    }

    @Test
    fun replacesSelectedPinyin() {
        val session = EditableTextSession("nihao", selectionStart = 0, selectionEnd = 5)
        val controller = newController(session, PinyinEngine.empty())
        try {
            controller.controller.dispatch(KeyboardAction.RequestAiCandidates)
            controller.scheduler.advanceUntilIdle()
            controller.controller.dispatch(KeyboardAction.AiCandidateTapped("你好"))
            assertEquals("你好", session.text)
        } finally {
            controller.scope.cancel()
        }
    }

    @Test
    fun changedEditorCancelsReplacement() {
        val session = EditableTextSession("nihao")
        val controller = newController(session, PinyinEngine.empty())
        try {
            controller.controller.dispatch(KeyboardAction.RequestAiCandidates)
            controller.scheduler.advanceUntilIdle()
            session.replaceText("somethingelse")
            controller.controller.dispatch(KeyboardAction.AiCandidateTapped("你好"))
            assertEquals("somethingelse", session.text)
        } finally {
            controller.scope.cancel()
        }
    }

    private fun newController(session: EditableTextSession, engine: PinyinEngine): ControllerBundle {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = KeyboardController(scope, session, engine, CannedAiService("你好"), dispatcher)
        return ControllerBundle(scope, controller, scheduler)
    }

    private data class ControllerBundle(
        val scope: CoroutineScope,
        val controller: KeyboardController,
        val scheduler: TestCoroutineScheduler,
    )
}

private class CannedAiService(private val reply: String) : com.bitap.keyboard.ai.AiRewriteService {
    override val runtimeState: StateFlow<com.bitap.keyboard.ai.AiRuntimeState> =
        MutableStateFlow(com.bitap.keyboard.ai.AiRuntimeState.Ready)
    override val modelInfo: StateFlow<com.bitap.keyboard.ai.AiModelInfo> =
        MutableStateFlow(com.bitap.keyboard.ai.AiModelInfo())

    override fun refresh() = Unit

    override suspend fun importModel(uri: Uri): Result<com.bitap.keyboard.ai.AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() = Unit

    override fun rewrite(text: String, tone: com.bitap.keyboard.ai.RewriteTone): Flow<com.bitap.keyboard.ai.AiEvent> =
        emptyFlow()

    override fun suggestCandidates(pinyin: String, context: String): Flow<com.bitap.keyboard.ai.AiEvent> =
        flowOf(com.bitap.keyboard.ai.AiEvent.Started, com.bitap.keyboard.ai.AiEvent.Completed(reply))

    override fun cancel() = Unit

    override fun close() = Unit
}

private class EditableTextSession(
    initialText: String,
    private var selectionStart: Int = initialText.length,
    private var selectionEnd: Int = initialText.length,
) : TextSession {
    var text: String = initialText
        private set

    override val isActive: Boolean = true

    fun replaceText(newText: String) {
        text = newText
        selectionStart = newText.length
        selectionEnd = newText.length
    }

    override fun commitText(text: String) {
        val start = selectionStart.coerceIn(0, this.text.length)
        val end = selectionEnd.coerceIn(start, this.text.length)
        this.text = this.text.substring(0, start) + text + this.text.substring(end)
        selectionStart = start + text.length
        selectionEnd = selectionStart
    }

    override fun setComposingText(text: String) = Unit

    override fun finishComposingText() = Unit

    override fun deleteBackward() {
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        if (start != end) {
            text = text.substring(0, start) + text.substring(end)
            selectionStart = start
            selectionEnd = start
        } else if (start > 0) {
            text = text.substring(0, start - 1) + text.substring(start)
            selectionStart = start - 1
            selectionEnd = start - 1
        }
    }

    override fun sendEnter() = Unit

    override fun getSelectedText(): String? =
        if (selectionStart != selectionEnd) text.substring(selectionStart, selectionEnd) else null

    override fun getTextBeforeCursor(maxChars: Int): String? {
        if (maxChars <= 0) return null
        val end = selectionStart.coerceIn(0, text.length)
        return text.substring((end - maxChars).coerceAtLeast(0), end)
    }

    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot =
        EditorTextSnapshot(getSelectedText(), getTextBeforeCursor(maxChars))

    override fun readAiSource(maxChars: Int): String =
        getSelectedText() ?: getTextBeforeCursor(maxChars)?.trimEnd().orEmpty()
}
