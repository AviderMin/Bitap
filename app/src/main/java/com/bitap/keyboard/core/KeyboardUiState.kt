package com.bitap.keyboard.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.bitap.keyboard.ai.RewriteTone

/** Current input language. */
enum class InputLanguage {
    Chinese,
    English,
}

/** Visible keyboard layer. */
enum class KeyboardLayer {
    Letters,
    Numbers,
    Symbols,
}

/** Shift key state; Locked stays until the next tap. */
enum class ShiftState {
    Off,
    Shifted,
    Locked,
}

/**
 * High-level editor type derived from [EditorInfo.inputType]. It drives the
 * initial language/layer and the AI privacy policy in [KeyboardReducer].
 */
enum class EditorInputKind {
    Text,
    Number,
    Phone,
    Email,
    Url,
    Password,
}

/** Enter-key semantics derived from [EditorInfo.imeOptions]. */
enum class EnterAction(val label: String) {
    Enter("回车"),
    Go("前往"),
    Search("搜索"),
    Send("发送"),
    Next("下一步"),
    Previous("上一个"),
    Done("完成"),
}

/**
 * Snapshot of the currently focused editor's input configuration.
 *
 * The IME service converts EditorInfo into this immutable value and passes it to
 * KeyboardController; the pure reducer uses [preferredLanguage], [preferredLayer]
 * and [aiContextAllowed] to adapt the keyboard without touching the visual layer.
 */
data class EditorInputInfo(
    val inputType: Int = 0,
    val imeOptions: Int = 0,
    val kind: EditorInputKind = EditorInputKind.Text,
    val enterAction: EnterAction = EnterAction.Enter,
) {
    val isPassword: Boolean
        get() = kind == EditorInputKind.Password

    /** Password fields must never expose selected/cursor context to the AI panel. */
    val aiContextAllowed: Boolean
        get() = !isPassword

    /** IME_FLAG_NO_PERSONALIZED_LEARNING and password fields forbid learning. */
    val allowsPersonalizedLearning: Boolean
        get() = !isPassword &&
            (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0

    val learningAllowed: Boolean
        get() = allowsPersonalizedLearning

    /** Only normal text fields enter pinyin composing; the other types force English. */
    val allowsChineseInput: Boolean
        get() = kind == EditorInputKind.Text

    val preferredLanguage: InputLanguage
        get() = if (allowsChineseInput) InputLanguage.Chinese else InputLanguage.English

    val preferredLayer: KeyboardLayer
        get() = when (kind) {
            EditorInputKind.Number, EditorInputKind.Phone -> KeyboardLayer.Numbers
            EditorInputKind.Password ->
                if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER) {
                    KeyboardLayer.Numbers
                } else {
                    KeyboardLayer.Letters
                }

            else -> KeyboardLayer.Letters
        }

    companion object {
        fun from(inputType: Int, imeOptions: Int): EditorInputInfo = EditorInputInfo(
            inputType = inputType,
            imeOptions = imeOptions,
            kind = resolveKind(inputType),
            enterAction = resolveEnterAction(imeOptions),
        )

        private fun resolveKind(inputType: Int): EditorInputKind {
            return when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_NUMBER ->
                    if ((inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                        EditorInputKind.Password
                    } else {
                        EditorInputKind.Number
                    }

                InputType.TYPE_CLASS_PHONE -> EditorInputKind.Phone
                InputType.TYPE_CLASS_DATETIME -> EditorInputKind.Number
                InputType.TYPE_CLASS_TEXT -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    -> EditorInputKind.Email

                    InputType.TYPE_TEXT_VARIATION_URI -> EditorInputKind.Url
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    -> EditorInputKind.Password

                    else -> EditorInputKind.Text
                }

                else -> EditorInputKind.Text
            }
        }

        private fun resolveEnterAction(imeOptions: Int): EnterAction {
            if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.Enter
            return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_GO -> EnterAction.Go
                EditorInfo.IME_ACTION_SEARCH -> EnterAction.Search
                EditorInfo.IME_ACTION_SEND -> EnterAction.Send
                EditorInfo.IME_ACTION_NEXT -> EnterAction.Next
                EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.Previous
                EditorInfo.IME_ACTION_DONE -> EnterAction.Done
                else -> EnterAction.Enter
            }
        }
    }
}

/** A confirmation action waiting for the latest composing query result. */
sealed interface PendingConfirmation {
    data object Space : PendingConfirmation

    data class Candidate(val index: Int) : PendingConfirmation

    data class Punctuation(val text: String) : PendingConfirmation
}

/** One pinyin candidate.
 *
 * [consumedInputLength] is the number of composing characters this candidate
 * covers. 0 means the whole current composition (legacy callers); phrase
 * candidates may cover only a prefix so the rest stays editable and queryable.
 */
data class Candidate(
    val word: String,
    val pinyin: String = "",
    val frequency: Long = 0L,
    val consumedInputLength: Int = 0,
)

/** AI panel lifecycle inside the keyboard. */
enum class AiStatus {
    Idle,
    Thinking,
    Streaming,
    Completed,
    Failed,
    Unavailable,
}

/**
 * Everything the AI panel renders. The panel never inserts text on its own:
 * [canInsert] becomes true only after an explicit user request completed.
 */
data class AiPanelUiState(
    val visible: Boolean = false,
    val source: String = "",
    val tone: RewriteTone? = null,
    val status: AiStatus = AiStatus.Idle,
    val result: String = "",
    val error: String? = null,
) {
    val isRunning: Boolean
        get() = status == AiStatus.Thinking || status == AiStatus.Streaming

    val canInsert: Boolean
        get() = status == AiStatus.Completed && result.isNotBlank()
}

