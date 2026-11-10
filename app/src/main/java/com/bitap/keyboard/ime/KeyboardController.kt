package com.bitap.keyboard.ime

import com.bitap.keyboard.ai.AiEvent
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.ai.internal.AiRawContextResolver
import com.bitap.keyboard.ai.internal.AiRawReplacement
import com.bitap.keyboard.core.AiPanelUiState
import com.bitap.keyboard.core.AiStatus
import com.bitap.keyboard.core.DictionaryLoadState
import com.bitap.keyboard.core.EditorInputInfo
import com.bitap.keyboard.core.EditorSelection
import com.bitap.keyboard.core.EnglishPredictionData
import com.bitap.keyboard.core.EnglishPredictionStore
import com.bitap.keyboard.core.EnglishPredictor
import com.bitap.keyboard.core.ImeCommand
import com.bitap.keyboard.core.InputLanguage
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.NumericInputMode
import com.bitap.keyboard.core.KeyboardReducer
import com.bitap.keyboard.core.KeyboardUiState
import com.bitap.keyboard.core.PinyinEngine
import com.bitap.keyboard.core.Reduction
import com.bitap.keyboard.core.engine.InputEngine
import com.bitap.keyboard.core.engine.PinyinInputEngine
import com.bitap.keyboard.core.engine.QueryContext
import com.bitap.keyboard.core.engine.QueryInputMode
import com.bitap.keyboard.core.engine.QueryRequest
import com.bitap.keyboard.core.engine.SelectionEvent
import com.bitap.keyboard.core.engine.toKeyboardCandidate
import com.bitap.keyboard.core.ShiftState
import com.bitap.keyboard.core.TextSession
import com.bitap.keyboard.settings.BitapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val inputEngine: InputEngine,
    private val aiService: AiRewriteService,
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Long-input prefix policy; production stays [QueryInputMode.FIXED] until a batch selects otherwise. */
    private val queryInputMode: QueryInputMode = QueryInputMode.FIXED,
    /**
     * P0-B: reloads the pinyin dictionary when the user taps retry. The IME
     * service injects the real asset loader; null (headless tests) still moves
     * the observable state to Loading, it just cannot perform the load.
     */
    private val onDictionaryRetry: (() -> Unit)? = null,
    /** Persistent store for English completion/next-word learning; None in headless tests. */
    private val englishPredictionStore: EnglishPredictionStore = EnglishPredictionStore.None,
) {
    /**
     * Compatibility constructor for the pre-D03 caller and tests: the bundled
     * [PinyinEngine] is wrapped in [PinyinInputEngine] so production and tests
     * exercise the exact same [InputEngine] path. A future librime/other engine
     * can be supplied through the primary constructor without touching the IME.
     */
    constructor(
        scope: CoroutineScope,
        textSession: TextSession,
        pinyinEngine: PinyinEngine,
        aiService: AiRewriteService,
        queryDispatcher: CoroutineDispatcher = Dispatchers.Default,
        onDictionaryRetry: (() -> Unit)? = null,
        englishPredictionStore: EnglishPredictionStore = EnglishPredictionStore.None,
    ) : this(
        scope,
        textSession,
        PinyinInputEngine(pinyinEngine),
        aiService,
        queryDispatcher,
        onDictionaryRetry = onDictionaryRetry,
        englishPredictionStore = englishPredictionStore,
    )
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
    /**
     * Last [EnglishPredictionData] this controller loaded from or saved to the
     * store. The settings screen clears/changes the same persisted data through
     * a different store instance, so the live predictor must compare before it
     * records or persists and adopt the external state instead of writing stale
     * words back (P1-1 English half).
     */
    private var lastEnglishPredictionData: EnglishPredictionData = englishPredictionStore.load()
    private val englishPredictor = EnglishPredictor(lastEnglishPredictionData)
    /** Letters of the English word currently being typed, lowercase for lookup. */
    private var englishBuffer = ""
    /** Last committed English word, used for next-word bigrams. */
    private var englishLastWord: String? = null
    private var lastSelectionSnapshot: SelectionSnapshot? = null
    /**
     * Best-known absolute editor selection. It is advanced locally by every
     * [ImeCommand] whose effect is known (commit/composing/delete) so commit end
     * positions are available without reading the whole document; host
     * selection callbacks re-synchronise it.
     */
    private var virtualSelection: EditorSelection? = null
    /** Start offset of the active composing region, when [execute] created one. */
    private var virtualComposingStart: Int? = null
    /**
     * Selection callbacks our own commands are expected to produce, oldest
     * first. A callback matching an entry is internal; anything else that moves
     * the caret/selection is an external move that invalidates English
     * completion state and exact-commit undo history.
     */
    private val expectedSelectionChanges = ArrayDeque<ExpectedSelection>()
    /** Bounded history of exact IME commits; newest last. */
    private val undoStack = ArrayDeque<UndoRecord>()
    /** Undone commits that can be re-applied at their recorded start position. */
    private val redoStack = ArrayDeque<UndoRecord>()
    private val committedThisDispatch = ArrayList<UndoRecord>()

    /** Monotonic per-input-connection id; late query results carry it for rejection. */
    private var inputSessionId: String = "ime-0"
    private var inputSessionCounter = 0L

    private data class SelectionSnapshot(
        val selectionStart: Int,
        val selectionEnd: Int,
        val candidatesStart: Int,
        val candidatesEnd: Int,
    )

    /**
     * One exact IME edit: [text] occupies the absolute [start]..[end] range now,
     * and [replacedText] was the text in [start]..[start]+replacedText.length
     * before the edit. Undo removes [text] and re-inserts [replacedText]; redo
     * removes the restored [replacedText] and re-inserts [text].
     */
    private data class UndoRecord(
        val text: String,
        val start: Int,
        val end: Int,
        /** Input connection that produced the commit; undo never crosses editors. */
        val sessionId: String,
        /** Exact text replaced by this edit; empty for a pure insertion. */
        val replacedText: String = "",
    )

    /** A selection callback our own command is expected to produce. */
    private data class ExpectedSelection(
        val from: EditorSelection?,
        val to: EditorSelection,
    )

    /** Single entry point for every keyboard event. */
    fun dispatch(action: KeyboardAction) {
        // P0-B: retry is a service-owned side effect. Move to Loading before the
        // reload starts so the UI shows progress immediately, then invoke the
        // injected loader exactly once (the reducer clears the old error).
        if (action == KeyboardAction.RetryDictionaryLoad) {
            _state.value = KeyboardReducer.reduce(_state.value, action).state
            onDictionaryRetry?.invoke()
            return
        }
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
        if (action is KeyboardAction.EnglishSuggestionTapped) {
            handleEnglishSuggestionTapped(action.word)
            return
        }
        if (action == KeyboardAction.Undo) {
            handleUndo()
            return
        }
        if (action == KeyboardAction.Redo) {
            handleRedo()
            return
        }
        if (action is KeyboardAction.MoveCursor) {
            if (textSession.moveCursorBy(action.delta)) {
                adoptEditorSelection()
                clearUndoState()
                resetEnglishTracking()
            }
            return
        }
        // Editing-tool actions are editor side effects owned by TextSession; the
        // reducer keeps them state-neutral. Handle them before the generic reducer
        // path so they never queue behind a pending pinyin confirmation.
        if (action == KeyboardAction.ToggleEditingTools) {
            _state.value = _state.value.copy(editingToolsVisible = !_state.value.editingToolsVisible)
            return
        }
        if (action == KeyboardAction.SelectAll) {
            if (runCatching { textSession.selectAll() }.getOrDefault(false)) {
                adoptEditorSelection()
                _state.value = _state.value.copy(hasSelection = true)
            }
            return
        }
        if (action == KeyboardAction.CopySelection) {
            runCatching { textSession.copySelection() }
            return
        }
        if (action == KeyboardAction.CutSelection) {
            if (runCatching { textSession.cutSelection() }.getOrDefault(false)) {
                adoptEditorSelection()
                clearUndoState()
                resetEnglishTracking()
                _state.value = _state.value.copy(hasSelection = false)
            }
            return
        }
        if (action == KeyboardAction.Paste) {
            if (runCatching { textSession.paste() }.getOrDefault(false)) {
                adoptEditorSelection()
                clearUndoState()
                resetEnglishTracking()
                _state.value = _state.value.copy(hasSelection = false)
            }
            return
        }
        if (action is KeyboardAction.MoveCursorByWord) {
            if (runCatching { textSession.moveCursorByWord(action.delta) }.getOrDefault(false)) {
                adoptEditorSelection()
                clearUndoState()
                resetEnglishTracking()
                _state.value = _state.value.copy(hasSelection = false)
            }
            return
        }
        if (action == KeyboardAction.DeleteWord) {
            if (runCatching { textSession.deleteBackwardWord() }.getOrDefault(false)) {
                adoptEditorSelection()
                clearUndoState()
                resetEnglishTracking()
                _state.value = _state.value.copy(hasSelection = false)
            }
            return
        }
        trackEnglishInput(action, _state.value)
        if (actionClearsUndo(action)) clearUndoState()
        committedThisDispatch.clear()
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
        if (committedThisDispatch.isNotEmpty()) {
            val first = committedThisDispatch.first()
            val last = committedThisDispatch.last()
            val committed = committedThisDispatch.joinToString("") { it.text }
            if (committed.isNotEmpty()) {
                undoStack.addLast(
                    UndoRecord(
                        text = committed,
                        start = first.start,
                        end = last.end,
                        sessionId = inputSessionId,
                        replacedText = first.replacedText,
                    ),
                )
                while (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
                redoStack.clear()
                updateUndoAvailability()
            }
        }
        recordLearnedSelection(reduction)
        refreshEnglishSuggestions()

        if (reduction.state.composing != previous.composing ||
            reduction.state.compositionVersion != previous.compositionVersion
        ) {
            _state.value = _state.value.copy(
                aiCandidates = emptyList(),
                aiCandidatesLoading = false,
                aiCandidatesError = null,
            )
            if (aiJob != null) cancelAi()
            if (KeyboardReducer.isLiteralComposing(reduction.state)) {
                // D07: a literal English run is not pinyin. Never schedule a
                // pinyin query (and cancel any stale one) while it is active.
                pinyinJob?.cancel()
            } else {
                schedulePinyinQuery(reduction.state.composing, reduction.state.compositionVersion)
            }
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
                if (reduction.state.composing.isNotEmpty() &&
                    !KeyboardReducer.isLiteralComposing(reduction.state)
                ) {
                    schedulePinyinQuery(reduction.state.composing, reduction.state.compositionVersion)
                }
            }

            else -> Unit
        }
    }

    fun onSettingsChanged(newSettings: BitapSettings) {
        val aiWasEnabled = settings.aiEnabled
        settings = newSettings
        applyLearningPolicy()
        if (!newSettings.aiEnabled) {
            cancelAi()
            if (aiWasEnabled) {
                // D02: turning AI off must release the native engine, not merely
                // cancel the current generation. setEnabled(false) serialises
                // cancel -> join -> release inside the service.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { aiService.setEnabled(false) }
                }
            }
        } else if (!aiWasEnabled) {
            // D02: re-enabling is lazy; no engine is loaded until the next request.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { aiService.setEnabled(true) }
            }
        }
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

    /** P0-B: reports the pinyin dictionary lifecycle from the IME service. */
    fun onDictionaryStateChanged(state: DictionaryLoadState, error: String? = null) {
        reduceInto(KeyboardAction.DictionaryStateChanged(state, error))
    }

    /** Called when the pinyin dictionary finished loading so a pending query can retry. */
    fun onPinyinReady() {
        val composing = _state.value.composing
        if (composing.isNotEmpty() && !KeyboardReducer.isLiteralComposing(_state.value)) {
            schedulePinyinQuery(composing, _state.value.compositionVersion)
        }
    }

    /**
     * New editor focused. The complete input configuration is applied through the
     * reducer so no language/layer/composing state leaks from the previous field.
     */
    fun onStartInput(editorInfo: EditorInputInfo) {
        pinyinJob?.cancel()
        lastSelectionSnapshot = null
        resetVirtualEditorTracking()
        // The settings screen may have cleared/changed persisted English
        // learning through another store instance while this keyboard was alive.
        syncEnglishPredictionsFromStore()
        cancelAi()
        inputSessionId = nextInputSessionId()
        dispatch(KeyboardAction.EditorInfoChanged(editorInfo, settings.aiEnabled, inputSessionId))
        // Belt and braces: the reducer resets these on EditorInfoChanged, but the
        // IME lifecycle must never leak the previous field's editing page.
        resetEditingState()
        resetEnglishTracking()
        applyLearningPolicy()
    }

    /** Clears the running engine's in-memory and persisted user lexicon. */
    fun clearUserLexicon() {
        inputEngine.clearLearning()
        englishPredictor.clear()
        runCatching { englishPredictionStore.clear() }
        lastEnglishPredictionData = EnglishPredictionData()
        resetEnglishTracking()
    }

    private fun applyLearningPolicy() {
        inputEngine.setLearningEnabled(
            settings.learningEnabled && _state.value.editorInfo.allowsPersonalizedLearning,
        )
    }

    private fun scheduleUserLexiconPersist() {
        userLexiconPersistJob?.cancel()
        userLexiconPersistJob = scope.launch {
            delay(USER_LEXICON_PERSIST_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { inputEngine.persistSelectionWeights() }
        }
    }

    fun onFinishInput() {
        pinyinJob?.cancel()
        lastSelectionSnapshot = null
        cancelAi()
        runCatching { textSession.finishComposingText() }
        resetVirtualEditorTracking()
        syncEnglishPredictionsFromStore()
        inputSessionId = nextInputSessionId()
        dispatch(KeyboardAction.EditorInfoChanged(EditorInputInfo(), settings.aiEnabled, inputSessionId))
        resetEditingState()
        resetEnglishTracking()
    }

    /** Clears editing-page UI that belongs to the previous field. */
    private fun resetEditingState() {
        if (_state.value.hasSelection || _state.value.editingToolsVisible) {
            _state.value = _state.value.copy(hasSelection = false, editingToolsVisible = false)
        }
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
        // task-1 contract: keep TextSession's cached absolute selection fresh on
        // every callback, including repeated/composing-end ones that return early.
        runCatching { textSession.updateSelection(selectionStart, selectionEnd) }
        val snapshot = SelectionSnapshot(selectionStart, selectionEnd, candidatesStart, candidatesEnd)
        val samePosition = oldSelectionStart >= 0 && oldSelectionEnd >= 0 &&
            oldSelectionStart == selectionStart && oldSelectionEnd == selectionEnd
        val repeated = samePosition || lastSelectionSnapshot == snapshot ||
            (oldSelectionStart >= 0 && oldSelectionEnd >= 0 &&
                oldSelectionStart == selectionStart && oldSelectionEnd == selectionEnd &&
                lastSelectionSnapshot?.let {
                    it.candidatesStart == candidatesStart && it.candidatesEnd == candidatesEnd
                } == true)
        if (repeated) {
            // Even a repeated callback is authoritative for our virtual selection
            // when nothing else has been observed yet.
            if (virtualSelection == null && selectionStart >= 0 && selectionEnd >= 0) {
                virtualSelection = EditorSelection(selectionStart, selectionEnd)
            }
            return
        }

        val state = _state.value
        val normalComposingEnd = state.composing.isNotEmpty() &&
            candidatesStart >= 0 && candidatesEnd >= candidatesStart &&
            selectionStart == selectionEnd && selectionEnd == candidatesEnd
        lastSelectionSnapshot = snapshot

        val newSelection =
            if (selectionStart >= 0 && selectionEnd >= 0) EditorSelection(selectionStart, selectionEnd) else null
        // Our own commits/composing updates produce selection callbacks; treating
        // them as external would reset the English buffer and undo history on
        // every keystroke. Match against the queued expectation (falling back to
        // an unchanged virtual selection for coalesced callbacks).
        val expectedIndex = newSelection?.let { candidate ->
            expectedSelectionChanges.indexOfFirst { expected ->
                expected.to == candidate &&
                    (oldSelectionStart < 0 || expected.from == null ||
                        expected.from == EditorSelection(oldSelectionStart, oldSelectionEnd))
            }
        } ?: -1
        val fromOwnCommand = expectedIndex >= 0
        if (fromOwnCommand) {
            repeat(expectedIndex + 1) { expectedSelectionChanges.removeFirst() }
        } else if (newSelection != null && newSelection == virtualSelection) {
            expectedSelectionChanges.clear()
        }
        val internal = fromOwnCommand || normalComposingEnd ||
            (newSelection != null && newSelection == virtualSelection)

        if (newSelection != null) virtualSelection = newSelection
        if (!internal) {
            // External cursor/selection move: the typed English prefix no longer
            // belongs before this caret and the exact commit range is gone.
            virtualComposingStart = null
            expectedSelectionChanges.clear()
            clearUndoState()
            resetEnglishTracking()
        } else if (normalComposingEnd && candidatesStart >= 0) {
            virtualComposingStart = candidatesStart
        }

        // A composing-end callback is generated by our own setComposingText. It
        // must not cancel a still-valid AI request or clear its loading state,
        // but the collapsed caret it reports still clears a stale selection flag.
        if (normalComposingEnd) {
            if (_state.value.hasSelection) _state.value = _state.value.copy(hasSelection = false)
            return
        }

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

    /** Releases coroutines, cancels any in-flight AI request and unloads the native engine. */
    fun dispose() {
        pinyinJob?.cancel()
        userLexiconPersistJob?.cancel()
        cancelAi()
        // D02: IME teardown must release the AI engine. requestUnload() keeps the
        // serialised cancel -> join -> release on the service-owned scope because
        // BitapImeService cancels this controller's scope immediately after dispose().
        runCatching { aiService.requestUnload() }
        // Persist synchronously only at service teardown, never on the key path.
        runCatching { inputEngine.persistSelectionWeights() }
        runCatching { inputEngine.close() }
    }

    private fun nextInputSessionId(): String {
        inputSessionCounter++
        return "ime-" + inputSessionCounter
    }

    /**
     * Executes one reducer command and advances the virtual caret by the known
     * effect of that command. Commit positions therefore stay available without
     * reading the whole editor, and the expected host selection callbacks are
     * queued so they are not mistaken for external cursor moves.
     */
    private fun execute(command: ImeCommand) {
        when (command) {
            is ImeCommand.CommitText -> {
                val text = command.text
                val start = virtualComposingStart ?: selectionStartOrFallback()
                val selectionBefore = virtualSelection
                // A non-collapsed selection is replaced by commitText; keep the
                // exact old text so undo can restore the selection content.
                val replacedText = if (selectionBefore != null && selectionBefore.start != selectionBefore.end) {
                    runCatching { textSession.getSelectedText() }.getOrNull().orEmpty()
                } else {
                    ""
                }
                textSession.commitText(text)
                val end = start + text.length
                virtualComposingStart = null
                virtualSelection = EditorSelection(end, end)
                if (text.isNotEmpty()) {
                    committedThisDispatch += UndoRecord(
                        text = text,
                        start = start,
                        end = end,
                        sessionId = inputSessionId,
                        replacedText = replacedText,
                    )
                    queueExpectedSelection(from = selectionBefore ?: EditorSelection(start, start), to = end)
                }
            }

            is ImeCommand.SetComposingText -> {
                val text = command.text
                val start = virtualComposingStart ?: selectionStartOrFallback()
                textSession.setComposingText(text)
                val end = start + text.length
                virtualComposingStart = if (text.isEmpty()) null else start
                virtualSelection = EditorSelection(end, end)
                queueExpectedSelection(from = EditorSelection(start, start), to = end)
            }

            ImeCommand.FinishComposing -> {
                textSession.finishComposingText()
                virtualComposingStart = null
                virtualSelection?.let { queueExpectedSelection(from = it, to = it.start) }
            }

            ImeCommand.DeleteBackward -> {
                val selectionBefore = virtualSelection
                textSession.deleteBackward()
                val start = selectionBefore?.let { minOf(it.start, it.end) }
                val end = selectionBefore?.let { maxOf(it.start, it.end) }
                val caret = if (start != null && end != null && start != end) {
                    start
                } else {
                    ((selectionBefore?.start ?: boundedCaretFallback() ?: 1) - 1).coerceAtLeast(0)
                }
                virtualSelection = EditorSelection(caret, caret)
                virtualComposingStart?.let { composingStart ->
                    if (caret <= composingStart) virtualComposingStart = null
                }
                queueExpectedSelection(from = selectionBefore, to = caret)
            }

            ImeCommand.SendEnter -> {
                textSession.sendEnter()
                // The editor may insert a newline or perform an action; exact
                // caret tracking is no longer guaranteed until the host reports.
                virtualComposingStart = null
                virtualSelection = null
            }
        }
    }

    /** Absolute caret start for a local edit; reads at most a bounded window. */
    private fun selectionStartOrFallback(): Int =
        virtualSelection?.let { minOf(it.start, it.end) }
            ?: boundedCaretFallback()
            ?: 0

    /** Bounded fallback when the host has not reported an absolute selection yet. */
    private fun boundedCaretFallback(): Int? = runCatching {
        textSession.getTextBeforeCursor(MAX_CURSOR_FALLBACK_READ)?.length
    }.getOrNull()

    private fun queueExpectedSelection(from: EditorSelection?, to: Int) {
        expectedSelectionChanges.addLast(ExpectedSelection(from = from, to = EditorSelection(to, to)))
        while (expectedSelectionChanges.size > MAX_EXPECTED_SELECTION_CHANGES) {
            expectedSelectionChanges.removeFirst()
        }
    }

    /**
     * Undo the exact last IME commit only when the editor's absolute selection is
     * still collapsed exactly at the recorded commit end and the recorded text
     * immediately precedes it. A cursor move or outside edit invalidates the
     * whole history instead of deleting unrelated text.
     */
    private fun handleUndo() {
        val state = _state.value
        if (state.composing.isNotEmpty()) return
        val record = undoStack.lastOrNull() ?: return
        if (record.sessionId != inputSessionId) {
            clearUndoState()
            return
        }
        val selection = currentEditorSelection()
        if (selection == null || selection.start != selection.end || selection.start != record.end) {
            clearUndoState()
            return
        }
        val before = runCatching { textSession.getTextBeforeCursor(record.text.length) }.getOrNull()
        if (before != record.text) {
            clearUndoState()
            return
        }
        runCatching { textSession.deleteBackward(record.text.length) }
        if (record.replacedText.isNotEmpty()) {
            // Restore the exact text this edit replaced (English prefix or a
            // selection), returning the caret to the original edit end.
            runCatching { textSession.commitText(record.replacedText) }
        }
        undoStack.removeLast()
        redoStack.addLast(record)
        while (redoStack.size > MAX_UNDO_STEPS) redoStack.removeFirst()
        noteDirectEditorEdit(record.start + record.replacedText.length)
        updateUndoAvailability()
        resetEnglishTracking()
    }

    private fun handleRedo() {
        val state = _state.value
        if (state.composing.isNotEmpty()) return
        val record = redoStack.lastOrNull() ?: return
        if (record.sessionId != inputSessionId) {
            clearUndoState()
            return
        }
        val selection = currentEditorSelection()
        val restoredEnd = record.start + record.replacedText.length
        if (selection == null || selection.start != selection.end || selection.start != restoredEnd) {
            clearUndoState()
            return
        }
        if (record.replacedText.isNotEmpty()) {
            val before = runCatching { textSession.getTextBeforeCursor(record.replacedText.length) }.getOrNull()
            if (before != record.replacedText) {
                clearUndoState()
                return
            }
            runCatching { textSession.deleteBackward(record.replacedText.length) }
        }
        runCatching { textSession.commitText(record.text) }
        redoStack.removeLast()
        undoStack.addLast(record)
        while (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
        noteDirectEditorEdit(record.end)
        updateUndoAvailability()
        resetEnglishTracking()
    }

    /** Absolute selection reported by the host, or our virtual/bounded fallback. */
    private fun currentEditorSelection(): EditorSelection? =
        runCatching { textSession.getSelection() }.getOrNull()
            ?: virtualSelection
            ?: boundedCaretFallback()?.let { EditorSelection(it, it) }

    /** Records a direct editor edit that bypassed [execute]. */
    private fun noteDirectEditorEdit(caret: Int) {
        virtualComposingStart = null
        virtualSelection = EditorSelection(caret, caret)
        queueExpectedSelection(from = null, to = caret)
    }

    /** Re-adopts the host-reported selection after a direct TextSession operation. */
    private fun adoptEditorSelection() {
        runCatching { textSession.getSelection() }.getOrNull()?.let { virtualSelection = it }
    }

    /** Forgets all absolute editor knowledge for a new/finished input connection. */
    private fun resetVirtualEditorTracking() {
        virtualSelection = runCatching { textSession.getSelection() }.getOrNull()
        virtualComposingStart = null
        expectedSelectionChanges.clear()
    }


    /**
     * Tracks the English word being typed and records it at a word boundary.
     * The model is session-local; [EnglishPredictor] is seeded from store at
     * construction so committed words survive IME restarts when persistence is
     * available.
     */
    private fun trackEnglishInput(action: KeyboardAction, state: KeyboardUiState) {
        if (state.language != InputLanguage.English ||
            state.editorInfo.isPassword ||
            state.editorInfo.numericMode != NumericInputMode.None
        ) {
            if (action is KeyboardAction.EditorInfoChanged) resetEnglishTracking()
            return
        }
        when (action) {
            is KeyboardAction.AppendInput -> {
                val text = action.text
                if (text.length == 1 && text[0].isLetter()) {
                    englishBuffer += text.lowercase()
                } else if (text.isNotEmpty()) {
                    commitEnglishBuffer()
                }
            }

            KeyboardAction.Backspace -> {
                if (englishBuffer.isNotEmpty()) englishBuffer = englishBuffer.dropLast(1)
            }

            KeyboardAction.Space, KeyboardAction.Enter -> commitEnglishBuffer()

            KeyboardAction.ToggleLanguage -> {
                commitEnglishBuffer()
                resetEnglishTracking()
            }

            is KeyboardAction.EditorInfoChanged -> resetEnglishTracking()
            else -> Unit
        }
    }

    private fun commitEnglishBuffer() {
        val word = englishBuffer
        if (word.isNotEmpty()) {
            if (settings.learningEnabled && _state.value.editorInfo.allowsPersonalizedLearning) {
                // Adopt externally cleared/changed learning data before recording
                // so a stale in-memory word is never saved back (P1-1).
                syncEnglishPredictionsFromStore()
                englishPredictor.recordCommittedWord(word, englishLastWord)
                persistEnglishPredictions()
            }
            englishLastWord = word
            englishBuffer = ""
        }
    }

    /**
     * Adopts the persisted English model when another store instance (the
     * settings screen) cleared or changed it. [EnglishPredictionStore.None] is a
     * deliberate no-persistence sink, so the in-memory model stays authoritative.
     */
    private fun syncEnglishPredictionsFromStore() {
        if (englishPredictionStore === EnglishPredictionStore.None) return
        val stored = runCatching { englishPredictionStore.load() }.getOrNull() ?: return
        if (stored != lastEnglishPredictionData) {
            englishPredictor.resetFrom(stored)
            lastEnglishPredictionData = stored
        }
    }

    private fun persistEnglishPredictions() {
        syncEnglishPredictionsFromStore()
        val snapshot = englishPredictor.snapshot()
        runCatching { englishPredictionStore.save(snapshot) }
        lastEnglishPredictionData = snapshot
    }

    private fun refreshEnglishSuggestions() {
        val state = _state.value
        val eligible = state.language == InputLanguage.English &&
            !state.editorInfo.isPassword &&
            state.editorInfo.numericMode == NumericInputMode.None
        val suggestions = if (eligible) {
            englishPredictor.suggestions(englishBuffer, englishLastWord)
        } else {
            emptyList()
        }
        if (state.englishSuggestions != suggestions) {
            _state.value = state.copy(englishSuggestions = suggestions)
        }
    }

    private fun resetEnglishTracking() {
        englishBuffer = ""
        englishLastWord = null
        if (_state.value.englishSuggestions.isNotEmpty()) {
            _state.value = _state.value.copy(englishSuggestions = emptyList())
        }
    }

    private fun handleEnglishSuggestionTapped(word: String) {
        val state = _state.value
        if (state.language != InputLanguage.English || state.editorInfo.isPassword) return
        if (word !in state.englishSuggestions) return
        val selection = currentEditorSelection()
        if (selection == null || selection.start != selection.end) {
            // A completion only replaces a collapsed caret; never delete around a
            // stale caret or over a real selection.
            resetEnglishTracking()
            return
        }
        val caret = selection.start
        val prefix = englishBuffer
        // The tracked prefix must still be exactly what sits before the caret
        // (case-insensitive) before anything is deleted (P1-2). Keep the actual
        // typed text, including its case, so undo can restore it exactly.
        val replacedText = if (prefix.isEmpty()) {
            ""
        } else {
            val before = runCatching { textSession.getTextBeforeCursor(prefix.length) }.getOrNull()
            if (before == null || !before.equals(prefix, ignoreCase = true)) {
                resetEnglishTracking()
                return
            }
            before
        }
        if (prefix.isNotEmpty()) {
            runCatching { textSession.deleteBackward(prefix.length) }
        }
        val inserted = word + " "
        runCatching { textSession.commitText(inserted) }
        val start = caret - prefix.length
        undoStack.addLast(
            UndoRecord(
                text = inserted,
                start = start,
                end = start + inserted.length,
                sessionId = inputSessionId,
                replacedText = replacedText,
            ),
        )
        while (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
        redoStack.clear()
        noteDirectEditorEdit(start + inserted.length)
        updateUndoAvailability()
        if (settings.learningEnabled && state.editorInfo.allowsPersonalizedLearning) {
            syncEnglishPredictionsFromStore()
            englishPredictor.recordCommittedWord(word, englishLastWord)
            persistEnglishPredictions()
        }
        englishLastWord = word
        englishBuffer = ""
        refreshEnglishSuggestions()
    }

    private fun updateUndoAvailability() {
        val canUndo = undoStack.isNotEmpty()
        val canRedo = redoStack.isNotEmpty()
        if (_state.value.canUndo != canUndo || _state.value.canRedo != canRedo) {
            _state.value = _state.value.copy(canUndo = canUndo, canRedo = canRedo)
        }
    }

    private fun clearUndoState() {
        undoStack.clear()
        redoStack.clear()
        updateUndoAvailability()
    }

    /**
     * Only user-visible edit/navigation actions invalidate the undo target.
     * Internal query/AI completions must not make the undo button disappear.
     */
    private fun actionClearsUndo(action: KeyboardAction): Boolean = when (action) {
        // Keep the bounded history across ordinary typing and cursor callbacks so
        // several committed candidates can be undone one by one. Exact-suffix
        // checking in [handleUndo] and [handleRedo] prevents touching text that
        // was edited or moved outside the IME.
        is KeyboardAction.EditorInfoChanged -> true

        else -> false
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
            inputEngine.recordSelection(
                SelectionEvent(
                    word = event.word,
                    pinyin = event.pinyin,
                    source = event.source,
                    consumedInputLength = event.consumedInputLength,
                ),
            )
        }
        scheduleUserLexiconPersist()
    }

    /**
     * Schedules one debounced pinyin query.
     *
     * P0 long-input consumption rule: [composing] is always the FULL visible
     * composition and stays that way in [KeyboardUiState.composing], so
     * backspace, commit-raw and editor state remain reliable at any length.
     * Only the query input is bounded: [InputEngine.queryInputFor] returns at
     * most [InputEngine.maxQueryInputLength] leading characters. Candidate
     * [com.bitap.keyboard.core.Candidate.consumedInputLength] offsets therefore
     * refer to that queried prefix, not to the full composing string. When a
     * candidate consumes the prefix, the reducer drops exactly those characters;
     * the unconsumed tail stays composing and the state change schedules the
     * next query for the remaining text (again bounded and re-segmented).
     */
    private fun schedulePinyinQuery(composing: String, version: Long = _state.value.compositionVersion) {
        pinyinJob?.cancel()
        if (composing.isEmpty()) return
        val token = ++pinyinQueryToken
        val sessionId = inputSessionId
        // Pure budget computation; the engine still receives a versioned
        // QueryRequest and the full composition remains in UI state.
        val queryInput = inputEngine.queryInputFor(composing, queryInputMode)
        pinyinJob = scope.launch {
            delay(PINYIN_DEBOUNCE_MS)
            if (token != pinyinQueryToken) return@launch
            // P0-A: a throwing engine must not leave a pending confirmation
            // waiting forever. CancellationException is rethrown unchanged so
            // coroutine cancellation keeps its original semantics; any other
            // throwable becomes an explicit PinyinQueryFailed event below.
            val outcome = runPinyinQueryCatchingFailure {
                withContext(queryDispatcher) {
                    // The engine cooperates with coroutine cancellation so old
                    // queries stop using the CPU. QueryRequest carries the session
                    // and composition version; the result is only applied when both
                    // still match the visible composition (D03).
                    inputEngine.query(
                        QueryRequest(
                            sessionId = sessionId,
                            compositionVersion = version,
                            rawInput = queryInput,
                            limit = InputEngine.DEFAULT_LIMIT,
                            context = QueryContext.None,
                            shouldContinue = { isActive },
                        ),
                    )
                }
            }
            if (!isActive || token != pinyinQueryToken) return@launch
            if (outcome.isFailure) {
                dispatch(
                    KeyboardAction.PinyinQueryFailed(
                        composing = composing,
                        version = version,
                        sessionId = sessionId,
                        message = KeyboardReducer.CANDIDATE_QUERY_FAILED_MESSAGE,
                    ),
                )
                return@launch
            }
            val result = outcome.getOrThrow()
            if (result.sessionId != sessionId || result.compositionVersion != version) return@launch
            val candidates = result.candidates.map { candidate ->
                // 0 is the legacy "whole composition" convention. A budgeted
                // engine only saw [queryInput], so a zero consumption can only
                // mean that queried prefix; map it explicitly before the
                // reducer (without a budget queryInput is the whole composing,
                // so legacy engines keep their exact behavior).
                val requestedConsumed = candidate.consumedInputLength
                val consumed = if (requestedConsumed <= 0) {
                    queryInput.length
                } else {
                    // A budgeted engine cannot have inspected beyond queryInput;
                    // clamp a bad/legacy over-claim instead of consuming unseen text.
                    requestedConsumed.coerceAtMost(queryInput.length)
                }
                candidate.copy(consumedInputLength = consumed)
                    .toKeyboardCandidate(result.compositionVersion, result.sessionId)
            }
            // Dispatch through the same path as user actions so a queued
            // confirmation executes its CommitText when the fresh result lands.
            // The FULL composing string is reported so stale/session guards keep
            // comparing against the visible composition.
            dispatch(KeyboardAction.ComposingUpdated(composing, candidates, version, result.sessionId))
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
        // D07: literal English runs are not pinyin; the AI lexicon must not query them.
        if (KeyboardReducer.isLiteralComposing(_state.value)) return
        aiJob?.cancel()
        aiService.cancel()
        val composing = _state.value.composing
        val rawContext = runCatching { textSession.readAiSource(128) }.getOrDefault("")
        val selectedText = runCatching { textSession.getSelectedText() }.getOrNull()
        val beforeCursor = runCatching { textSession.getTextBeforeCursor(128) }.getOrNull()
        val rawPlan = if (composing.isEmpty()) {
            AiRawContextResolver.resolve(
                selected = selectedText,
                beforeCursor = beforeCursor,
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
            // The entry only generates candidates from pinyin. Do not suggest that
            // a Chinese selection can be rewritten here; say what actually works.
            val message = if (composing.isEmpty() && !selectedText.isNullOrBlank()) {
                KeyboardReducer.AI_LEXICON_SELECTION_NOT_PINYIN_MESSAGE
            } else {
                KeyboardReducer.AI_LEXICON_QUERY_REQUIRED_MESSAGE
            }
            reduceInto(KeyboardAction.AiCandidatesFailed(message))
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
        val selectionBefore = currentEditorSelection()
        val commitStart = if (plan.selected) {
            selectionBefore?.let { minOf(it.start, it.end) } ?: 0
        } else {
            ((selectionBefore?.start ?: boundedCaretFallback() ?: plan.original.length) - plan.original.length)
                .coerceAtLeast(0)
        }
        if (!plan.selected) repeat(plan.original.length) { textSession.deleteBackward() }
        textSession.commitText(word)
        undoStack.addLast(
            UndoRecord(
                text = word,
                start = commitStart,
                end = commitStart + word.length,
                sessionId = inputSessionId,
                replacedText = plan.original,
            ),
        )
        while (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
        noteDirectEditorEdit(commitStart + word.length)
        if (settings.learningEnabled && state.editorInfo.allowsPersonalizedLearning) {
            inputEngine.recordSelection(
                SelectionEvent(
                    word = word,
                    pinyin = plan.query,
                    source = "ai",
                    consumedInputLength = plan.original.length,
                ),
            )
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
            canUndo = true,
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
        const val MAX_UNDO_STEPS = 10
        const val MAX_CURSOR_FALLBACK_READ = 4_096
        const val MAX_EXPECTED_SELECTION_CHANGES = 64
    }
}

/**
 * Executes one pinyin engine query and reports non-cancellation failures as
 * [Result.failure]. [CancellationException] is deliberately rethrown unchanged:
 * cancelling an obsolete composition must stay a cancellation, never a
 * user-visible query failure. Internal (not private) so the JVM regression
 * suite can pin this contract without a real engine.
 */
internal suspend fun <T> runPinyinQueryCatchingFailure(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
