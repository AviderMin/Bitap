package com.bitap.keyboard.core

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

/** One pinyin candidate. */
data class Candidate(
    val word: String,
    val pinyin: String = "",
    val frequency: Long = 0L,
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
    val candidates: List<Candidate> = emptyList(),
    val ai: AiPanelUiState = AiPanelUiState(),
    val aiEnabled: Boolean = true,
    val aiReady: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val keyboardHeightScale: Float = 1.0f,
    val lastShiftTapAt: Long = 0L,
)

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
