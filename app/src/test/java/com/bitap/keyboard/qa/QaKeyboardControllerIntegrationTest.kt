package com.bitap.keyboard.qa

import android.net.Uri
import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.core.AiStatus
import com.bitap.keyboard.core.Candidate
import com.bitap.keyboard.core.EditorTextSnapshot
import com.bitap.keyboard.core.InputLanguage
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.TextSession
import com.bitap.keyboard.ime.KeyboardController
import com.bitap.keyboard.settings.BitapSettings
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-module acceptance: core reducer -> KeyboardController -> TextSession ordering.
 * Uses a FakeTextSession per docs/06-quality-bar.md section 4 ("TextSessionTest").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QaKeyboardControllerIntegrationTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val session = FakeTextSession()
    private val controller = KeyboardController(scope, session, PinyinEngine.empty(), FakeAiService())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun chineseTypingCommitsSelectedCandidateThroughTextSession() {
        controller.dispatch(KeyboardAction.AppendInput("n"))
        controller.dispatch(KeyboardAction.AppendInput("i"))
        controller.dispatch(
            KeyboardAction.ComposingUpdated("ni", listOf(Candidate("你", "ni", 100L))),
        )
        controller.dispatch(KeyboardAction.Space)

        assertEquals(listOf("setComposing:n", "setComposing:ni", "commit:你"), session.log)
        assertEquals("", controller.state.value.composing)
        assertTrue(controller.state.value.candidates.isEmpty())
    }

    @Test
    fun backspaceWalksComposingThenDeletesBackward() {
        controller.dispatch(KeyboardAction.AppendInput("a"))
        controller.dispatch(KeyboardAction.AppendInput("b"))
        controller.dispatch(KeyboardAction.Backspace)
        controller.dispatch(KeyboardAction.Backspace)
        controller.dispatch(KeyboardAction.Backspace)

        assertEquals(
            listOf(
                "setComposing:a",
                "setComposing:ab",
                "setComposing:a",
                "finishComposing",
                "deleteBackward",
                "deleteBackward",
            ),
            session.log,
        )
    }

    @Test
    fun enterCommitsComposingThenSendsEnterWhenIdle() {
        controller.dispatch(KeyboardAction.AppendInput("h"))
        controller.dispatch(KeyboardAction.Enter)
        controller.dispatch(KeyboardAction.Enter)

        assertEquals(listOf("setComposing:h", "commit:h", "sendEnter"), session.log)
    }

    @Test
    fun languageToggleCommitsPendingComposing() {
        controller.dispatch(KeyboardAction.AppendInput("n"))
        controller.dispatch(KeyboardAction.AppendInput("i"))
        controller.dispatch(KeyboardAction.ToggleLanguage)

        assertEquals(listOf("setComposing:n", "setComposing:ni", "commit:ni"), session.log)
        assertEquals(InputLanguage.English, controller.state.value.language)
        assertEquals("", controller.state.value.composing)
    }

    @Test
    fun aiPanelReadsSessionSelectionWhenSourceIsEmpty() {
        session.selected = "最近一句"
        controller.onAiRuntimeChanged(AiRuntimeState.Ready)
        controller.dispatch(KeyboardAction.AiPanelOpened())

        val panel = controller.state.value.ai
        assertTrue(panel.visible)
        assertEquals("最近一句", panel.source)
        assertEquals(AiStatus.Idle, panel.status)
    }

    @Test
    fun aiPanelStaysClosedWhenSettingIsOff() {
        controller.onSettingsChanged(BitapSettings(aiEnabled = false))
        controller.dispatch(KeyboardAction.AiPanelOpened("hello"))

        assertFalse(controller.state.value.ai.visible)
        assertTrue(controller.state.value.ai.source.isEmpty())
    }

    @Test
    fun finishInputClearsComposingAndFinishesComposition() {
        controller.dispatch(KeyboardAction.AppendInput("n"))
        controller.onFinishInput()

        assertEquals(listOf("setComposing:n", "finishComposing"), session.log)
        assertEquals("", controller.state.value.composing)
        assertTrue(controller.state.value.candidates.isEmpty())
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
