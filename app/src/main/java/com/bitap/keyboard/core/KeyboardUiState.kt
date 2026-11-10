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
 * Lifecycle of the bundled pinyin dictionary load (P0-B).
 *
 * The IME starts in [Loading]; a successful `loadFromAssets` moves it to
 * [Ready], any non-cancellation failure moves it to [Failed]. [Failed] only
 * disables pinyin candidates: raw-text commit and the rest of the keyboard stay
 * usable, and the user can request a reload from the candidate bar.
 */
enum class DictionaryLoadState {
    Loading,
    Ready,
    Failed,
}

/**
 * High-level editor type derived from [EditorInfo.inputType]. It drives the
 * initial language/layer and the AI privacy policy in [KeyboardReducer].
 */
enum class EditorInputKind {
    Text,
    Number,
    Phone,
    DateTime,
    Email,
    Url,
    Password,
}

/** Numeric input capability derived from EditorInfo.inputType flags. */
enum class NumericInputMode {
    None,
    Integer,
    Decimal,
    Signed,
    Phone,
    DateTime,
}

/** Independent numeric capabilities; decimal and signed can both be true. */
data class NumericCapabilities(
    val digits: Boolean = false,
    val decimal: Boolean = false,
    val signed: Boolean = false,
    val phone: Boolean = false,
    val dateTime: Boolean = false,
)

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

    /**
     * Initial layout is English for non-text fields, but Email/URL are not hard
     * language locks: the user may deliberately switch to Chinese search.
     */
    val supportsChineseInput: Boolean
        get() = kind == EditorInputKind.Text ||
            kind == EditorInputKind.Email ||
            kind == EditorInputKind.Url

    /** Compatibility alias used by existing reducer callers. */
    val allowsChineseInput: Boolean
        get() = supportsChineseInput

    val preferredLanguage: InputLanguage
        get() = if (kind == EditorInputKind.Text) InputLanguage.Chinese else InputLanguage.English

    val numericCapabilities: NumericCapabilities
        get() = when (kind) {
            EditorInputKind.Phone -> NumericCapabilities(digits = true, phone = true)
            EditorInputKind.DateTime -> NumericCapabilities(digits = true, dateTime = true)
            EditorInputKind.Number -> NumericCapabilities(
                digits = true,
                decimal = inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0,
                signed = inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0,
            )

            EditorInputKind.Password -> if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER) {
                NumericCapabilities(digits = true)
            } else {
                NumericCapabilities()
            }

            else -> NumericCapabilities()
        }

    /** Independent flags; unlike numericMode these can combine (signed+decimal). */
    val allowsDecimal: Boolean
        get() = numericCapabilities.decimal

    val allowsSigned: Boolean
        get() = numericCapabilities.signed

    val isDateTime: Boolean
        get() = numericCapabilities.dateTime

    val numericMode: NumericInputMode
        get() = when {
            numericCapabilities.phone -> NumericInputMode.Phone
            numericCapabilities.dateTime -> NumericInputMode.DateTime
            numericCapabilities.decimal -> NumericInputMode.Decimal
            numericCapabilities.signed -> NumericInputMode.Signed
            numericCapabilities.digits -> NumericInputMode.Integer
            else -> NumericInputMode.None
        }

    /** Symbols beyond 0-9 that the numeric layout should expose. */
    val numericSymbols: List<String>
        get() = when {
            numericCapabilities.phone -> listOf("+", "-", "*", "#", " ", "(", ")")
            numericCapabilities.dateTime -> listOf("/", ":", "-", " ")
            else -> buildList {
                if (numericCapabilities.decimal) add(".")
                if (numericCapabilities.signed) {
                    add("+")
                    add("-")
                }
            }
        }

    val preferredLayer: KeyboardLayer
        get() = when (kind) {
            EditorInputKind.Number, EditorInputKind.Phone, EditorInputKind.DateTime -> KeyboardLayer.Numbers
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
                InputType.TYPE_CLASS_DATETIME -> EditorInputKind.DateTime
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

/** Metadata for a candidate that was actually committed and may be learned. */
data class LearnedSelection(
    val word: String,
    val pinyin: String,
    val source: String,
    val consumedInputLength: Int,
)

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
    /** True for a lightweight typo-correction candidate; the UI marks it as 纠错. */
    val isCorrection: Boolean = false,
    /**
     * Composition version this candidate was produced for. [UNVERSIONED] (-1)
     * keeps legacy callers/tests working: an unversioned candidate is accepted
     * by the reducer's version guard. A non-negative value must equal
     * [KeyboardUiState.compositionVersion] or the candidate can never be committed.
     */
    val compositionVersion: Long = UNVERSIONED,
    /** Input session that produced this candidate; null means unbound (legacy). */
    val sessionId: String? = null,
) {
    companion object {
        /** Sentinel for candidates created before versioning (legacy behavior). */
        const val UNVERSIONED: Long = -1L
    }
}

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
    /**
     * Short user-facing hint set when the last pinyin query failed (P0-A).
     * The composing text stays intact so the user can retry, edit or commit
     * raw; the hint is cleared by the next composing update/input/commit.
     */
    val candidateQueryError: String? = null,
    /** True while the last committed text can be removed by [KeyboardAction.Undo]. */
    val canUndo: Boolean = false,
    /** True while an undone commit can be re-applied by [KeyboardAction.Redo]. */
    val canRedo: Boolean = false,
    /** P0-B: observable pinyin dictionary load state; starts loading on IME creation. */
    val dictionaryState: DictionaryLoadState = DictionaryLoadState.Loading,
    /** Short user-facing reason when [dictionaryState] is Failed. */
    val dictionaryError: String? = null,
    /** Set when Space/Candidate/Punctuation waits for a fresh composing query. */
    val pendingConfirmation: PendingConfirmation? = null,
    /** Snapshot of composing at the time the pending confirmation was created. */
    val pendingComposing: String = "",
    /** Keys/actions received while a confirmation is pending, replayed in order. */
    val queuedActions: List<KeyboardAction> = emptyList(),
    /** Monotonic composition session/version; changes even when text repeats. */
    val compositionVersion: Long = 0L,
    /**
     * Opaque id of the current input session. Regenerated by every
     * [KeyboardAction.EditorInfoChanged] (or supplied by the controller) so a
     * late query result from a previous editor can never be applied.
     */
    val compositionSessionId: String = "",
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
    /** English word completions / next-word suggestions shown above the English keyboard. */
    val englishSuggestions: List<String> = emptyList(),
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val keyboardHeightScale: Float = 1.0f,
    val lastShiftTapAt: Long = 0L,
    /** True while the editor selection is a non-empty range (drives copy/cut). */
    val hasSelection: Boolean = false,
    /** True while the dedicated editing-tools keyboard page is open. */
    val editingToolsVisible: Boolean = false,
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

    val supportsChineseInput: Boolean
        get() = editorInfo.supportsChineseInput

    val numericMode: NumericInputMode
        get() = editorInfo.numericMode

    val numericSymbols: List<String>
        get() = editorInfo.numericSymbols

    val numericCapabilities: NumericCapabilities
        get() = editorInfo.numericCapabilities

    val allowsDecimal: Boolean
        get() = editorInfo.allowsDecimal

    val allowsSigned: Boolean
        get() = editorInfo.allowsSigned

    val isDateTimeInput: Boolean
        get() = editorInfo.isDateTime

    /** UI can hide the language toggle where it has no effect. */
    val showLanguageToggle: Boolean
        get() = editorInfo.supportsChineseInput
}

