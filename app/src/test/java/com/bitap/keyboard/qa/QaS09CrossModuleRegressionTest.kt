package com.bitap.keyboard.qa

import android.net.Uri
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.core.Candidate
import com.bitap.keyboard.core.EditorInputInfo
import com.bitap.keyboard.core.EditorTextSnapshot
import com.bitap.keyboard.core.FileUserLexiconStore
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardLayer
import com.bitap.keyboard.core.PendingConfirmation
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.TextSession
import com.bitap.keyboard.ime.KeyboardController
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * S09 cross-module acceptance: S01-S08 through the real controller + a text
 * session that maintains the actual editor text/composing state, not only
 * command strings. Requires no Android runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QaS09CrossModuleRegressionTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(
        engine: PinyinEngine,
        session: FakeEditorSession,
        ai: FakeAiService = FakeAiService(),
    ): KeyboardController = KeyboardController(scope, session, engine, ai, dispatcher)

    private fun type(controller: KeyboardController, text: String) {
        text.forEach { char -> controller.dispatch(KeyboardAction.AppendInput(char.toString())) }
    }

    // ------------------------------------------------------------------
    // S01: switching layers must not lose a valid query result
    // ------------------------------------------------------------------

    @Test
    fun s01_layerSwitchKeepsFreshCandidatesAndSpaceCommitsRealText() {
        val session = FakeEditorSession()
        val engine = PinyinEngine.fromLines(sequenceOf("nihao	你好	500"))
        val keyboard = controller(engine, session)
        keyboard.onStartInput(EditorInputInfo(0, 0))

        type(keyboard, "nihao")
        scheduler.advanceUntilIdle()
        assertTrue(keyboard.state.value.candidates.isNotEmpty())

        keyboard.dispatch(KeyboardAction.ShowLayer(KeyboardLayer.Numbers))
        scheduler.advanceUntilIdle()
        assertEquals("nihao", keyboard.state.value.candidatesFor)
        assertTrue(keyboard.state.value.candidates.isNotEmpty())

        keyboard.dispatch(KeyboardAction.Space)
        assertEquals("你好", session.text)
        assertEquals("", session.composing)
        assertEquals("", keyboard.state.value.composing)
    }

    // ------------------------------------------------------------------
    // S02: pending confirmation must freeze its target and replay later keys
    // ------------------------------------------------------------------

    @Test
    fun s02_pendingSpaceDoesNotConsumeLaterLetters() {
        val session = FakeEditorSession()
        val engine = PinyinEngine.fromLines(sequenceOf("nihao	你好	500"))
        val keyboard = controller(engine, session)
        keyboard.onStartInput(EditorInputInfo(0, 0))

        type(keyboard, "nihao")
        scheduler.advanceTimeBy(10) // query still inside the 30 ms debounce
        keyboard.dispatch(KeyboardAction.Space)
        assertEquals(PendingConfirmation.Space, keyboard.state.value.pendingConfirmation)

        keyboard.dispatch(KeyboardAction.AppendInput("m"))
        keyboard.dispatch(KeyboardAction.AppendInput("a"))
        assertEquals(2, keyboard.state.value.queuedActions.size)

        scheduler.advanceUntilIdle()
        assertEquals("你好", session.text)
        assertEquals("ma", session.composing)
        assertEquals(null, keyboard.state.value.pendingConfirmation)
        assertTrue(keyboard.state.value.queuedActions.isEmpty())
    }

    @Test
    fun s02_secondPunctuationIsNotSwallowedWhilePending() {
        val session = FakeEditorSession()
        val engine = PinyinEngine.fromLines(sequenceOf("nihao	你好	500"))
        val keyboard = controller(engine, session)
        keyboard.onStartInput(EditorInputInfo(0, 0))

        type(keyboard, "nihao")
        scheduler.advanceTimeBy(10)
        keyboard.dispatch(KeyboardAction.AppendInput("，"))
        keyboard.dispatch(KeyboardAction.AppendInput("。"))
        assertEquals(1, keyboard.state.value.queuedActions.size)

        scheduler.advanceUntilIdle()
        assertEquals("你好，。", session.text)
        assertEquals("", keyboard.state.value.composing)
    }

    // ------------------------------------------------------------------
    // S03: partial candidates keep their unconsumed suffix
    // ------------------------------------------------------------------

    @Test
    fun s03_partialCandidatePunctuationKeepsTheSuffix() {
        val session = FakeEditorSession()
        val engine = PinyinEngine.fromLines(sequenceOf("nihao	你好	500"))
        val keyboard = controller(engine, session)
        keyboard.onStartInput(EditorInputInfo(0, 0))

        type(keyboard, "nihaox")
        keyboard.dispatch(
            KeyboardAction.ComposingUpdated(
                "nihaox",
                listOf(Candidate("你好", "nihao", 500, consumedInputLength = 5)),
            ),
        )
        keyboard.dispatch(KeyboardAction.AppendInput("，"))

        assertEquals("你好，", session.text)
        assertEquals("x", session.composing)
        assertEquals("x", keyboard.state.value.composing)
    }

    // ------------------------------------------------------------------
    // S04: clearing the active engine and its persistence
    // ------------------------------------------------------------------

    @Test
    fun s04_controllerClearRemovesMemoryAndPersistedEntries() {
        val file = tempLexiconFile()
        try {
            val engine = PinyinEngine.fromLines(
                emptySequence(),
                FileUserLexiconStore(file),
            )
            engine.recordSelection("天气", "tianqi", source = "ai", persist = true)
            assertTrue(engine.search("tianqi").map { it.word }.contains("天气"))
            assertTrue(FileUserLexiconStore(file).loadEntries().isNotEmpty())

            val keyboard = controller(engine, FakeEditorSession())
            keyboard.clearUserLexicon()

            assertEquals(0, engine.selectionCount("天气"))
            assertTrue(engine.search("tianqi").isEmpty())
            assertTrue(FileUserLexiconStore(file).loadEntries().isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun s04_storeClearInvalidatesRunningEngineSnapshot() {
        val file = tempLexiconFile()
        try {
            val engine = PinyinEngine.fromLines(
                emptySequence(),
                FileUserLexiconStore(file),
            )
            engine.recordSelection("天气", "tianqi", source = "ai", persist = true)
            assertTrue(engine.search("tianqi").map { it.word }.contains("天气"))

            // The UI path clears its own store instance; the running engine must
            // observe the generation change instead of reviving the old snapshot.
            FileUserLexiconStore(file).clear()

            assertTrue(engine.search("tianqi").isEmpty())
            assertEquals(0, engine.selectionCount("天气"))
        } finally {
            file.delete()
        }
    }

    // ------------------------------------------------------------------
    // S05: real dictionary partial candidates survive a large candidate set
    // ------------------------------------------------------------------

    @Test
    fun s05_realDictionaryOffersSingleWordPartialForLongInput() {
        val dictionary = File("src/main/assets/pinyin_dict.tsv")
        assumeTrue("bundled dictionary missing: " + dictionary.absolutePath, dictionary.isFile)
        val engine = dictionary.useLines { PinyinEngine.fromLines(it) }
        val candidate = engine.search("woaini").firstOrNull { it.word == "我" }
        assertNotNull("real dictionary must offer partial 我 for woaini", candidate)
        assertEquals(2, candidate!!.consumedInputLength)
    }

    @Test
    fun s05_partialCommitKeepsRemainingPinyinComposing() {
        val session = FakeEditorSession()
        val engine = PinyinEngine.fromLines(
            sequenceOf(
                "wo	我	300",
                "ai	爱	200",
                "ni	你	300",
            ),
        )
        val keyboard = controller(engine, session)
        keyboard.onStartInput(EditorInputInfo(0, 0))

        type(keyboard, "woaini")
        val candidates = engine.search("woaini")
        val partialIndex = candidates.indexOfFirst { it.word == "我" }
        assertTrue(partialIndex >= 0)
        keyboard.dispatch(KeyboardAction.ComposingUpdated("woaini", candidates))
        keyboard.dispatch(KeyboardAction.CandidateTapped(partialIndex))

        assertEquals("我", session.text)
        assertEquals("aini", session.composing)
    }

    // ------------------------------------------------------------------
    // S06: pending confirmation must learn like a direct candidate tap
    // ------------------------------------------------------------------

    @Test
    fun s06_pendingSpaceIsLearnedWithCorrectPronunciation() {
        val file = tempLexiconFile()
        try {
            val engine = PinyinEngine.fromLines(
                sequenceOf("nihao	你好	500"),
                FileUserLexiconStore(file),
            )
            val keyboard = controller(engine, FakeEditorSession())
            keyboard.onStartInput(EditorInputInfo(0, 0))

            type(keyboard, "nihao")
            scheduler.advanceTimeBy(10)
            keyboard.dispatch(KeyboardAction.Space)
            scheduler.advanceUntilIdle()

            assertEquals(1, engine.selectionCount("你好"))
            keyboard.dispose()
            val entry = FileUserLexiconStore(file).loadEntries().firstOrNull { it.word == "你好" }
            assertNotNull("pending selection should be persisted", entry)
            assertEquals("nihao", entry!!.pinyin)
        } finally {
            file.delete()
        }
    }

    // ------------------------------------------------------------------
    // S07: AI raw-context candidates replace the original text, not append
    // ------------------------------------------------------------------

    @Test
    fun s07_rawContextAiCandidateReplacesOriginalText() {
        val session = FakeEditorSession()
        session.text = "nihao"
        val ai = FakeAiService(suggestion = "你好")
        val keyboard = controller(PinyinEngine.empty(), session, ai)
        keyboard.onStartInput(EditorInputInfo(0, 0))

        keyboard.dispatch(KeyboardAction.RequestAiCandidates)
        scheduler.advanceUntilIdle()
        assertTrue(keyboard.state.value.aiCandidates.contains("你好"))

        keyboard.dispatch(KeyboardAction.AiCandidateTapped("你好"))
        assertEquals("你好", session.text)
        assertEquals("", session.composing)
        assertTrue(session.log.count { it == "deleteBackward" } == 5)
    }

    @Test
    fun s07_rawContextReplacementIsCancelledWhenTextMoved() {
        val session = FakeEditorSession()
        session.text = "nihao"
        val ai = FakeAiService(suggestion = "你好")
        val keyboard = controller(PinyinEngine.empty(), session, ai)
        keyboard.onStartInput(EditorInputInfo(0, 0))

        keyboard.dispatch(KeyboardAction.RequestAiCandidates)
        scheduler.advanceUntilIdle()
        session.text = "其他"
        keyboard.dispatch(KeyboardAction.AiCandidateTapped("你好"))

        assertEquals("其他", session.text)
        assertTrue(keyboard.state.value.aiCandidatesError?.contains("原输入已变化") == true)
    }

    // ------------------------------------------------------------------
    // S08: independent signed + decimal numeric capabilities
    // ------------------------------------------------------------------

    @Test
    fun s08_signedAndDecimalCombineToTypeNegativeDecimal() {
        val session = FakeEditorSession()
        val keyboard = controller(PinyinEngine.empty(), session)
        val info = EditorInputInfo.from(
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED,
            0,
        )
        keyboard.onStartInput(info)

        assertTrue(keyboard.state.value.numericSymbols.containsAll(listOf(".", "+", "-")))
        assertEquals("Decimal", keyboard.state.value.numericMode.name)

        "-12.5".forEach { char -> keyboard.dispatch(KeyboardAction.AppendInput(char.toString())) }
        assertEquals("-12.5", session.text)
        assertEquals("", keyboard.state.value.composing)
        assertFalse(keyboard.state.value.showLanguageToggle)
    }

    @Test
    fun s08_integerAndDateTimeExposeTheirOwnSymbols() {
        val integer = EditorInputInfo.from(InputType.TYPE_CLASS_NUMBER, 0)
        assertTrue(integer.numericSymbols.isEmpty())
        val dateTime = EditorInputInfo.from(InputType.TYPE_CLASS_DATETIME, 0)
        assertEquals("DateTime", dateTime.numericMode.name)
        assertTrue(dateTime.numericSymbols.containsAll(listOf("/", ":", "-")))
    }

    // ------------------------------------------------------------------
    // S09: long sentence from the real dictionary, actual editor text
    // ------------------------------------------------------------------

    @Test
    fun s09_longSentenceCandidateCommitsAsOneWholeText() {
        val dictionary = File("src/main/assets/pinyin_dict.tsv")
        assumeTrue("bundled dictionary missing: " + dictionary.absolutePath, dictionary.isFile)
        val engine = dictionary.useLines { PinyinEngine.fromLines(it) }
        val session = FakeEditorSession()
        val keyboard = controller(engine, session)
        keyboard.onStartInput(EditorInputInfo(0, 0))

        val input = "jintiantianqihenhao"
        type(keyboard, input)
        val candidates = engine.search(input)
        assertTrue(
            "expected whole-sentence candidate: " + candidates.take(10).map { it.word },
            candidates.any { it.word == "今天天气很好" },
        )
        scheduler.advanceUntilIdle()
        val index = keyboard.state.value.candidates.indexOfFirst { it.word == "今天天气很好" }
        assertTrue(index >= 0)
        keyboard.dispatch(KeyboardAction.CandidateTapped(index))
        assertEquals("今天天气很好", session.text)
        assertEquals("", session.composing)
    }

    private fun tempLexiconFile(): File = File(
        System.getProperty("java.io.tmpdir"),
        "bitap-qa-s09-" + UUID.randomUUID() + ".tsv",
    )

    private class FakeEditorSession : TextSession {
        var text: String = ""
        var composing: String = ""
        var selection: String? = null
        val log = CopyOnWriteArrayList<String>()

        override val isActive: Boolean = true

        override fun commitText(textToCommit: String) {
            log += "commit:" + textToCommit
            composing = ""
            text += textToCommit
        }

        override fun setComposingText(text: String) {
            log += "setComposing:" + text
            composing = text
        }

        override fun finishComposingText() {
            log += "finishComposing"
            if (composing.isNotEmpty()) {
                text += composing
                composing = ""
            }
        }

        override fun deleteBackward() {
            log += "deleteBackward"
            if (!selection.isNullOrEmpty()) {
                selection = null
            } else if (text.isNotEmpty()) {
                text = text.dropLast(1)
            }
        }

        override fun sendEnter() {
            log += "sendEnter"
            text += "\n"
        }

        override fun getSelectedText(): String? = selection

        override fun getTextBeforeCursor(maxChars: Int): String? =
            (text + composing).takeLast(maxChars).takeIf { it.isNotEmpty() }

        override fun getSurroundingText(maxChars: Int): EditorTextSnapshot =
            EditorTextSnapshot(selection, getTextBeforeCursor(maxChars))

        override fun readAiSource(maxChars: Int): String =
            (selection ?: (text + composing)).take(maxChars)
    }

    private class FakeAiService(private val suggestion: String = "你好") : AiRewriteService {
        override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.Ready)
        override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

        override fun refresh() = Unit

        override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
            Result.failure(UnsupportedOperationException("not used by unit tests"))

        override fun deleteModel() = Unit

        override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> =
            flowOf(AiEvent.Completed("ok"))

        override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> =
            flowOf(AiEvent.Completed(suggestion))

        override fun cancel() = Unit

        override fun close() = Unit
    }
}
