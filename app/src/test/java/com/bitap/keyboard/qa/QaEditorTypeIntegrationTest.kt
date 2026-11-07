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
import com.bitap.keyboard.core.EditorInputKind
import com.bitap.keyboard.core.EnterAction
import com.bitap.keyboard.core.EditorTextSnapshot
import com.bitap.keyboard.core.InputLanguage
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4 regression: editor input-type behaviour (number / phone / email / password)
 * and selection synchronisation, against the task-6 API (EditorInputInfo +
 * SelectionChanged). This is the "if ime-dev interface is ready" part of task-9.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QaEditorTypeIntegrationTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val session = FakeTextSession()
    private val controller = KeyboardController(scope, session, PinyinEngine.empty(), FakeAiService())

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // EditorInputInfo mapping
    // ------------------------------------------------------------------

    @Test
    fun numberFieldMapsToNumbersLayerAndEnglish() {
        val info = EditorInputInfo.from(InputType.TYPE_CLASS_NUMBER, 0)
        assertEquals(EditorInputKind.Number, info.kind)
        assertEquals(InputLanguage.English, info.preferredLanguage)
        assertEquals(KeyboardLayer.Numbers, info.preferredLayer)
        assertFalse(info.isPassword)
        assertTrue(info.aiContextAllowed)
    }

    @Test
    fun numericPasswordVariationMapsToPassword() {
        val info = EditorInputInfo.from(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            0,
        )
        assertEquals(EditorInputKind.Password, info.kind)
        assertTrue(info.isPassword)
        assertFalse(info.aiContextAllowed)
    }

    @Test
    fun phoneAndEmailKindsAreRecognised() {
        assertEquals(
            EditorInputKind.Phone,
            EditorInputInfo.from(InputType.TYPE_CLASS_PHONE, 0).kind,
        )
        assertEquals(
            EditorInputKind.Email,
            EditorInputInfo.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                0,
            ).kind,
        )
        assertEquals(
            EditorInputKind.Password,
            EditorInputInfo.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            ).kind,
        )
        assertEquals(
            EditorInputKind.Url,
            EditorInputInfo.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                0,
            ).kind,
        )
    }

    @Test
    fun enterActionsMapFromImeOptions() {
        assertEquals(
            EnterAction.Search,
            EditorInputInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH).enterAction,
        )
        assertEquals(
            EnterAction.Send,
            EditorInputInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND).enterAction,
        )
        assertEquals(
            EnterAction.Next,
            EditorInputInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT).enterAction,
        )
        assertEquals(
            EnterAction.Done,
            EditorInputInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE).enterAction,
        )
        assertEquals("搜索", EnterAction.Search.label)
    }

    // ------------------------------------------------------------------
    // Controller behaviour per editor type
    // ------------------------------------------------------------------

    @Test
    fun numberFieldStartsNumbersLayerAndCommitsDigitsDirectly() {
        controller.onStartInput(EditorInputInfo.from(InputType.TYPE_CLASS_NUMBER, 0))
        assertEquals(KeyboardLayer.Numbers, controller.state.value.layer)
        assertEquals(InputLanguage.English, controller.state.value.language)

        "123".forEach { digit -> controller.dispatch(KeyboardAction.AppendInput(digit.toString())) }
        assertEquals("123", session.log.joinToString("") { it.removePrefix("commit:") })
        assertEquals("", controller.state.value.composing)
    }

    @Test
    fun phoneFieldStartsNumbersLayer() {
        controller.onStartInput(EditorInputInfo.from(InputType.TYPE_CLASS_PHONE, 0))
        assertEquals(KeyboardLayer.Numbers, controller.state.value.layer)
        controller.dispatch(KeyboardAction.AppendInput("+"))
        assertEquals("commit:+", session.log.last())
    }

    @Test
    fun emailFieldIsEnglishOnlyAndNeverStartsPinyinComposing() {
        controller.onStartInput(
            EditorInputInfo.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                0,
            ),
        )
        assertEquals(InputLanguage.English, controller.state.value.language)
        assertEquals(KeyboardLayer.Letters, controller.state.value.layer)

        "a@b.com".forEach { char -> controller.dispatch(KeyboardAction.AppendInput(char.toString())) }
        assertEquals("a@b.com", session.log.joinToString("") { it.removePrefix("commit:") })
        assertEquals("", controller.state.value.composing)
        assertTrue(session.log.none { it.startsWith("setComposing:") })
    }

    @Test
    fun passwordFieldDisablesAiAndDoesNotHarvestSelection() {
        controller.onStartInput(
            EditorInputInfo.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            ),
        )
        assertTrue(controller.state.value.isPasswordInput)
        assertFalse(controller.state.value.aiContextAllowed)
        assertFalse(controller.state.value.aiEnabled)

        session.selected = "secret-text"
        controller.dispatch(KeyboardAction.AiPanelOpened())
        assertFalse(controller.state.value.ai.visible)
        assertEquals("", controller.state.value.ai.source)
    }

    @Test
    fun switchingFromChineseTextFieldToNumberFieldClearsCompositionAndCandidates() {
        controller.onStartInput(EditorInputInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        controller.dispatch(KeyboardAction.AppendInput("n"))
        controller.dispatch(
            KeyboardAction.ComposingUpdated("n", listOf(Candidate("你", "n", 10))),
        )
        assertEquals("n", controller.state.value.composing)

        controller.onStartInput(EditorInputInfo.from(InputType.TYPE_CLASS_NUMBER, 0))
        assertEquals("", controller.state.value.composing)
        assertTrue(controller.state.value.candidates.isEmpty())
        assertEquals(KeyboardLayer.Numbers, controller.state.value.layer)
        assertEquals(InputLanguage.English, controller.state.value.language)
    }

    // ------------------------------------------------------------------
    // Selection changes
    // ------------------------------------------------------------------

    @Test
    fun selectionOutsideCandidatesDropsComposingAndFinishesIt() {
        controller.onStartInput(EditorInputInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        controller.dispatch(KeyboardAction.AppendInput("n"))
        controller.dispatch(KeyboardAction.AppendInput("i"))
        controller.dispatch(
            KeyboardAction.ComposingUpdated("ni", listOf(Candidate("你", "ni", 100))),
        )

        controller.onSelectionChanged(
            selectionStart = 5,
            selectionEnd = 5,
            candidatesStart = 0,
            candidatesEnd = 2,
        )
        assertEquals("", controller.state.value.composing)
        assertTrue(controller.state.value.candidates.isEmpty())
        assertEquals("finishComposing", session.log.last())
    }

    @Test
    fun selectionInsideCandidatesKeepsComposing() {
        controller.onStartInput(EditorInputInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        controller.dispatch(KeyboardAction.AppendInput("n"))
        controller.dispatch(KeyboardAction.AppendInput("i"))
        controller.dispatch(
            KeyboardAction.ComposingUpdated("ni", listOf(Candidate("你", "ni", 100))),
        )

        controller.onSelectionChanged(
            selectionStart = 2,
            selectionEnd = 2,
            candidatesStart = 0,
            candidatesEnd = 2,
        )
        assertEquals("ni", controller.state.value.composing)
        assertEquals(1, controller.state.value.candidates.size)
        assertTrue(session.log.none { it == "finishComposing" })
    }

    @Test
    fun selectionChangeWithoutComposingIsIgnored() {
        controller.onStartInput(EditorInputInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        controller.onSelectionChanged(0, 0, -1, -1)
        assertTrue(session.log.isEmpty())
        assertEquals("", controller.state.value.composing)
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
