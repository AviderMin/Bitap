package com.bitap.keyboard.ime

import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.core.AiPanelUiState
import com.bitap.keyboard.core.AiStatus
import com.bitap.keyboard.core.ImeCommand
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardReducer
import com.bitap.keyboard.core.KeyboardUiState
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.ShiftState
import com.bitap.keyboard.core.TextSession
import com.bitap.keyboard.settings.BitapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Connects the pure [KeyboardReducer] to real side effects:
 * TextSession writes, debounced pinyin queries and the AI rewrite stream.
 * Lives for the lifetime of the IME service.
 */
class KeyboardController(
    private val scope: CoroutineScope,
    private val textSession: TextSession,
    private val pinyinEngine: PinyinEngine,
    private val aiService: AiRewriteService,
) {
    private val _state = MutableStateFlow(KeyboardUiState())
    val state: StateFlow<KeyboardUiState> = _state.asStateFlow()

    private var settings = BitapSettings()
    private var aiRuntimeState = AiRuntimeState.NotInstalled
    private var pinyinJob: Job? = null
    private var aiJob: Job? = null
    private var aiToken = 0L

    /** Single entry point for every keyboard event. */
    fun dispatch(action: KeyboardAction) {
        val effective = if (action is KeyboardAction.AiPanelOpened && action.source.isEmpty()) {
            action.copy(source = textSession.readAiSource(KeyboardReducer.MAX_AI_SOURCE_CHARS))
        } else {
            action
        }

        val previous = _state.value
        val reduction = KeyboardReducer.reduce(previous, effective)
        _state.value = reduction.state
        reduction.commands.forEach(::execute)

        if (reduction.state.composing != previous.composing) {
            _state.value = _state.value.copy(
                aiCandidates = emptyList(),
                aiCandidatesLoading = false,
                aiCandidatesError = null,
            )
            schedulePinyinQuery(reduction.state.composing)
        }
        if (previous.ai.visible && !reduction.state.ai.visible) {
            cancelAi()
        }

        when (effective) {
            is KeyboardAction.AiToneChosen -> {
                val panel = reduction.state.ai
                if (panel.status == AiStatus.Thinking && panel.tone == effective.tone) {
                    startAi(panel)
                }
            }

            KeyboardAction.RequestAiCandidates -> startAiCandidates()
            KeyboardAction.AiCancel -> cancelAi()
            else -> Unit
        }
    }

    fun onSettingsChanged(newSettings: BitapSettings) {
        settings = newSettings
        dispatch(KeyboardAction.HapticsChanged(newSettings.hapticsEnabled))
        _state.value = _state.value.copy(
            keyboardHeightScale = newSettings.keyboardHeightScale.coerceIn(0.85f, 1.35f),
        )
        publishAiAvailability()
    }

    fun onAiRuntimeChanged(runtimeState: AiRuntimeState) {
        aiRuntimeState = runtimeState
        publishAiAvailability()
    }

    /** Called when the pinyin dictionary finished loading so a pending query can retry. */
    fun onPinyinReady() {
        val composing = _state.value.composing
        if (composing.isNotEmpty()) schedulePinyinQuery(composing)
    }

    fun onStartInput() {
        pinyinJob?.cancel()
        cancelAi()
        _state.value = _state.value.copy(
            composing = "",
            candidates = emptyList(),
            ai = AiPanelUiState(),
            aiCandidates = emptyList(),
            aiCandidatesLoading = false,
            aiCandidatesError = null,
            shift = ShiftState.Off,
            lastShiftTapAt = 0L,
        )
    }

    fun onFinishInput() {
        pinyinJob?.cancel()
        cancelAi()
        runCatching { textSession.finishComposingText() }
        _state.value = _state.value.copy(
            composing = "",
            candidates = emptyList(),
            ai = AiPanelUiState(),
            aiCandidates = emptyList(),
            aiCandidatesLoading = false,
            aiCandidatesError = null,
            shift = ShiftState.Off,
            lastShiftTapAt = 0L,
        )
    }

    /** Releases coroutines and cancels any in-flight AI request when the service dies. */
    fun dispose() {
        pinyinJob?.cancel()
        cancelAi()
    }

    private fun execute(command: ImeCommand) {
        when (command) {
            is ImeCommand.CommitText -> textSession.commitText(command.text)
            is ImeCommand.SetComposingText -> textSession.setComposingText(command.text)
            ImeCommand.FinishComposing -> textSession.finishComposingText()
            ImeCommand.DeleteBackward -> textSession.deleteBackward()
            ImeCommand.SendEnter -> textSession.sendEnter()
        }
    }

    private fun schedulePinyinQuery(composing: String) {
        pinyinJob?.cancel()
        if (composing.isEmpty()) return
        pinyinJob = scope.launch {
            delay(PINYIN_DEBOUNCE_MS)
            val candidates = pinyinEngine.query(composing)
            _state.value = KeyboardReducer.reduce(
                _state.value,
                KeyboardAction.ComposingUpdated(composing, candidates),
            ).state
        }
    }

    private fun publishAiAvailability() {
        val ready = aiRuntimeState == AiRuntimeState.Ready || aiRuntimeState == AiRuntimeState.Busy
        // Keep a running request alive while the engine reports Loading/Busy transitions.
        if (!ready && _state.value.ai.isRunning && aiRuntimeState == AiRuntimeState.Loading) return
        dispatch(KeyboardAction.AiAvailabilityChanged(settings.aiEnabled, ready))
    }

    private fun startAiCandidates() {
        if (!settings.aiEnabled) return
        aiJob?.cancel()
        aiService.cancel()
        val query = _state.value.composing.ifBlank {
            runCatching { textSession.readAiSource(128) }.getOrDefault("")
        }
        if (query.isBlank()) {
            reduceInto(KeyboardAction.AiCandidatesFailed("先输入拼音，或选中一段文字"))
            return
        }
        val token = ++aiToken
        aiJob = scope.launch {
            var terminal = false
            try {
                aiService.suggestCandidates(
                    pinyin = if (_state.value.composing.isNotEmpty()) _state.value.composing else "",
                    context = query,
                ).collect { event ->
                    if (token != aiToken) return@collect
                    when (event) {
                        AiEvent.Started -> Unit
                        is AiEvent.Delta -> Unit
                        is AiEvent.Completed -> {
                            terminal = true
                            reduceInto(KeyboardAction.AiCandidatesUpdated(parseAiCandidates(event.text)))
                        }
                        is AiEvent.Failed -> {
                            terminal = true
                            reduceInto(KeyboardAction.AiCandidatesFailed(event.message))
                        }
                    }
                }
                if (!terminal && token == aiToken) {
                    reduceInto(KeyboardAction.AiCandidatesFailed("AI 词库没有返回候选"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (token == aiToken) {
                    reduceInto(KeyboardAction.AiCandidatesFailed("AI 词库暂时不可用"))
                }
            }
        }
    }

    private fun parseAiCandidates(raw: String): List<String> = raw
        .lineSequence()
        .map { line ->
            line.trim()
                .trim('-', '*', '#', '`', '"', '“', '”', '「', '」')
                .replace(Regex("^[0-9]+[.)、\\s]+"), "")
                .trim()
        }
        .filter { it.isNotEmpty() && it.length <= 24 && it.any { c -> c in '\u4e00'..'\u9fff' } }
        .distinct()
        .take(6)
        .toList()

    private fun startAi(panel: AiPanelUiState) {
        val tone = panel.tone ?: return
        if (panel.source.isBlank()) return
        aiJob?.cancel()
        aiService.cancel()
        val token = ++aiToken
        aiJob = scope.launch {
            var terminal = false
            try {
                aiService.rewrite(panel.source, tone).collect { event ->
                    if (token != aiToken) return@collect
                    when (event) {
                        AiEvent.Started -> Unit
                        is AiEvent.Delta -> reduceInto(KeyboardAction.AiDelta(event.text))
                        is AiEvent.Completed -> {
                            terminal = true
                            reduceInto(KeyboardAction.AiCompleted(event.text))
                        }

                        is AiEvent.Failed -> {
                            terminal = true
                            reduceInto(KeyboardAction.AiFailed(event.message))
                        }
                    }
                }
                if (!terminal && token == aiToken) {
                    reduceInto(KeyboardAction.AiFailed("这次没想好"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (token == aiToken) {
                    reduceInto(KeyboardAction.AiFailed("这次没想好"))
                }
            }
        }
    }

    private fun cancelAi() {
        aiToken++
        aiJob?.cancel()
        aiJob = null
        runCatching { aiService.cancel() }
    }

    private fun reduceInto(action: KeyboardAction) {
        _state.value = KeyboardReducer.reduce(_state.value, action).state
    }

    private companion object {
        const val PINYIN_DEBOUNCE_MS = 30L
    }
}