/** All user/UI events understood by [KeyboardReducer]. */
sealed interface KeyboardAction {
    data object ToggleLanguage : KeyboardAction

    /** [now] is an uptime/elapsed timestamp supplied by the caller so the reducer stays pure. */
    data class ShiftTapped(val now: Long = 0L) : KeyboardAction

    data class ShowLayer(val layer: KeyboardLayer) : KeyboardAction

    /** A printable key was pressed. Letters become pinyin composing in Chinese mode. */
    data class AppendInput(val text: String) : KeyboardAction

    /**
     * Async pinyin query result. Old sessions are rejected by compositionVersion
     * and (when supplied) by [sessionId].
     */
    data class ComposingUpdated(
        val composing: String,
        val candidates: List<Candidate>,
        val version: Long = 0L,
        /** Input session this result belongs to; empty keeps the legacy path. */
        val sessionId: String = "",
    ) : KeyboardAction

    /**
     * Async pinyin query failed with a non-cancellation throwable (P0-A).
     *
     * [composing], [version] and [sessionId] identify the composition the failed
     * query belonged to, so a late failure can never resolve a newer pending
     * confirmation or touch a newer input. When a matching confirmation is
     * waiting the reducer commits the raw composing text and replays queued keys.
     */
    data class PinyinQueryFailed(
        val composing: String,
        val version: Long = 0L,
        /** Input session this failure belongs to; empty keeps the legacy path. */
        val sessionId: String = "",
        /** Optional short user-facing hint; blank lets the reducer choose one. */
        val message: String = "",
    ) : KeyboardAction

