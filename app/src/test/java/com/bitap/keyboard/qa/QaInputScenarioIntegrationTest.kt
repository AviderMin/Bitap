package com.bitap.keyboard.qa

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.core.Candidate
import com.bitap.keyboard.core.EditorTextSnapshot
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardLayer
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.TextSession
import com.bitap.keyboard.ime.KeyboardController
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4 independent regression at the controller + TextSession boundary: what the
 * editor actually receives for continuous pinyin, punctuation, stale candidates,
 * last-letter backspace, and number / email / password style entry.
 *
 * InputType-specific behaviour is not yet exposed by ime-dev (task-6); the
 * number/email/password cases below exercise the current API (layer + language)
 * and are labelled accordingly in the task-9 gap report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QaInputScenarioIntegrationTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val session = FakeTextSession()
    private val engine = PinyinEngine.fromLines(
        sequenceOf(
            "wo	我	300",
            "ai	爱	200",
            "ni	你	300",
            "nihao	你好	80",
            "hao	好	200",
            "ma	吗	100",
        ),
    )
    private val controller = KeyboardController(scope, session, engine, FakeAiService())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun continuousPinyinSentenceCommitsWholeSentenceThroughSession() {
        val typed = "woaini"
        typed.forEach { letter -> controller.dispatch(KeyboardAction.AppendInput(letter.toString())) }
        assertEquals(typed, controller.state.value.composing)

        controller.dispatch(KeyboardAction.ComposingUpdated(typed, engine.search(typed)))
        assertEquals("我爱你", controller.state.value.candidates.first().word)

        controller.dispatch(KeyboardAction.Space)
        assertEquals("commit:我爱你", session.log.last())
        assertEquals("", controller.state.value.composing)
        assertTrue(controller.state.value.candidates.isEmpty())
    }

    @Test
    fun punctuationCommitsCandidateThenPunctuationInOrder() {
        controller.dispatch(KeyboardAction.AppendInput("n"))
        controller.dispatch(KeyboardAction.AppendInput("i"))
        controller.dispatch(KeyboardAction.ComposingUpdated("ni", listOf(Candidate("你", "ni", 100))))
        controller.dispatch(KeyboardAction.AppendInput("，"))

        assertEquals(
            listOf("setComposing:n", "setComposing:ni", "commit:你", "commit:，"),
            session.log,
        )
        assertEquals("", controller.state.value.composing)
    }

    @Test
    fun staleCandidatesAreNotSubmittedBySpace() {
        controller.dispatch(KeyboardAction.AppendInput("n"))
        controller.dispatch(KeyboardAction.AppendInput("i"))
        controller.dispatch(KeyboardAction.ComposingUpdated("ni", listOf(Candidate("你", "ni", 100))))
        // Type one more letter: the old candidate stays visible but is stale.
        controller.dispatch(KeyboardAction.AppendInput("h"))
        assertEquals(1, controller.state.value.candidates.size)

        controller.dispatch(KeyboardAction.Space)
        assertEquals("commit:nih", session.log.last())
        assertEquals("", controller.state.value.composing)
    }

    @Test
    fun lastLetterBackspaceFinishesThenDeletesOnSession() {
        controller.dispatch(KeyboardAction.AppendInput("n"))
        controller.dispatch(KeyboardAction.Backspace)

        assertEquals(listOf("setComposing:n", "finishComposing", "deleteBackward"), session.log)
        assertEquals("", controller.state.value.composing)
    }

    @Test
    fun numberLayerCommitsDigitsDirectlyWithoutComposing() {
        controller.dispatch(KeyboardAction.ShowLayer(KeyboardLayer.Numbers))
        "13800138000".forEach { digit -> controller.dispatch(KeyboardAction.AppendInput(digit.toString())) }

        assertTrue(session.log.all { it.startsWith("commit:") })
        assertEquals("13800138000", session.log.joinToString("") { it.removePrefix("commit:") })
        assertEquals("", controller.state.value.composing)
    }

    @Test
    fun phoneNumberCharactersCommitDirectly() {
        controller.dispatch(KeyboardAction.ShowLayer(KeyboardLayer.Numbers))
        listOf("+", "8", "6", "1", "3", "8", "0", "0", "1", "3", "8", "0", "0", "0").forEach { char ->
            controller.dispatch(KeyboardAction.AppendInput(char))
        }
        assertEquals("+8613800138000", session.log.joinToString("") { it.removePrefix("commit:") })
    }

    @Test
    fun emailLikeCharactersCommitDirectlyInEnglishMode() {
        controller.dispatch(KeyboardAction.ToggleLanguage)
        "a@b.com".forEach { char -> controller.dispatch(KeyboardAction.AppendInput(char.toString())) }
        assertEquals("a@b.com", session.log.joinToString("") { it.removePrefix("commit:") })
        assertEquals("", controller.state.value.composing)
    }

    @Test
    fun passwordLikeCharactersCommitLiterallyInEnglishMode() {
        controller.dispatch(KeyboardAction.ToggleLanguage)
        val password = "P@ssw0rd-2026!"
        password.forEach { char -> controller.dispatch(KeyboardAction.AppendInput(char.toString())) }
        assertEquals(password, session.log.joinToString("") { it.removePrefix("commit:") })
        assertEquals("", controller.state.value.composing)
    }

    @Test
    fun aiPanelReadsLatestSelectionOnEachOpen() {
        controller.onAiRuntimeChanged(AiRuntimeState.Ready)
        session.selected = "第一句"
        controller.dispatch(KeyboardAction.AiPanelOpened())
        assertEquals("第一句", controller.state.value.ai.source)

        controller.dispatch(KeyboardAction.AiDismiss)
        session.selected = "第二句"
        controller.dispatch(KeyboardAction.AiPanelOpened())
        assertEquals("第二句", controller.state.value.ai.source)
    }

    private class FakeTextSession : TextSession {
        val log = CopyOnWriteArrayList<String>()
        var selected: String? = null
        var beforeCursor: String? = null

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

        override fun getSelectedText(): String? = selected

        override fun getTextBeforeCursor(maxChars: Int): String? = beforeCursor

        override fun getSurroundingText(maxChars: Int): EditorTextSnapshot =
            EditorTextSnapshot(selected, beforeCursor)

        override fun readAiSource(maxChars: Int): String =
            (selected ?: beforeCursor ?: "").take(maxChars)
    }

    private class FakeAiService : AiRewriteService {
        override val runtimeState: StateFlow<AiRuntimeState> = MutableStateFlow(AiRuntimeState.NotInstalled)
        override val modelInfo: StateFlow<AiModelInfo> = MutableStateFlow(AiModelInfo())

        override fun refresh() = Unit

        override suspend fun importModel(uri: Uri): Result<AiModelInfo> =
            Result.failure(UnsupportedOperationException("not used by unit tests"))

        override fun deleteModel() = Unit

        override fun rewrite(text: String, tone: RewriteTone): Flow<AiEvent> =
            flowOf(AiEvent.Completed("ok"))

        override fun suggestCandidates(pinyin: String, context: String): Flow<AiEvent> =
            flowOf(AiEvent.Completed("你好\n您好"))

        override fun cancel() = Unit

        override fun close() = Unit
    }
}