/**
 * Single source of truth for the whole keyboard. Immutable and produced only by
 * [KeyboardReducer], which keeps the state machine unit-testable on the JVM.
 */
data class KeyboardUiState(
    val language: InputLanguage = InputLanguage.Chinese,
    val layer: KeyboardLayer = KeyboardLayer.Letters,
    val shift: ShiftState = ShiftState.Off,
    val composing: String = "",
    /** Pinyin string the current [candidates] were generated for. */
    val candidatesFor: String = "",
    val candidates: List<Candidate> = emptyList(),
    /** Set when Space/Candidate/Punctuation waits for a fresh composing query. */
    val pendingConfirmation: PendingConfirmation? = null,
    /** Current editor configuration; reset on every onStartInput. */
    val editorInfo: EditorInputInfo = EditorInputInfo(),
    val ai: AiPanelUiState = AiPanelUiState(),
    val aiEnabled: Boolean = true,
    val aiReady: Boolean = false,
    val aiCandidates: List<String> = emptyList(),
    /** Composing text the current [aiCandidates] belong to; empty for raw-context requests. */
    val aiCandidatesFor: String = "",
    val aiCandidatesLoading: Boolean = false,
    val aiCandidatesError: String? = null,
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val keyboardHeightScale: Float = 1.0f,
    val lastShiftTapAt: Long = 0L,
) {
    /** Raw EditorInfo values, exposed for integrations/tests. */
    val inputType: Int
        get() = editorInfo.inputType

    val imeOptions: Int
        get() = editorInfo.imeOptions

    /** Enter-key label for the keyboard UI ("搜索"/"发送"/"下一步"/"完成"...). */
    val enterAction: EnterAction
        get() = editorInfo.enterAction

    val enterKeyLabel: String
        get() = enterAction.label

    val isPasswordInput: Boolean
        get() = editorInfo.isPassword

    val aiContextAllowed: Boolean
        get() = editorInfo.aiContextAllowed

    val learningAllowed: Boolean
        get() = editorInfo.allowsPersonalizedLearning
}

/** All user/UI events understood by [KeyboardReducer]. */
sealed interface KeyboardAction {
    data object ToggleLanguage : KeyboardAction

    /** [now] is an uptime/elapsed timestamp supplied by the caller so the reducer stays pure. */
    data class ShiftTapped(val now: Long = 0L) : KeyboardAction

    data class ShowLayer(val layer: KeyboardLayer) : KeyboardAction

    /** A printable key was pressed. Letters become pinyin composing in Chinese mode. */
    data class AppendInput(val text: String) : KeyboardAction

    /** Async pinyin query result. Ignored when [composing] no longer matches the state. */
    data class ComposingUpdated(val composing: String, val candidates: List<Candidate>) : KeyboardAction

    data class CandidateTapped(val index: Int) : KeyboardAction

    /** Commit the raw composing text (used by the "no candidates" fallback chip). */
    data object CommitRaw : KeyboardAction

    data object Space : KeyboardAction

    data object Backspace : KeyboardAction

    data object Enter : KeyboardAction

    /**
     * Editor switched. [aiSettingEnabled] is the user preference; password fields
     * additionally disable AI context harvesting and the AI entry point.
     */
    data class EditorInfoChanged(
        val info: EditorInputInfo,
        val aiSettingEnabled: Boolean = true,
    ) : KeyboardAction

    /** Cursor/selection changed; stale composing outside the candidate region is dropped. */
    data class SelectionChanged(
        val selectionStart: Int,
        val selectionEnd: Int,
        val candidatesStart: Int,
        val candidatesEnd: Int,
    ) : KeyboardAction

    /** Requests AI candidates for the current pinyin/context. */
    data object RequestAiCandidates : KeyboardAction

    data class AiCandidatesUpdated(val candidates: List<String>) : KeyboardAction

    data class AiCandidatesFailed(val message: String) : KeyboardAction

    data class AiCandidateTapped(val word: String) : KeyboardAction

    /** Opens the AI panel; an empty [source] is filled from TextSession by the controller. */
    data class AiPanelOpened(val source: String = "") : KeyboardAction

    data class AiToneChosen(val tone: RewriteTone) : KeyboardAction

    data object AiCancel : KeyboardAction

    data class AiDelta(val text: String) : KeyboardAction

    data class AiCompleted(val text: String) : KeyboardAction

    data class AiFailed(val message: String) : KeyboardAction

    data object AiInsert : KeyboardAction

    data object AiDismiss : KeyboardAction

    data class AiAvailabilityChanged(val enabled: Boolean, val ready: Boolean) : KeyboardAction

    data class HapticsChanged(val enabled: Boolean) : KeyboardAction

    data class SoundChanged(val enabled: Boolean) : KeyboardAction
}

/** Side effects emitted by the pure reducer; executed by KeyboardController via TextSession. */
sealed interface ImeCommand {
    data class CommitText(val text: String) : ImeCommand

    data class SetComposingText(val text: String) : ImeCommand

    data object FinishComposing : ImeCommand

    data object DeleteBackward : ImeCommand

    data object SendEnter : ImeCommand
}

/** Result of one reducer step: the next state plus the side effects to perform. */
data class Reduction(
    val state: KeyboardUiState,
    val commands: List<ImeCommand> = emptyList(),
)