    /** P0-B: the dictionary load finished (or failed); [error] is a short hint. */
    data class DictionaryStateChanged(
        val state: DictionaryLoadState,
        val error: String? = null,
    ) : KeyboardAction

    /** P0-B: user asked to reload a failed dictionary; the controller reloads it. */
    data object RetryDictionaryLoad : KeyboardAction

    data class CandidateTapped(val index: Int) : KeyboardAction

    /** Commit the raw composing text (used by the "no candidates" fallback chip). */
    data object CommitRaw : KeyboardAction

    /** Remove the last text committed by the IME, when it is still intact. */
    data object Undo : KeyboardAction

    /** Re-apply the most recently undone commit at the same cursor position. */
    data object Redo : KeyboardAction

    /** Move the cursor by [delta] characters; zero is a no-op. */
    data class MoveCursor(val delta: Int) : KeyboardAction

    /** Open or close the dedicated editing-tools keyboard page. */
    data object ToggleEditingTools : KeyboardAction

    /** Select the whole editor content through [TextSession.selectAll]. */
    data object SelectAll : KeyboardAction

    /** Copy the current selection through [TextSession.copySelection]. */
    data object CopySelection : KeyboardAction

    /** Cut the current selection through [TextSession.cutSelection]. */
    data object CutSelection : KeyboardAction

    /** Paste the clipboard through [TextSession.paste]. */
    data object Paste : KeyboardAction

    /** Move the cursor by [delta] words; zero is a no-op. */
    data class MoveCursorByWord(val delta: Int) : KeyboardAction

    /** Delete the word before the cursor through [TextSession.deleteBackwardWord]. */
    data object DeleteWord : KeyboardAction

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
        /** Opaque per-input-connection id from the controller; empty generates one. */
        val sessionId: String = "",
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

    /** An English completion/next-word chip was tapped. */
    data class EnglishSuggestionTapped(val word: String) : KeyboardAction

    /** Opens the AI panel; an empty [source] is filled from TextSession by the controller. */
    data class AiPanelOpened(val source: String = "") : KeyboardAction

    data class AiToneChosen(val tone: RewriteTone) : KeyboardAction

    data object AiCancel : KeyboardAction

    /** Cancel only the AI candidate request and clear its loading/candidate state. */
    data object AiCandidatesCancelled : KeyboardAction

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

/** Result of one reducer step: next state, side effects, and ordered learning events. */
data class Reduction(
    val state: KeyboardUiState,
    val commands: List<ImeCommand> = emptyList(),
    val learnedSelection: LearnedSelection? = null,
    val learnedSelections: List<LearnedSelection> = emptyList(),
)
