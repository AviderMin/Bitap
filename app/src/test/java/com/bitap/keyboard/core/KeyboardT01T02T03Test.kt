package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.ime.KeyboardController
import java.io.File
import java.util.UUID
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T01/T02/T03 regression coverage beyond the copied probes. */
class KeyboardT01T02T03Test {

    @Test
    fun threePendingConfirmationsPreserveEveryQueuedSuffixAndFinalText() {
        var state = KeyboardUiState(composing = "nihao")
        for (action in listOf(
            KeyboardAction.Space,
            KeyboardAction.AppendInput("m"),
            KeyboardAction.AppendInput("a"),
            KeyboardAction.Space,
            KeyboardAction.AppendInput("x"),
        )) state = KeyboardReducer.reduce(state, action).state

        val first = KeyboardReducer.reduce(
            state,
            KeyboardAction.ComposingUpdated("nihao", listOf(Candidate("你好", "nihao", 100))),
        )
        assertEquals("ma", first.state.composing)
        assertEquals(listOf(KeyboardAction.AppendInput("x")), first.state.queuedActions)

        val second = KeyboardReducer.reduce(
            first.state,
            KeyboardAction.ComposingUpdated("ma", listOf(Candidate("吗", "ma", 100))),
        )
        assertEquals("x", second.state.composing)
        assertEquals(null, second.state.pendingConfirmation)
        assertEquals(
            listOf(
                ImeCommand.CommitText("吗"),
                ImeCommand.SetComposingText("x"),
            ),
            second.commands,
        )
    }

    @Test
    fun repeatedNiStartsARealSecondQueryAndCanCommitAgain() {
        verifyRepeatedSegment(first = "ni", second = "ni")
    }

    @Test
    fun repeatedNihaoStartsARealSecondQueryAndCanCommitAgain() {
        verifyRepeatedSegment(first = "nihao", second = "nihao")
    }

    private fun verifyRepeatedSegment(first: String, second: String) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = T02TextSession()
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "ni\t你\t100",
                "nihao\t你好\t500",
            ),
        )
        val controller = KeyboardController(
            scope = scope,
            textSession = session,
            pinyinEngine = engine,
            aiService = T02AiService(),
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            first.forEach { controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            controller.dispatch(KeyboardAction.Space)
            // The second segment is typed while the first confirmation is still
            // waiting; it is queued and must start a fresh query after replay.
            second.forEach { controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            scheduler.advanceUntilIdle()

            assertTrue(
                "second segment must have candidates after replay: " + controller.state.value,
                controller.state.value.candidates.isNotEmpty(),
            )
            controller.dispatch(KeyboardAction.Space)
            assertEquals(2, session.commits.count { it == "你" || it == "你好" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun separatedPartialLearningUsesOnlyConsumedPronunciationAndDoesNotPolluteRebuild() {
        val file = File(
            System.getProperty("java.io.tmpdir"),
            "bitap-t03-" + UUID.randomUUID() + ".tsv",
        )
        try {
            val lines = sequenceOf(
                "xi\t西\t100",
                "an\t安\t100",
                "xian\t西安\t1000",
            )
            val store = FileUserLexiconStore(file)
            val engine = PinyinEngine.fromLines(lines, store)
            val candidates = engine.search("xi'an")
            val partialIndex = candidates.indexOfFirst { it.word == "西" }
            assertTrue(partialIndex >= 0)
            val state = KeyboardUiState(
                composing = "xi'an",
                candidatesFor = "xi'an",
                candidates = candidates,
                layer = KeyboardLayer.Letters,
            )
            val result = KeyboardReducer.reduce(state, KeyboardAction.CandidateTapped(partialIndex))
            val learned = result.learnedSelection
            assertNotNull(learned)
            assertEquals("西", learned!!.word)
            assertEquals("xi", learned.pinyin)

            engine.recordSelection(learned.word, learned.pinyin, learned.source)
            val rebuilt = PinyinEngine.fromLines(lines, FileUserLexiconStore(file))
            assertTrue(rebuilt.search("xi").any { it.word == "西" })
            assertTrue(
                "user entry must not gain a xian/whole-input pronunciation",
                rebuilt.search("xian").none { it.word == "西" && it.pinyin == "xian" },
            )
        } finally {
            file.delete()
        }
    }
}

private class T02TextSession : TextSession {
    val commits = mutableListOf<String>()
    override val isActive: Boolean = true

    override fun commitText(text: String) {
        commits += text
    }

    override fun setComposingText(text: String) = Unit
    override fun finishComposingText() = Unit
    override fun deleteBackward() = Unit
    override fun sendEnter() = Unit
    override fun getSelectedText(): String? = null
    override fun getTextBeforeCursor(maxChars: Int): String? = null
    override fun getSurroundingText(maxChars: Int): EditorTextSnapshot = EditorTextSnapshot(null, null)
    override fun readAiSource(maxChars: Int): String = ""
}

private class T02AiService : AiRewriteService {
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
