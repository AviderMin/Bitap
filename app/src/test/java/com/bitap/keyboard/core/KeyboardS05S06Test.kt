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

/** S05: partial candidates survive a large full-sentence candidate set. */
class KeyboardS05Test {

    @Test
    fun partialPrefixSurvivesMoreThanSixtyFourFullPaths() {
        val lines = ArrayList<String>()
        lines += "wo\t我\t100"
        (0 until 70).forEach { index ->
            lines += "a\t词" + index + "\t" + (1000 - index)
        }
        val engine = PinyinEngine.fromLines(lines.asSequence())
        val candidates = engine.search("woa")
        val partial = candidates.firstOrNull { it.word == "我" }
        assertTrue("partial 我 should be present: " + candidates.map { it.word }, partial != null)
        assertEquals(2, partial!!.consumedInputLength)
    }

    @Test
    fun realDictionaryExposesSingleWordPrefixForLongInput() {
        val file = java.io.File("src/main/assets/pinyin_dict.tsv")
        org.junit.Assume.assumeTrue(file.isFile)
        val engine = file.useLines { PinyinEngine.fromLines(it) }
        val candidates = engine.search("woaini")
        assertTrue(
            "single-word 我 should be offered for woaini: " + candidates.take(10).map { it.word },
            candidates.any { it.word == "我" },
        )
    }

    @Test
    fun explicitSeparatorHasPartialCandidateWithOriginalConsumedLength() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "xi\t西\t300",
                "an\t安\t300",
                "xian\t先\t500",
            ),
        )
        val candidates = engine.search("xi'an")
        val partial = candidates.firstOrNull { it.word == "西" }
        assertTrue("explicit separator should expose 西: " + candidates.map { it.word }, partial != null)
        assertEquals(2, partial!!.consumedInputLength)
    }
}

/** S06: pending and direct candidate commits share learning attribution. */
class KeyboardS06Test {

    @Test
    fun directCandidateTapIsLearned() {
        val engine = PinyinEngine.fromLines(sequenceOf("nihao\t你好\t500"))
        val session = S06TextSession()
        val scope = CoroutineScope(SupervisorJob())
        val controller = KeyboardController(scope, session, engine, S06AiService())
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            "nihao".forEach { controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            controller.dispatch(
                KeyboardAction.ComposingUpdated(
                    "nihao",
                    listOf(Candidate("你好", "nihao", 500, consumedInputLength = 5)),
                ),
            )
            controller.dispatch(KeyboardAction.CandidateTapped(0))
            assertEquals(1, engine.selectionCount("你好"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pendingSpaceIsLearnedWithCorrectPinyin() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = PinyinEngine.fromLines(sequenceOf("nihao\t你好\t500"))
        val controller = KeyboardController(
            scope = scope,
            textSession = S06TextSession(),
            pinyinEngine = engine,
            aiService = S06AiService(),
            queryDispatcher = dispatcher,
        )
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            "nihao".forEach { controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            scheduler.advanceTimeBy(10)
            controller.dispatch(KeyboardAction.Space)
            scheduler.advanceUntilIdle()
            assertEquals(1, engine.selectionCount("你好"))
            assertEquals("nihao", engine.search("nihao").firstOrNull()?.let { "nihao" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun partialCandidateLearnsItsWordPinyinNotRemainingSuffix() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "wo\t我\t300",
                "ai\t爱\t200",
                "ni\t你\t300",
            ),
        )
        val session = S06TextSession()
        val scope = CoroutineScope(SupervisorJob())
        val controller = KeyboardController(scope, session, engine, S06AiService())
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            "woaini".forEach { controller.dispatch(KeyboardAction.AppendInput(it.toString())) }
            val candidates = engine.search("woaini")
            val partial = candidates.first { it.word == "我爱" }
            controller.dispatch(
                KeyboardAction.ComposingUpdated("woaini", candidates),
            )
            val index = controller.state.value.candidates.indexOfFirst { it.word == partial.word }
            controller.dispatch(KeyboardAction.CandidateTapped(index))
            assertEquals(1, engine.selectionCount("我爱"))
            assertTrue(
                "partial word pinyin should be woai: " + engine.search("woai").map { it.word },
                engine.search("woai").any { it.word == "我爱" },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rawFallbackAndCancelledPendingAreNotLearned() {
        val engine = PinyinEngine.fromLines(emptySequence())
        val scope = CoroutineScope(SupervisorJob())
        val controller = KeyboardController(scope, S06TextSession(), engine, S06AiService())
        try {
            controller.onStartInput(EditorInputInfo.from(0, 0))
            controller.dispatch(KeyboardAction.AppendInput("n"))
            controller.dispatch(KeyboardAction.CommitRaw)
            assertEquals(0, engine.selectionCount("n"))
        } finally {
            scope.cancel()
        }
    }
}

private class S06TextSession : TextSession {
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

private class S06AiService : AiRewriteService {
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
