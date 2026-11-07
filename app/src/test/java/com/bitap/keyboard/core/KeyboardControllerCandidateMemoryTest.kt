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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controller-level candidate memory: both a local candidate tap and an AI lexicon
 * candidate tap must be recorded in the shared PinyinEngine session memory.
 */
class KeyboardControllerCandidateMemoryTest {

    @Test
    fun localCandidateTapIsRecordedInEngineMemory() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "ni\t你\t100",
                "ni\t泥\t50",
            ),
        )
        val scope = CoroutineScope(SupervisorJob())
        val controller = KeyboardController(scope, RecordingTextSession(), engine, StubAiService())
        try {
            controller.onStartInput(EditorInputInfo())
            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(
                KeyboardAction.ComposingUpdated(
                    "n",
                    listOf(Candidate("你", "ni", 100), Candidate("泥", "ni", 50)),
                ),
            )
            controller.dispatch(KeyboardAction.CandidateTapped(1))
            assertEquals(1, engine.selectionCount("泥"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aiCandidateTapIsRecordedInEngineMemory() {
        val engine = PinyinEngine.fromLines(sequenceOf("ni\t你\t100"))
        val scope = CoroutineScope(SupervisorJob())
        val controller = KeyboardController(scope, RecordingTextSession(), engine, StubAiService())
        try {
            controller.dispatch(KeyboardAction.AiCandidateTapped("你好"))
            assertEquals(1, engine.selectionCount("你好"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun spaceWithin0To29MsWaitsForFreshQueryAndNeverCommitsRawPinyin() {
        listOf(0L, 10L, 29L).forEach { elapsedMs ->
            val scheduler = TestCoroutineScheduler()
            val dispatcher = StandardTestDispatcher(scheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = PinyinEngine.fromLines(sequenceOf("nihao\t你好\t500"))
            val session = RecordingTextSession()
            val controller = KeyboardController(scope, session, engine, StubAiService(), dispatcher)
            try {
                controller.onStartInput(EditorInputInfo())
                "nihao".forEach { char ->
                    controller.dispatch(KeyboardAction.AppendInput(char.toString()))
                }
                scheduler.advanceTimeBy(elapsedMs)
                controller.dispatch(KeyboardAction.Space)
                assertTrue(
                    "space at " + elapsedMs + "ms must not commit before query",
                    session.log.none { it.startsWith("commit:") },
                )
                assertEquals("nihao", controller.state.value.composing)

                scheduler.advanceUntilIdle()
                assertEquals("commit:你好", session.log.last())
                assertEquals("", controller.state.value.composing)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun selectionInsideCompositionEndsSafelyThroughControllerWithoutDelete() {
        val engine = PinyinEngine.fromLines(sequenceOf("ni\t你\t100"))
        val scope = CoroutineScope(SupervisorJob())
        val session = RecordingTextSession()
        val controller = KeyboardController(scope, session, engine, StubAiService())
        try {
            controller.onStartInput(EditorInputInfo())
            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(KeyboardAction.AppendInput("i"))
            controller.dispatch(
                KeyboardAction.ComposingUpdated("ni", listOf(Candidate("你", "ni", 100))),
            )
            controller.onSelectionChanged(
                selectionStart = 1,
                selectionEnd = 1,
                candidatesStart = 0,
                candidatesEnd = 2,
            )
            assertEquals("", controller.state.value.composing)
            assertEquals("finishComposing", session.log.last())
            assertTrue(session.log.none { it == "deleteBackward" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleCandidateTapIsNotRecorded() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "ni\t你\t100",
                "nihao\t你好\t80",
            ),
        )
        val scope = CoroutineScope(SupervisorJob())
        val controller = KeyboardController(scope, RecordingTextSession(), engine, StubAiService())
        try {
            controller.onStartInput(EditorInputInfo())
            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(
                KeyboardAction.ComposingUpdated(
                    "n",
                    listOf(Candidate("你", "n", 100)),
                ),
            )
            controller.dispatch(KeyboardAction.AppendInput("i")) // composing becomes "ni", candidatesFor "n"
            controller.dispatch(KeyboardAction.CandidateTapped(0))
            assertEquals(0, engine.selectionCount("你"))
        } finally {
            scope.cancel()
        }
    }
}

private class RecordingTextSession : TextSession {
    val log = mutableListOf<String>()

    override val isActive: Boolean = true

    override fun commitText(text: String) {
        log += "commit:" + text
    }

    override fun setComposingText(text: String) {
        log += "setComposing:" + text
    }

    override fun finishComposingText() {
        log += "finishComposing"
    }

    override fun deleteBackward() {
        log += "deleteBackward"
    }

    override fun sendEnter() {
        log += "sendEnter"
    }

    override fun getSelectedText(): String? = null

    override fun getTextBeforeCursor(maxChars: Int): String? = null

    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot = EditorTextSnapshot(null, null)

    override fun readAiSource(maxChars: Int): String = ""
}

private class StubAiService : AiRewriteService {
    override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.NotInstalled)
    override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

    override fun refresh() = Unit

    override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
        Result.failure(UnsupportedOperationException("not used by unit tests"))

    override fun deleteModel() = Unit

    override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> =
        flowOf(AiEvent.Completed("ok"))

    override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> =
        flowOf(AiEvent.Completed("你好"))

    override fun cancel() = Unit

    override fun close() = Unit
}
