package com.bitap.keyboard.ime

import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.internal.AiRawContextResolver
import com.bitap.keyboard.ai.internal.AiRawReplacement
import com.bitap.keyboard.core.AiPanelUiState
import com.bitap.keyboard.core.AiStatus
import com.bitap.keyboard.core.EditorInputInfo
import com.bitap.keyboard.core.ImeCommand
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardReducer
import com.bitap.keyboard.core.KeyboardUiState
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.Reduction
import com.bitap.keyboard.core.ShiftState
import com.bitap.keyboard.core.TextSession
import com.bitap.keyboard.settings.BitapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(KeyboardUiState())
    val state: StateFlow<KeyboardUiState> = _state.asStateFlow()

    private var settings = BitapSettings()
    private var aiRuntimeState = AiRuntimeState.NotInstalled
    private var pinyinJob: Job? = null
    private var pinyinQueryToken = 0L
    private var aiJob: Job? = null
    private var aiToken = 0L
    private var rawAiPlan: AiRawReplacement? = null
    private var rawAiPlanToken = -1L
    private var userLexiconPersistJob: Job? = null
    private var lastSelectionSnapshot: SelectionSnapshot? = null

    private data class SelectionSnapshot(
        val selectionStart: Int,
        val selectionEnd: Int,
        val candidatesStart: Int,
        val candidatesEnd: Int,
    )

    /** Single entry point for every keyboard event. */
    fun dispatch(action: KeyboardAction) {
        if (action is KeyboardAction.AiCandidateTapped &&
            rawAiPlan != null &&
            rawAiPlanToken == aiToken
        ) {
            handleRawAiCandidateTap(action.word)
            return
        }
        // P0: while an AI lexicon request is loading, tapping the ✦ AI entry again
        // means "cancel". It must never restart the request or let the old result
        // land after the user asked to stop.
        if (action is KeyboardAction.RequestAiCandidates && _state.value.aiCandidatesLoading) {
            cancelAi()
            return
        }
        val canReadContext = _state.value.aiEnabled && _state.value.editorInfo.aiContextAllowed
        val effective = if (
            action is KeyboardAction.AiPanelOpened &&
            action.source.isEmpty() &&
            canReadContext
        ) {
            action.copy(source = textSession.readAiSource(KeyboardReducer.MAX_AI_SOURCE_CHARS))
        } else {
            action
        }

        val previous = _state.value
        val reduction = KeyboardReducer.reduce(previous, effective)
        _state.value = reduction.state
        reduction.commands.forEach(::execute)
        recordLearnedSelection(reduction)

        if (reduction.state.composing != previous.composing ||
            reduction.state.compositionVersion != previous.compositionVersion
        ) {
            _state.value = _state.value.copy(
                aiCandidates = emptyList(),
                aiCandidatesLoading = false,
                aiCandidatesError = null,
            )
            if (aiJob != null) cancelAi()
            schedulePinyinQuery(reduction.state.composing, reduction.state.compositionVersion)
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
            is KeyboardAction.ShowLayer -> {
                // S01: layer switches preserve composing; ensure a valid query
                // result exists for that same composing even if no letter changes.
                if (reduction.state.composing.isNotEmpty()) {
                    schedulePinyinQuery(reduction.state.composing, reduction.state.compositionVersion)
                }
            }

            else -> Unit
        }
    }

    fun onSettingsChanged(newSettings: BitapSettings) {
        settings = newSettings
        applyLearningPolicy()
        if (!newSettings.aiEnabled) cancelAi()
        dispatch(KeyboardAction.HapticsChanged(newSettings.hapticsEnabled))
        dispatch(KeyboardAction.SoundChanged(newSettings.soundEnabled))
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
        if (composing.isNotEmpty()) schedulePinyinQuery(composing, _state.value.compositionVersion)
    }

    /**
     * New editor focused. The complete input configuration is applied through the
     * reducer so no language/layer/composing state leaks from the previous field.
     */
    fun onStartInput(editorInfo: EditorInputInfo) {
        pinyinJob?.cancel()
        lastSelectionSnapshot = null
        cancelAi()
        dispatch(KeyboardAction.EditorInfoChanged(editorInfo, settings.aiEnabled))
        applyLearningPolicy()
    }

    /** Clears the running engine's in-memory and persisted user lexicon. */
    fun clearUserLexicon() {
        pinyinEngine.clearSelectionMemory()
    }

    private fun applyLearningPolicy() {
        pinyinEngine.setLearningEnabled(
            settings.learningEnabled && _state.value.editorInfo.allowsPersonalizedLearning,
        )
    }

    private fun scheduleUserLexiconPersist() {
        userLexiconPersistJob?.cancel()
        userLexiconPersistJob = scope.launch {
            delay(USER_LEXICON_PERSIST_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { pinyinEngine.persistSelectionWeights() }
        }
    }

    fun onFinishInput() {
        pinyinJob?.cancel()
        lastSelectionSnapshot = null
        cancelAi()
        runCatching { textSession.finishComposingText() }
        dispatch(KeyboardAction.EditorInfoChanged(EditorInputInfo(), settings.aiEnabled))
    }

    /**
     * Called from InputMethodService.onUpdateSelection. The reducer decides whether
     * the composing region is still valid; the controller only forwards the data.
     */
    fun onSelectionChanged(
        selectionStart: Int,
        selectionEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        oldSelectionStart: Int = -1,
        oldSelectionEnd: Int = -1,
    ) {
        val snapshot = SelectionSnapshot(selectionStart, selectionEnd, candidatesStart, candidatesEnd)
        val samePosition = oldSelectionStart >= 0 && oldSelectionEnd >= 0 &&
            oldSelectionStart == selectionStart && oldSelectionEnd == selectionEnd
        val repeated = samePosition || lastSelectionSnapshot == snapshot ||
            (oldSelectionStart >= 0 && oldSelectionEnd >= 0 &&
                oldSelectionStart == selectionStart && oldSelectionEnd == selectionEnd &&
                lastSelectionSnapshot?.let {
                    it.candidatesStart == candidatesStart && it.candidatesEnd == candidatesEnd
                } == true)
        if (repeated) return

        val state = _state.value
        val normalComposingEnd = state.composing.isNotEmpty() &&
            candidatesStart >= 0 && candidatesEnd >= candidatesStart &&
            selectionStart == selectionEnd && selectionEnd == candidatesEnd
        lastSelectionSnapshot = snapshot

        // A composing-end callback is generated by our own setComposingText. It
        // must not cancel a still-valid AI request or clear its loading state.
        if (normalComposingEnd) return

        // A real cursor/selection move invalidates the AI context. Clear the
        // request state immediately, then let the reducer finish composing if
        // necessary. (R07)
        val aiRequestActive = aiJob != null ||
            _state.value.aiCandidatesLoading ||
            _state.value.aiCandidates.isNotEmpty() ||
            _state.value.aiCandidatesError != null
        if (aiRequestActive) cancelAi()
        dispatch(
            KeyboardAction.SelectionChanged(
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                candidatesStart = candidatesStart,
                candidatesEnd = candidatesEnd,
            ),
        )
    }

    /** Releases coroutines and cancels any in-flight AI request when the service dies. */
    fun dispose() {
        pinyinJob?.cancel()
        userLexiconPersistJob?.cancel()
        cancelAi()
        // Persist synchronously only at service teardown, never on the key path.
        runCatching { pinyinEngine.persistSelectionWeights() }
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

    /**
     * Records the candidate actually committed by the reducer. This covers direct
     * taps and pending confirmations completed by ComposingUpdated alike; raw
     * fallbacks/cancelled confirmations carry no LearnedSelection.
     */
    private fun recordLearnedSelection(reduction: Reduction) {
        val learned = reduction.learnedSelections.ifEmpty {
            listOfNotNull(reduction.learnedSelection)
        }
        if (learned.isEmpty()) return
        if (!settings.learningEnabled || !_state.value.editorInfo.allowsPersonalizedLearning) return
        learned.forEach { event ->
            pinyinEngine.recordSelection(
                word = event.word,
                pinyin = event.pinyin,
                source = event.source,
                persist = false,
            )
        }
        scheduleUserLexiconPersist()
    }

    private fun schedulePinyinQuery(composing: String, version: Long = _state.value.compositionVersion) {
        pinyinJob?.cancel()
        if (composing.isEmpty()) return
        val token = ++pinyinQueryToken
        pinyinJob = scope.launch {
            delay(PINYIN_DEBOUNCE_MS)
            if (token != pinyinQueryToken) return@launch
            val candidates = withContext(queryDispatcher) {
                // Pinyin decoding cooperates with coroutine cancellation on its
                // internal loops so old queries stop using the CPU.
                pinyinEngine.search(composing) { isActive }
            }
            if (!isActive || token != pinyinQueryToken) return@launch
            // Dispatch through the same path as user actions so a queued
            // confirmation executes its CommitText when the fresh result lands.
            dispatch(KeyboardAction.ComposingUpdated(composing, candidates, version))
        }
    }

    private fun publishAiAvailability() {
        val ready = aiRuntimeState == AiRuntimeState.Ready || aiRuntimeState == AiRuntimeState.Busy
        // Keep a running request alive while the engine reports Loading/Busy transitions.
        if (!ready && _state.value.ai.isRunning && aiRuntimeState == AiRuntimeState.Loading) return
        val enabled = settings.aiEnabled && _state.value.editorInfo.aiContextAllowed
        if (!enabled) cancelAi()
        dispatch(KeyboardAction.AiAvailabilityChanged(enabled, ready))
    }

    private fun startAiCandidates() {
        if (!settings.aiEnabled || !_state.value.aiEnabled || !_state.value.editorInfo.aiContextAllowed) return
        aiJob?.cancel()
        aiService.cancel()
        val composing = _state.value.composing
        val rawContext = runCatching { textSession.readAiSource(128) }.getOrDefault("")
        val rawPlan = if (composing.isEmpty()) {
            AiRawContextResolver.resolve(
                selected = runCatching { textSession.getSelectedText() }.getOrNull(),
                beforeCursor = runCatching { textSession.getTextBeforeCursor(128) }.getOrNull(),
            )
        } else {
            null
        }
        // When the editor still contains the composing suffix, do not feed the
        // pinyin itself back as Chinese context.
        val context = if (composing.isNotEmpty()) {
            if (rawContext.endsWith(composing)) rawContext.dropLast(composing.length) else rawContext
        } else {
            rawPlan?.context.orEmpty()
        }
        val pinyin = if (composing.isNotEmpty()) composing else rawPlan?.query.orEmpty()
        if (pinyin.isBlank()) {
            reduceInto(KeyboardAction.AiCandidatesFailed("先输入拼音，或选中一段文字"))
            return
        }
        val token = ++aiToken
        rawAiPlan = rawPlan
        rawAiPlanToken = if (rawPlan != null) token else -1L
        aiJob = scope.launch {
            var terminal = false
            try {
                aiService.suggestCandidates(
                    pinyin = pinyin,
                    context = context,
                ).collect { event ->
                    if (token != aiToken) return@collect
                    when (event) {
                        AiEvent.Started -> Unit
                        is AiEvent.Delta -> Unit
                        is AiEvent.Completed -> {
                            terminal = true
                            val parsed = parseAiCandidates(event.text)
                            if (parsed.isEmpty()) clearRawAiPlan()
                            reduceInto(KeyboardAction.AiCandidatesUpdated(parsed))
                        }
                        is AiEvent.Failed -> {
                            terminal = true
                            clearRawAiPlan()
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
                    clearRawAiPlan()
                    reduceInto(KeyboardAction.AiCandidatesFailed("AI 词库暂时不可用"))
                }
            }
        }
    }

    /**
     * Replaces the exact raw editor range the AI candidates were requested for.
     * The editor is re-read first: a moved cursor, changed selection or edited
     * text cancels the replacement rather than deleting the wrong characters.
     */
    private fun handleRawAiCandidateTap(word: String) {
        val plan = rawAiPlan ?: return
        if (rawAiPlanToken != aiToken) {
            clearRawAiPlan()
            return
        }
        val state = _state.value
        if (!state.aiEnabled || !state.editorInfo.aiContextAllowed || state.aiCandidatesLoading) return
        if (state.aiCandidates.none { it == word }) return
        val stillValid = if (plan.selected) {
            runCatching { textSession.getSelectedText() }.getOrNull() == plan.original
        } else {
            runCatching { textSession.getTextBeforeCursor(plan.original.length) }.getOrNull() == plan.original &&
                runCatching { textSession.getSelectedText() }.getOrNull().isNullOrEmpty()
        }
        if (!stillValid) {
            clearRawAiPlan()
            reduceInto(KeyboardAction.AiCandidatesFailed("原输入已变化，已取消 AI 候选"))
            return
        }
        if (!plan.selected) repeat(plan.original.length) { textSession.deleteBackward() }
        textSession.commitText(word)
        if (settings.learningEnabled && state.editorInfo.allowsPersonalizedLearning) {
            pinyinEngine.recordSelection(word = word, pinyin = plan.query, source = "ai", persist = false)
            scheduleUserLexiconPersist()
        }
        clearRawAiPlan()
        aiJob?.cancel()
        aiJob = null
        aiToken++
        _state.value = _state.value.copy(
            aiCandidates = emptyList(),
            aiCandidatesFor = "",
            aiCandidatesLoading = false,
            aiCandidatesError = null,
        )
    }

    private fun clearRawAiPlan() {
        rawAiPlan = null
        rawAiPlanToken = -1L
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
        if (!_state.value.editorInfo.aiContextAllowed) return
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
        clearRawAiPlan()
        aiJob?.cancel()
        aiJob = null
        runCatching { aiService.cancel() }
        reduceInto(KeyboardAction.AiCandidatesCancelled)
    }

    private fun reduceInto(action: KeyboardAction) {
        _state.value = KeyboardReducer.reduce(_state.value, action).state
    }

    private companion object {
        const val PINYIN_DEBOUNCE_MS = 30L
        const val USER_LEXICON_PERSIST_DEBOUNCE_MS = 500L
    }
}
