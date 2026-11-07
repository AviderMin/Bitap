package com.bitap.keyboard.core

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.ime.KeyboardController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PinyinEngineTest {

    private fun engine(): PinyinEngine = PinyinEngine.fromLines(
        sequenceOf(
            "# Bitap test dictionary",
            "",
            "ni\t你\t100",
            "ni\t泥\t50",
            "ni\t你\t10",
            "nihao\t你好\t80",
            "nihaoma\t你好吗\t20",
            "hao\t好\t200",
        ),
    )

    @Test
    fun exactPinyinReturnsWordFirst() {
        val candidates = engine().search("nihao")
        assertFalse(candidates.isEmpty())
        assertEquals("你好", candidates.first().word)
    }

    @Test
    fun singleCharacterCandidatesAreAvailable() {
        val candidates = engine().search("ni")
        val words = candidates.map { it.word }
        assertTrue(words.contains("你"))
        assertTrue(words.contains("泥"))
        assertEquals("你", candidates.first().word)
    }

    @Test
    fun prefixOfLongerPinyinFindsWord() {
        val candidates = engine().search("niha")
        assertTrue(candidates.map { it.word }.contains("你好"))
    }

    @Test
    fun queryIsCaseInsensitiveAndTrimsSpaces() {
        val candidates = engine().search("  NIHAO  ")
        assertEquals("你好", candidates.first().word)
    }

    @Test
    fun noMatchReturnsEmptyList() {
        assertTrue(engine().search("zzzz").isEmpty())
        assertTrue(engine().search("").isEmpty())
    }

    @Test
    fun limitIsRespectedAndNeverAboveMaxCandidates() {
        val lines = (1..80).map { index -> "ni\t字" + index + "\t" + (1000 - index) }
        val many = PinyinEngine.fromLines(lines.asSequence())
        assertEquals(2, many.search("ni", limit = 2).size)
        assertEquals(PinyinEngine.MAX_CANDIDATES, many.search("ni", limit = 100).size)
    }

    @Test
    fun userSelectionReordersExactCandidates() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "ni\t你\t100",
                "ni\t泥\t50",
                "ni\t尼\t10",
            ),
        )
        assertEquals("你", engine.search("ni").first().word)

        engine.recordSelection("泥")
        assertEquals(1, engine.selectionCount("泥"))
        assertEquals("泥", engine.search("ni").first().word)
    }

    @Test
    fun userSelectedPrefixWordOvertakesExactCandidate() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "ni\t你\t100",
                "nihao\t你好\t80",
            ),
        )
        assertEquals("你", engine.search("ni").first().word)

        engine.recordSelection("你好")
        assertEquals("你好", engine.search("ni").first().word)
    }

    @Test
    fun userSelectedPhraseOvertakesExactCandidate() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "wo\t我\t300",
                "ai\t爱\t200",
                "ha\t哈\t50",
                "woaiha\t窝爱哈\t100",
            ),
        )
        val before = engine.search("woaiha")
        assertTrue(before.map { it.word }.contains("我爱哈"))
        assertEquals("窝爱哈", before.first().word)

        engine.recordSelection("我爱哈")
        assertEquals("我爱哈", engine.search("woaiha").first().word)
    }

    @Test
    fun selectionMemoryAccumulatesPerWordAndCanBeCleared() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "ni\t你\t100",
                "ni\t泥\t50",
            ),
        )
        engine.recordSelection("泥")
        engine.recordSelection("泥")
        assertEquals(2, engine.selectionCount("泥"))
        assertEquals(0, engine.selectionCount("你"))
        assertEquals("泥", engine.search("ni").first().word)

        engine.clearSelectionMemory()
        assertEquals(0, engine.selectionCount("泥"))
        assertEquals("你", engine.search("ni").first().word)
    }

    @Test
    fun malformedLinesAreIgnored() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "# comment",
                "not-a-tsv-line",
                "ab\t",
                "\tword\t1",
                "ha\t哈\t5",
            ),
        )
        assertEquals(1, engine.entryCount)
        assertEquals("哈", engine.search("ha").first().word)
    }

    @Test
    fun duplicateWordsAreDeduplicated() {
        val candidates = engine().search("ni", limit = 8)
        assertEquals(1, candidates.count { it.word == "你" })
    }

    @Test
    fun phraseSegmentationBuildsContinuousCandidates() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "wo\t我\t300",
                "ai\t爱\t200",
                "ni\t你\t300",
                "xiang\t想\t200",
                "chi\t吃\t200",
                "fan\t饭\t150",
                "chifan\t吃饭\t100",
                "jintian\t今天\t100",
                "tianqi\t天气\t90",
                "hen\t很\t200",
                "hao\t好\t200",
                "zhongguo\t中国\t200",
                "ren\t人\t200",
            ),
        )
        assertTrue(engine.search("woaini").map { it.word }.contains("我爱你"))
        assertTrue(engine.search("woxiangchifan").map { it.word }.contains("我想吃饭"))
        assertTrue(engine.search("jintiantianqihenhao").map { it.word }.contains("今天天气很好"))
        assertTrue(engine.search("zhongguoren").map { it.word }.contains("中国人"))
    }

    @Test
    fun exactMatchesAreRankedBeforePrefixMatches() {
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "ni\t你\t10",
                "nihao\t你好\t999999",
            ),
        )
        assertEquals("你", engine.search("ni").first().word)
    }

    @Test
    fun realDictionaryContainsNihao() {
        val dictionary = File("src/main/assets/pinyin_dict.tsv")
        assumeTrue("bundled dictionary not found at " + dictionary.absolutePath, dictionary.isFile)
        val engine = dictionary.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue(engine.isReady)
        assertTrue(engine.entryCount > 10_000)
        val candidates = engine.search("nihao")
        assertTrue(candidates.map { it.word }.contains("你好"))
        assertTrue(engine.search("ni").isNotEmpty())
        assertTrue(engine.search("zzzzzz").isEmpty())
        val daily = mapOf(
            "woaini" to "我爱你",
            "woxiangchifan" to "我想吃饭",
            "jintiantianqihenhao" to "今天天气很好",
            "zhongguoren" to "中国人",
            "zaijian" to "再见",
        )
        daily.forEach { (input, expected) ->
            val words = engine.search(input).map { it.word }
            assertTrue("$input -> $words should contain $expected", words.contains(expected))
        }
        assertEquals("再见", engine.search("zaijian").first().word)
        assertEquals("今天天气很好", engine.search("jintiantianqihenhao").first().word)
    }
}

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
