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

/** R12: decoder cancellation, bounded input, and rapid typing/backspace sanity. */
class KeyboardR12Test {

    @Test
    fun cancellationCheckStopsPrefixScanning() {
        val lines = (1..300).asSequence().map { index ->
            "a" + "b".repeat(index) + "\t词" + index + "\t" + (1000 - index)
        }
        val engine = PinyinEngine.fromLines(lines)
        var checks = 0
        val result = engine.search("a", shouldContinue = { checks++ < 2 })
        assertTrue("cancelled search should not return a full candidate page", result.isEmpty())
        assertTrue("cancellation callback was not consulted", checks >= 3)
    }

    @Test
    fun overlongInputIsBoundedAndQuick() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "a\t啊\t100",
                "ab\t阿\t100",
            ),
        )
        val started = System.nanoTime()
        val result = engine.search("a".repeat(100))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("overlong query should be empty", result.isEmpty())
        assertTrue("overlong query took " + elapsedMs + "ms", elapsedMs < 500)
    }

    @Test
    fun rapidTwentyFiftyHundredLetterTypingAndBackspaceRemainConsistent() {
        listOf(20, 50, 100).forEach { length ->
            val scheduler = TestCoroutineScheduler()
            val dispatcher = StandardTestDispatcher(scheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val session = R12TextSession()
            val controller = KeyboardController(
                scope = scope,
                textSession = session,
                pinyinEngine = PinyinEngine.fromLines(emptySequence()),
                aiService = R12AiService(),
                queryDispatcher = dispatcher,
            )
            try {
                controller.onStartInput(EditorInputInfo.from(0, 0))
                val started = System.nanoTime()
                repeat(length) { controller.dispatch(KeyboardAction.AppendInput("a")) }
                assertEquals(length, controller.state.value.composing.length)
                scheduler.advanceUntilIdle()
                assertEquals(length, controller.state.value.composing.length)
                repeat(length) { controller.dispatch(KeyboardAction.Backspace) }
                assertEquals("", controller.state.value.composing)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("rapid typing " + length + " took " + elapsedMs + "ms", elapsedMs < 3_000)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun singleOverlongActionCanBeDeleted() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = KeyboardController(
            scope = scope,
            textSession = R12TextSession(),
            pinyinEngine = PinyinEngine.fromLines(emptySequence()),
            aiService = R12AiService(),
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("a".repeat(100)))
            assertEquals(100, controller.state.value.composing.length)
            repeat(100) { controller.dispatch(KeyboardAction.Backspace) }
            assertEquals("", controller.state.value.composing)
            scheduler.advanceUntilIdle()
            assertEquals("", controller.state.value.composing)
        } finally {
            scope.cancel()
        }
    }
}

private class R12TextSession : TextSession {
    override val isActive: Boolean = true

    override fun commitText(text: String) = Unit

    override fun setComposingText(text: String) = Unit

    override fun finishComposingText() = Unit

    override fun deleteBackward() = Unit

    override fun sendEnter() = Unit

    override fun getSelectedText(): String? = null

    override fun getTextBeforeCursor(maxChars: Int): String? = null

    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot = EditorTextSnapshot(null, null)

    override fun readAiSource(maxChars: Int): String = ""
}

private class R12AiService : AiRewriteService {
    override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.Ready)
    override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

    override fun refresh() = Unit

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() = Unit

    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> = emptyFlow()

    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> = emptyFlow()

    override fun cancel() = Unit

    override fun close() = Unit
}
