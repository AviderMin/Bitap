package com.bitap.keyboard.core

import com.bitap.keyboard.ai.RewriteTone

/**
 * Pure keyboard state machine. Given the same state/action it always produces the
 * same [Reduction]; all InputConnection work is expressed as [ImeCommand] values
 * and performed later by KeyboardController.
 */
object KeyboardReducer {

    const val MAX_AI_SOURCE_CHARS = 512
    const val MAX_AI_RESULT_CHARS = MAX_AI_SOURCE_CHARS * 3

    /**
     * The ✦ AI entry is a local AI *lexicon candidate* generator: its input is
     * pinyin only, never a Chinese text rewrite request. These messages must stay
     * actionable and must not suggest selecting arbitrary Chinese text.
     */
    const val AI_LEXICON_QUERY_REQUIRED_MESSAGE =
        "AI 词库只根据拼音给出候选，请先输入拼音"

    const val AI_LEXICON_SELECTION_NOT_PINYIN_MESSAGE =
        "AI 词库只根据拼音给出候选；当前选区不是拼音，请先输入拼音或选中一段拼音"

    /** Short hint when a pinyin query failed without an active confirmation. */
    const val CANDIDATE_QUERY_FAILED_MESSAGE = "候选查询失败，请重试或继续输入"

    /** Hint when a failed query had to fall back to committing the raw text. */
    const val CANDIDATE_QUERY_FALLBACK_MESSAGE = "候选查询失败，已按原文提交"

    /** Short user-facing reason for a failed dictionary load (P0-B). */
    const val DICTIONARY_LOAD_FAILED_MESSAGE = "词库加载失败"

    /**
     * D07: sentinel stored in [KeyboardUiState.candidatesFor] while
     * [KeyboardUiState.composing] is a literal English run typed in Chinese
     * mode. It can never equal real pinyin composing, so the controller can
     * skip pinyin queries without a separate UI state flag, while the visible
     * composing text still preserves the typed case.
     */
    const val LITERAL_COMPOSING_MARKER = "\u0000literal"

    /** True when [state] is a D07 literal English run (not a pinyin composition). */
    fun isLiteralComposing(state: KeyboardUiState): Boolean =
        state.composing.isNotEmpty() && state.candidatesFor == LITERAL_COMPOSING_MARKER

    private const val DOUBLE_TAP_WINDOW_MS = 350L

    fun reduce(state: KeyboardUiState, action: KeyboardAction): Reduction = when (action) {
        KeyboardAction.ToggleLanguage -> toggleLanguage(state)
        is KeyboardAction.ShiftTapped -> shiftTapped(state, action.now)
        is KeyboardAction.ShowLayer -> showLayer(state, action.layer)
        is KeyboardAction.AppendInput -> appendInput(state, action.text)
        is KeyboardAction.ComposingUpdated -> composingUpdated(state, action)
        is KeyboardAction.PinyinQueryFailed -> pinyinQueryFailed(state, action)
        is KeyboardAction.DictionaryStateChanged -> dictionaryStateChanged(state, action)
        KeyboardAction.RetryDictionaryLoad -> retryDictionaryLoad(state)
        is KeyboardAction.CandidateTapped -> candidateTapped(state, action.index)
        KeyboardAction.CommitRaw -> commitRaw(state)
        // The controller owns the actual editor deletion; pure reducer callers
        // keep state unchanged so Undo cannot become a hidden text edit.
        KeyboardAction.Undo -> Reduction(state)
        KeyboardAction.Redo -> Reduction(state)
        is KeyboardAction.MoveCursor -> Reduction(state)
        // Editing-tool actions are performed by KeyboardController through
        // TextSession; direct reducer callers only observe unchanged state.
        KeyboardAction.ToggleEditingTools,
        KeyboardAction.SelectAll,
        KeyboardAction.CopySelection,
        KeyboardAction.CutSelection,
        KeyboardAction.Paste,
        KeyboardAction.DeleteWord -> Reduction(state)
        is KeyboardAction.MoveCursorByWord -> Reduction(state)
        KeyboardAction.Space -> space(state)
        KeyboardAction.Backspace -> backspace(state)
        KeyboardAction.Enter -> enter(state)
        is KeyboardAction.EditorInfoChanged -> editorInfoChanged(state, action)
        is KeyboardAction.SelectionChanged -> selectionChanged(state, action)
        KeyboardAction.RequestAiCandidates -> requestAiCandidates(state)
        is KeyboardAction.AiCandidatesUpdated -> aiCandidatesUpdated(state, action.candidates)
        is KeyboardAction.AiCandidatesFailed -> aiCandidatesFailed(state, action.message)
        is KeyboardAction.AiCandidateTapped -> aiCandidateTapped(state, action.word)
        is KeyboardAction.EnglishSuggestionTapped -> Reduction(state)
        is KeyboardAction.AiPanelOpened -> aiPanelOpened(state, action.source)
        is KeyboardAction.AiToneChosen -> aiToneChosen(state, action.tone)
        KeyboardAction.AiCancel -> aiCancel(state)
        KeyboardAction.AiCandidatesCancelled -> aiCandidatesCancelled(state)
        is KeyboardAction.AiDelta -> aiDelta(state, action.text)
        is KeyboardAction.AiCompleted -> aiCompleted(state, action.text)
        is KeyboardAction.AiFailed -> aiFailed(state, action.message)
        KeyboardAction.AiInsert -> aiInsert(state)
        KeyboardAction.AiDismiss -> aiDismiss(state)
        is KeyboardAction.AiAvailabilityChanged -> aiAvailabilityChanged(state, action)
        is KeyboardAction.HapticsChanged -> state.copy(hapticsEnabled = action.enabled).let { Reduction(it) }
        is KeyboardAction.SoundChanged -> state.copy(soundEnabled = action.enabled).let { Reduction(it) }
    }

    private fun toggleLanguage(state: KeyboardUiState): Reduction {
        if (state.pendingConfirmation != null) {
            return enqueueAction(state, KeyboardAction.ToggleLanguage)
        }
        // Number/phone/password are hard language locks; Email/URL start in
        // English but may deliberately switch to Chinese search (R09).
        if (!state.editorInfo.supportsChineseInput) return Reduction(state)
        val commands = if (state.composing.isEmpty()) {
            emptyList()
        } else {
            listOf(ImeCommand.CommitText(state.composing))
        }
        val nextLanguage = when (state.language) {
            InputLanguage.Chinese -> InputLanguage.English
            InputLanguage.English -> InputLanguage.Chinese
        }
        return Reduction(
            state.copy(
                language = nextLanguage,
                layer = KeyboardLayer.Letters,
                shift = ShiftState.Off,
                composing = "",
                candidates = emptyList(),
                candidatesFor = "",
                candidateQueryError = null,
                pendingConfirmation = null,
                lastShiftTapAt = 0L,
            ),
            commands,
        )
    }

    private fun shiftTapped(state: KeyboardUiState, now: Long): Reduction {
        if (state.layer != KeyboardLayer.Letters) return Reduction(state)
        val withinDoubleTap = state.lastShiftTapAt > 0L &&
            now > 0L &&
            now - state.lastShiftTapAt in 1..DOUBLE_TAP_WINDOW_MS
        val next = when {
            withinDoubleTap && state.shift != ShiftState.Locked -> ShiftState.Locked
            state.shift == ShiftState.Off -> ShiftState.Shifted
            else -> ShiftState.Off
        }
        return Reduction(state.copy(shift = next, lastShiftTapAt = now))
    }

    private fun showLayer(state: KeyboardUiState, layer: KeyboardLayer): Reduction {
        if (state.pendingConfirmation != null) {
            return enqueueAction(state, KeyboardAction.ShowLayer(layer))
        }
        if (layer == state.layer) return Reduction(state)
        // R05/S01: switching layers must not commit an active composition, and
        // must not destroy candidates that still belong to the same composing.
        val keepCandidates = state.candidatesFor == state.composing
        val nextCandidatesFor = when {
            keepCandidates -> state.candidatesFor
            // D07: a layer switch must not drop the literal-run marker.
            isLiteralComposing(state) -> LITERAL_COMPOSING_MARKER
            else -> ""
        }
        return Reduction(
            state.copy(
                layer = layer,
                shift = ShiftState.Off,
                candidates = if (keepCandidates) state.candidates else emptyList(),
                candidatesFor = nextCandidatesFor,
                lastShiftTapAt = 0L,
            ),
        )
    }

    private fun appendInput(state: KeyboardUiState, text: String): Reduction {
        if (text.isEmpty()) return Reduction(state)
        if (state.editorInfo.numericMode != NumericInputMode.None &&
            !isNumericInputAllowed(state.editorInfo, text)
        ) {
            // Numeric editors may reject letters/symbols the layout could still
            // expose through legacy paths; never send them a key that cannot work.
            return Reduction(state)
        }
        if (state.pendingConfirmation != null) {
            return enqueueAction(state, KeyboardAction.AppendInput(text))
        }
        // D07: an active literal run consumes every literal character before any
        // layer-specific path, so moving to Numbers/Symbols to type digits,
        // underscore or hyphen never queues a pinyin confirmation.
        if (isLiteralComposing(state)) {
            return appendLiteralInput(state, text)
        }
        if (state.language == InputLanguage.Chinese &&
            state.editorInfo.allowsChineseInput &&
            isPinyinSeparator(text)
        ) {
            return appendPinyinSeparator(state)
        }
        if (state.layer != KeyboardLayer.Letters) {
            return if (state.language == InputLanguage.Chinese && state.composing.isNotEmpty()) {
                commitCompositionThen(state, text)
            } else {
                Reduction(state, listOf(ImeCommand.CommitText(text)))
            }
        }
        if (!state.editorInfo.supportsChineseInput && state.language == InputLanguage.Chinese) {
            return Reduction(state, listOf(ImeCommand.CommitText(text)))
        }
        return when (state.language) {
            InputLanguage.English -> {
                val output = applyShift(text, state.shift)
                val nextShift = if (state.shift == ShiftState.Locked) ShiftState.Locked else ShiftState.Off
                Reduction(state.copy(shift = nextShift), listOf(ImeCommand.CommitText(output)))
            }

            InputLanguage.Chinese -> {
                if (isLiteralComposing(state)) return appendLiteralInput(state, text)
                if (shouldStartLiteralRun(state, text)) return startLiteralRun(state, text)
                val lower = text.lowercase()
                if (lower.any { it !in 'a'..'z' }) {
                    commitCompositionThen(state, text)
                } else {
                    val composing = state.composing + lower
                    // R03: never leave old candidates visible as if they belonged
                    // to the longer composition. The raw-pinyin fallback chip is
                    // still available as an explicit CommitRaw action.
                    Reduction(
                        state.copy(
                            composing = composing,
                            compositionVersion = state.compositionVersion + 1,
                            candidates = emptyList(),
                            candidatesFor = "",
                            candidateQueryError = null,
                            aiCandidates = emptyList(),
                            aiCandidatesFor = "",
                            aiCandidatesLoading = false,
                            aiCandidatesError = null,
                        ),
                        listOf(ImeCommand.SetComposingText(composing)),
                    )
                }
            }
        }
    }

    private fun isPinyinSeparator(text: String): Boolean =
        text == "'" || text == "’" || text == "‘"

    /** D07: a single ASCII Latin letter (the only way to start a literal run). */
    private fun isLatinLetter(text: String): Boolean =
        text.length == 1 && (text[0] in 'a'..'z' || text[0] in 'A'..'Z')

    /** D07: characters that continue a literal English run unchanged. */
    private fun isLiteralRunChar(text: String): Boolean =
        text.length == 1 &&
            (isLatinLetter(text) || text[0] in '0'..'9' || text[0] == '_' || text[0] == '-')

    /**
     * D07: in Chinese mode an active Shift plus the first Latin letter starts a
     * literal English run. Manual language switching remains the fallback for
     * editors where Shift is not available.
     */
    private fun shouldStartLiteralRun(state: KeyboardUiState, text: String): Boolean =
        state.language == InputLanguage.Chinese &&
            state.layer == KeyboardLayer.Letters &&
            state.editorInfo.allowsChineseInput &&
            state.composing.isEmpty() &&
            state.shift != ShiftState.Off &&
            isLatinLetter(text)

    private fun startLiteralRun(state: KeyboardUiState, text: String): Reduction {
        // The keyboard sends the lowercase key identity even when Shift is on,
        // so apply the normal Shift rule for the first letter (Android16 from
        // Shift+"a"). Later letters keep the typed case.
        val composing = applyShift(text, state.shift)
        val nextShift = if (state.shift == ShiftState.Locked) ShiftState.Locked else ShiftState.Off
        return Reduction(
            state.copy(
                composing = composing,
                compositionVersion = state.compositionVersion + 1,
                candidates = emptyList(),
                candidatesFor = LITERAL_COMPOSING_MARKER,
                candidateQueryError = null,
                shift = nextShift,
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
            ),
            listOf(ImeCommand.SetComposingText(composing)),
        )
    }

    private fun appendLiteralInput(state: KeyboardUiState, text: String): Reduction {
        if (isLiteralRunChar(text)) {
            // Caps Lock continues to uppercase letters; a one-shot Shift has
            // already been consumed when the run started.
            val appended = if (state.shift == ShiftState.Locked) text.uppercase() else text
            val composing = state.composing + appended
            return Reduction(
                state.copy(
                    composing = composing,
                    compositionVersion = state.compositionVersion + 1,
                    candidates = emptyList(),
                    candidatesFor = LITERAL_COMPOSING_MARKER,
                    candidateQueryError = null,
                    aiCandidates = emptyList(),
                    aiCandidatesFor = "",
                    aiCandidatesLoading = false,
                    aiCandidatesError = null,
                ),
                listOf(ImeCommand.SetComposingText(composing)),
            )
        }
        // Space is normally delivered as [KeyboardAction.Space]; this branch
        // keeps direct AppendInput(" ") callers on the same literal+space path.
        if (text == " ") return commitLiteralWithSuffix(state, " ")
        return commitLiteralThen(state, text)
    }

    /** D07: commits the literal run and [suffix] as one typed block. */
    private fun commitLiteralWithSuffix(state: KeyboardUiState, suffix: String): Reduction =
        Reduction(
            clearLiteralState(state),
            listOf(ImeCommand.CommitText(state.composing + suffix)),
        )

    /** D07: commits the literal run, then [text] (punctuation) as its own commit. */
    private fun commitLiteralThen(state: KeyboardUiState, text: String): Reduction =
        Reduction(
            clearLiteralState(state),
            buildList {
                add(ImeCommand.CommitText(state.composing))
                if (text.isNotEmpty()) add(ImeCommand.CommitText(text))
            },
        )

    /** D07: ends a literal run without changing unrelated editor/settings state. */
    private fun clearLiteralState(state: KeyboardUiState): KeyboardUiState = state.copy(
        composing = "",
        compositionVersion = state.compositionVersion + 1,
        candidates = emptyList(),
        candidatesFor = "",
        candidateQueryError = null,
        pendingConfirmation = null,
        pendingComposing = "",
        queuedActions = emptyList(),
        aiCandidates = emptyList(),
        aiCandidatesFor = "",
        aiCandidatesLoading = false,
        aiCandidatesError = null,
    )

    private fun isNumericInputAllowed(info: EditorInputInfo, text: String): Boolean {
        if (text.length != 1) return false
        val char = text[0]
        val caps = info.numericCapabilities
        return when {
            char in '0'..'9' -> caps.digits
            char == '.' -> caps.decimal
            char == '+' || char == '-' -> caps.signed || caps.phone || caps.dateTime
            caps.phone && char in charArrayOf('*', '#', ' ', '(', ')') -> true
            caps.dateTime && char in charArrayOf('/', ':', ' ') -> true
            else -> false
        }
    }

    private fun appendPinyinSeparator(state: KeyboardUiState): Reduction {
        // Leading separators are ignored; repeated separators collapse to one.
        if (state.composing.isEmpty() || state.composing.endsWith("'")) return Reduction(state)
        if (state.pendingConfirmation != null) return Reduction(state)
        val composing = state.composing + "'"
        return Reduction(
            state.copy(
                composing = composing,
                compositionVersion = state.compositionVersion + 1,
                candidates = emptyList(),
                candidatesFor = "",
                candidateQueryError = null,
            ),
            listOf(ImeCommand.SetComposingText(composing)),
        )
    }

    /**
     * Commit the current composition (fresh candidate first) and then [text].
     * This is used by punctuation and by ordinary symbol keys when composing is
     * still active; a pending query uses the same queue as R03.
     */
    private fun commitCompositionThen(state: KeyboardUiState, text: String): Reduction {
        if (state.pendingConfirmation != null) {
            return enqueueAction(state, KeyboardAction.AppendInput(text))
        }
        if (isLiteralComposing(state)) {
            // D07: punctuation always commits the literal run, never a pinyin query.
            return commitLiteralThen(state, text)
        }
        if (state.composing.isNotEmpty() && state.candidatesFor != state.composing) {
            return queueConfirmation(state, PendingConfirmation.Punctuation(text))
        }
        // Respect consumedInputLength for partial candidates: punctuation must
        // never swallow the unconsumed suffix (e.g. nihaox + 你好(5) + ， -> x).
        val candidate = if (state.composing.isNotEmpty()) state.candidates.firstOrNull() else null
        return commitCandidateThenText(state, candidate, text)
    }

    private fun applyShift(text: String, shift: ShiftState): String = when (shift) {
        ShiftState.Off -> text
        ShiftState.Shifted, ShiftState.Locked -> text.uppercase()
    }

    private fun composingUpdated(state: KeyboardUiState, action: KeyboardAction.ComposingUpdated): Reduction {
        // D07: a literal run never issues pinyin queries; any late result is stale.
        if (isLiteralComposing(state)) return Reduction(state)
        // Old query results must never cross composition sessions even when the
        // visible composing string happens to be identical (e.g. ni -> Space -> ni).
        if (action.version != 0L && action.version != state.compositionVersion) return Reduction(state)
        if (action.sessionId.isNotEmpty() &&
            state.compositionSessionId.isNotEmpty() &&
            action.sessionId != state.compositionSessionId
        ) {
            return Reduction(state)
        }
        if (action.composing != state.composing) return Reduction(state)
        // Stamp every candidate with the composition version/session it was
        // produced for, so a later tap can prove it still belongs to the visible
        // composition. An explicit non-negative candidate version/session is
        // authoritative and never overwritten: a result must not launder a stale
        // candidate into the current version. version == 0L is the legacy
        // "unversioned" escape hatch and keeps candidate defaults (-1).
        val stamped = action.candidates.take(PinyinEngine.MAX_CANDIDATES).map { candidate ->
            candidate.copy(
                compositionVersion = if (candidate.compositionVersion != Candidate.UNVERSIONED) {
                    candidate.compositionVersion
                } else if (action.version != 0L) {
                    action.version
                } else {
                    Candidate.UNVERSIONED
                },
                sessionId = candidate.sessionId ?: action.sessionId.ifEmpty { null },
            )
        }
        val updated = state.copy(
            candidates = stamped,
            candidatesFor = action.composing,
            candidateQueryError = null,
            compositionSessionId = if (state.compositionSessionId.isEmpty() && action.sessionId.isNotEmpty()) {
                action.sessionId
            } else {
                state.compositionSessionId
            },
        )
        val pending = state.pendingConfirmation ?: return Reduction(updated)
        return resolvePending(updated, pending)
    }

    /**
     * P0-A recovery: the engine query for [KeyboardAction.PinyinQueryFailed.composing]
     * failed. A stale failure (old version/session or a different visible
     * composition) is dropped exactly like a stale [KeyboardAction.ComposingUpdated].
     *
     * When the failure matches the composition a confirmation is waiting for,
     * the confirmation ends with the raw-text fallback (Space/Candidate use
     * [commitRaw] semantics; Punctuation commits the raw text plus its
     * punctuation via [commitCandidateThenText]) and every key queued behind it
     * is replayed in order, so a pressed key is never lost. Otherwise the failure is
     * recorded as [KeyboardUiState.candidateQueryError] and the composition is
     * marked as queried-with-no-result, so a later Space cannot wait forever for
     * a result that will never arrive.
     */
    private fun pinyinQueryFailed(
        state: KeyboardUiState,
        action: KeyboardAction.PinyinQueryFailed,
    ): Reduction {
        // D07: literal runs never wait for or accept a pinyin query result.
        if (isLiteralComposing(state)) return Reduction(state)
        if (action.version != 0L && action.version != state.compositionVersion) return Reduction(state)
        if (action.sessionId.isNotEmpty() &&
            state.compositionSessionId.isNotEmpty() &&
            action.sessionId != state.compositionSessionId
        ) {
            return Reduction(state)
        }
        if (action.composing != state.composing) return Reduction(state)
        val message = action.message.ifBlank { CANDIDATE_QUERY_FAILED_MESSAGE }
        val pending = state.pendingConfirmation
        if (pending == null) {
            // Treat the failure as a completed empty result for this composition:
            // candidates are only kept when they already belong to it.
            val keepCandidates = state.candidatesFor == state.composing
            return Reduction(
                state.copy(
                    candidates = if (keepCandidates) state.candidates else emptyList(),
                    candidatesFor = state.composing,
                    candidateQueryError = message,
                ),
            )
        }
        if (state.pendingComposing != action.composing) {
            return Reduction(state.copy(candidateQueryError = message))
        }
        // End the wait with the raw-text fallback, then replay the keys the user
        // pressed while the query was outstanding (same ordering rules as a
        // successful ComposingUpdated).
        val queued = state.queuedActions
        val cleared = state.copy(
            pendingConfirmation = null,
            pendingComposing = "",
            queuedActions = emptyList(),
        )
        val base = when (pending) {
            // The user pressed punctuation while waiting: keep the normal
            // "raw text + punctuation" fallback instead of swallowing the key.
            is PendingConfirmation.Punctuation ->
                commitCandidateThenText(cleared, candidate = null, text = pending.text)

            PendingConfirmation.Space, is PendingConfirmation.Candidate -> commitRaw(cleared)
        }
        val failedState = base.state.copy(candidateQueryError = CANDIDATE_QUERY_FALLBACK_MESSAGE)
        val baseLearned = base.learnedSelections.ifEmpty { listOfNotNull(base.learnedSelection) }
        return replayQueued(failedState, queued, base.commands, baseLearned)
    }

    /**
     * P0-B: dictionary lifecycle updates are orthogonal to composition/query
     * state. Only the dictionary fields change: a late load result must never
     * resolve a pending confirmation, drop candidates or touch the composing
     * text, so the raw-text fallback keeps working while the dictionary fails.
     */
    private fun dictionaryStateChanged(
        state: KeyboardUiState,
        action: KeyboardAction.DictionaryStateChanged,
    ): Reduction {
        val error = when (action.state) {
            DictionaryLoadState.Failed ->
                action.error?.takeIf { it.isNotBlank() } ?: DICTIONARY_LOAD_FAILED_MESSAGE

            DictionaryLoadState.Loading, DictionaryLoadState.Ready -> null
        }
        return Reduction(state.copy(dictionaryState = action.state, dictionaryError = error))
    }

    /**
     * P0-B: an explicit retry re-enters Loading immediately (before the reload
     * coroutine starts) and clears the previous failure hint. Existing
     * composition/candidate/pending state is deliberately untouched.
     */
    private fun retryDictionaryLoad(state: KeyboardUiState): Reduction = Reduction(
        state.copy(
            dictionaryState = DictionaryLoadState.Loading,
            dictionaryError = null,
        ),
    )

    /**
     * True when [this] candidate provably belongs to the state's current
     * composition. Unversioned legacy candidates (-1 / null session) remain
     * accepted so existing behavior is unchanged; a versioned candidate whose
     * version or session does not match is stale and must never write text.
     */
    private fun Candidate.matchesComposition(state: KeyboardUiState): Boolean {
        if (compositionVersion != Candidate.UNVERSIONED &&
            compositionVersion != state.compositionVersion
        ) {
            return false
        }
        if (sessionId != null &&
            state.compositionSessionId.isNotEmpty() &&
            sessionId != state.compositionSessionId
        ) {
            return false
        }
        return true
    }

    private fun candidateTapped(state: KeyboardUiState, index: Int): Reduction {
        if (state.composing.isEmpty()) return Reduction(state)
        if (state.pendingConfirmation != null) return Reduction(state)
        if (state.candidatesFor != state.composing) {
            return queueConfirmation(state, PendingConfirmation.Candidate(index))
        }
        val candidate = state.candidates.getOrNull(index) ?: return Reduction(state)
        return commitCandidate(state, candidate)
    }

    /**
     * A confirmation action arrived while the latest pinyin query is still
     * outstanding. Store it with the composing snapshot and hide old candidates,
     * but emit no text command.
     */
    private fun queueConfirmation(
        state: KeyboardUiState,
        pending: PendingConfirmation,
    ): Reduction {
        if (state.pendingConfirmation != null) return Reduction(state)
        return Reduction(
            state.copy(
                candidates = emptyList(),
                candidatesFor = "",
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
                pendingConfirmation = pending,
                pendingComposing = state.composing,
            ),
        )
    }

    private fun enqueueAction(state: KeyboardUiState, action: KeyboardAction): Reduction =
        Reduction(state.copy(queuedActions = state.queuedActions + action))

    private fun resolvePending(
        state: KeyboardUiState,
        pending: PendingConfirmation,
    ): Reduction {
        val fresh = state.candidatesFor == state.composing
        val queued = state.queuedActions
        val clearPending = state.copy(
            pendingConfirmation = null,
            pendingComposing = "",
            queuedActions = emptyList(),
        )
        val base = when (pending) {
            PendingConfirmation.Space -> when {
                fresh && state.candidates.isNotEmpty() ->
                    commitCandidate(clearPending, state.candidates.first())

                fresh -> commitRaw(clearPending)
                else -> Reduction(clearPending)
            }

            is PendingConfirmation.Candidate -> {
                val candidate = if (!fresh) null else state.candidates.getOrNull(pending.index)
                if (candidate == null) {
                    // Never silently fall back to another candidate when the
                    // candidate list changed while the query was outstanding.
                    Reduction(clearPending)
                } else {
                    commitCandidate(clearPending, candidate)
                }
            }

            is PendingConfirmation.Punctuation -> {
                val candidate = if (fresh) state.candidates.firstOrNull() else null
                commitCandidateThenText(clearPending, candidate, pending.text)
            }
        }
        val baseLearned = base.learnedSelections.ifEmpty { listOfNotNull(base.learnedSelection) }
        return replayQueued(base.state, queued, base.commands, baseLearned)
    }

    /**
     * Applies keys received while a confirmation was waiting, after the front
     * confirmation has been committed. Ordering is preserved and no key is
     * silently discarded.
     */
    private fun replayQueued(
        state: KeyboardUiState,
        queued: List<KeyboardAction>,
        initialCommands: List<ImeCommand>,
        initialLearned: List<LearnedSelection>,
    ): Reduction {
        if (queued.isEmpty()) {
            return Reduction(
                state = state,
                commands = initialCommands,
                learnedSelection = initialLearned.firstOrNull(),
                learnedSelections = initialLearned,
            )
        }
        var current = state.copy(queuedActions = emptyList())
        val commands = initialCommands.toMutableList()
        val learned = initialLearned.toMutableList()
        queued.forEach { action ->
            val result = reduce(current, action)
            current = result.state
            commands += result.commands
            if (result.learnedSelections.isNotEmpty()) {
                learned += result.learnedSelections
            } else {
                result.learnedSelection?.let(learned::add)
            }
        }
        // Keep any pending confirmation and its remaining queued suffix created
        // during replay. Only the *consumed* queue was empty; a new asynchronous
        // confirm owns everything appended after it.
        return Reduction(
            state = current,
            commands = commands,
            learnedSelection = learned.firstOrNull(),
            learnedSelections = learned,
        )
    }

    private fun commitCandidateThenText(
        state: KeyboardUiState,
        candidate: Candidate?,
        text: String,
    ): Reduction {
        // A stale candidate must not leak into a commit, not even through the
        // punctuation path: drop the whole action with no ImeCommand.
        if (candidate != null && !candidate.matchesComposition(state)) return Reduction(state)
        val consumed = if (candidate != null && candidate.consumedInputLength in 1..state.composing.length) {
            candidate.consumedInputLength
        } else {
            state.composing.length
        }
        val remaining = state.composing.drop(consumed)
        val word = candidate?.word ?: state.composing
        val commands = buildList {
            if (word.isNotEmpty()) add(ImeCommand.CommitText(word))
            add(ImeCommand.CommitText(text))
            if (remaining.isNotEmpty()) add(ImeCommand.SetComposingText(remaining))
        }
        val learnedEvent = candidate?.let {
            LearnedSelection(
                word = it.word,
                pinyin = it.pinyin.ifBlank { state.composing.take(consumed) },
                source = "punctuation",
                consumedInputLength = consumed,
            )
        }
        return Reduction(
            state.copy(
                composing = remaining,
                compositionVersion = state.compositionVersion + 1,
                candidates = emptyList(),
                candidatesFor = "",
                candidateQueryError = null,
                pendingConfirmation = null,
                pendingComposing = "",
                queuedActions = emptyList(),
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
            ),
            commands,
            learnedSelection = learnedEvent,
            learnedSelections = listOfNotNull(learnedEvent),
        )
    }

    private fun commitCandidate(
        state: KeyboardUiState,
        candidate: Candidate,
        source: String = "candidate",
    ): Reduction {
        // Stale candidates (older composition version or input session) are
        // dropped without any ImeCommand: no text, no composing update.
        if (!candidate.matchesComposition(state)) return Reduction(state)
        val consumed = if (candidate.consumedInputLength in 1..state.composing.length) {
            candidate.consumedInputLength
        } else {
            state.composing.length
        }
        val remaining = state.composing.drop(consumed)
        val commands = buildList {
            add(ImeCommand.CommitText(candidate.word))
            if (remaining.isNotEmpty()) add(ImeCommand.SetComposingText(remaining))
        }
        val learnedPinyin = candidate.pinyin.ifBlank { state.composing.take(consumed) }
        val learnedEvent = LearnedSelection(
            word = candidate.word,
            pinyin = learnedPinyin,
            source = source,
            consumedInputLength = consumed,
        )
        return Reduction(
            state.copy(
                composing = remaining,
                compositionVersion = state.compositionVersion + 1,
                candidates = emptyList(),
                candidatesFor = "",
                candidateQueryError = null,
                pendingConfirmation = null,
                pendingComposing = "",
                queuedActions = emptyList(),
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
                shift = if (state.shift == ShiftState.Locked) ShiftState.Locked else ShiftState.Off,
            ),
            commands,
            learnedSelection = learnedEvent,
            learnedSelections = listOf(learnedEvent),
        )
    }

    private fun commitCandidate(
        state: KeyboardUiState,
        word: String,
        pinyin: String,
        source: String,
    ): Reduction = commitCandidate(
        state = state,
        candidate = Candidate(
            word = word,
            pinyin = pinyin,
            consumedInputLength = state.composing.length,
            compositionVersion = state.compositionVersion,
        ),
        source = source,
    )

    private fun commitRaw(state: KeyboardUiState): Reduction {
        if (state.composing.isEmpty()) return Reduction(state)
        return Reduction(
            state.copy(
                composing = "",
                compositionVersion = state.compositionVersion + 1,
                candidates = emptyList(),
                candidatesFor = "",
                candidateQueryError = null,
                pendingConfirmation = null,
                pendingComposing = "",
                queuedActions = emptyList(),
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
            ),
            listOf(ImeCommand.CommitText(state.composing)),
        )
    }

    private fun space(state: KeyboardUiState): Reduction = when {
        state.editorInfo.numericMode != NumericInputMode.None -> Reduction(state)
        state.pendingConfirmation != null -> enqueueAction(state, KeyboardAction.Space)
        isLiteralComposing(state) -> commitLiteralWithSuffix(state, " ")
        state.composing.isEmpty() -> Reduction(state, listOf(ImeCommand.CommitText(" ")))
        state.candidatesFor != state.composing -> queueConfirmation(state, PendingConfirmation.Space)
        state.candidates.isNotEmpty() -> commitCandidate(state, state.candidates.first())
        else -> commitRaw(state)
    }

    private fun backspace(state: KeyboardUiState): Reduction {
        if (state.pendingConfirmation != null) {
            return enqueueAction(state, KeyboardAction.Backspace)
        }
        if (isLiteralComposing(state)) {
            // D07: backspace shrinks the literal run exactly like pinyin
            // composing, but the marker and typed case must survive.
            val remaining = state.composing.dropLast(1)
            return if (remaining.isEmpty()) {
                Reduction(clearLiteralState(state), listOf(ImeCommand.FinishComposing, ImeCommand.DeleteBackward))
            } else {
                Reduction(
                    state.copy(
                        composing = remaining,
                        compositionVersion = state.compositionVersion + 1,
                        candidates = emptyList(),
                        candidatesFor = LITERAL_COMPOSING_MARKER,
                        candidateQueryError = null,
                    ),
                    listOf(ImeCommand.SetComposingText(remaining)),
                )
            }
        }
        if (state.composing.isEmpty()) {
            return Reduction(state, listOf(ImeCommand.DeleteBackward))
        }
        val remaining = state.composing.dropLast(1)
        return if (remaining.isEmpty()) {
            // FinishComposing alone does not remove the last pinyin letter from
            // the editor.  Finish the composing region, then delete the one
            // character that was inserted by SetComposingText.
            Reduction(
                state.copy(
                    composing = "",
                    compositionVersion = state.compositionVersion + 1,
                    candidates = emptyList(),
                    candidatesFor = "",
                    candidateQueryError = null,
                    pendingConfirmation = null,
                    pendingComposing = "",
                    queuedActions = emptyList(),
                    aiCandidates = emptyList(),
                    aiCandidatesFor = "",
                    aiCandidatesLoading = false,
                    aiCandidatesError = null,
                ),
                listOf(ImeCommand.FinishComposing, ImeCommand.DeleteBackward),
            )
        } else {
            Reduction(
                state.copy(
                    composing = remaining,
                    compositionVersion = state.compositionVersion + 1,
                    candidates = emptyList(),
                    candidatesFor = "",
                    candidateQueryError = null,
                    pendingConfirmation = null,
                    pendingComposing = "",
                    queuedActions = emptyList(),
                    aiCandidates = emptyList(),
                    aiCandidatesFor = "",
                    aiCandidatesLoading = false,
                    aiCandidatesError = null,
                ),
                listOf(ImeCommand.SetComposingText(remaining)),
            )
        }
    }

    private fun enter(state: KeyboardUiState): Reduction {
        if (state.pendingConfirmation != null) {
            return enqueueAction(state, KeyboardAction.Enter)
        }
        if (isLiteralComposing(state)) {
            // D07: Enter commits only the literal text; it never sends an editor action.
            return Reduction(clearLiteralState(state), listOf(ImeCommand.CommitText(state.composing)))
        }
        if (state.composing.isNotEmpty()) {
            return Reduction(
                state.copy(
                    composing = "",
                    compositionVersion = state.compositionVersion + 1,
                    candidates = emptyList(),
                    candidatesFor = "",
                    candidateQueryError = null,
                    pendingConfirmation = null,
                    pendingComposing = "",
                    queuedActions = emptyList(),
                    aiCandidates = emptyList(),
                    aiCandidatesFor = "",
                    aiCandidatesLoading = false,
                    aiCandidatesError = null,
                ),
                listOf(ImeCommand.CommitText(state.composing)),
            )
        }
        return Reduction(state, listOf(ImeCommand.SendEnter))
    }

    /**
     * Editor switched (new field / restart). Everything tied to the previous
     * input field is cleared so numbers / English / password layouts never leak
     * composing or candidate state into the next editor.
     */
    private fun editorInfoChanged(
        state: KeyboardUiState,
        action: KeyboardAction.EditorInfoChanged,
    ): Reduction {
        val info = action.info
        return Reduction(
            state.copy(
                editorInfo = info,
                language = info.preferredLanguage,
                layer = info.preferredLayer,
                shift = ShiftState.Off,
                composing = "",
                compositionVersion = state.compositionVersion + 1,
                compositionSessionId = action.sessionId.ifEmpty {
                    "editor-" + (state.compositionVersion + 1)
                },
                candidates = emptyList(),
                candidatesFor = "",
                candidateQueryError = null,
                pendingConfirmation = null,
                pendingComposing = "",
                queuedActions = emptyList(),
                ai = AiPanelUiState(),
                aiEnabled = action.aiSettingEnabled && info.aiContextAllowed,
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
                lastShiftTapAt = 0L,
                hasSelection = false,
                editingToolsVisible = false,
            ),
        )
    }

    /**
     * Cursor/selection sync. A composing region is only valid while the cursor
     * stays inside the candidate region reported by the editor; otherwise the
     * composing text is stale and must be discarded (never committed).
     */
    private fun selectionChanged(
        state: KeyboardUiState,
        action: KeyboardAction.SelectionChanged,
    ): Reduction {
        // Selection tracking is independent of composing: the editing page
        // needs the real range even when no pinyin session is active.
        val hasSelection = action.selectionStart != action.selectionEnd
        if (state.composing.isEmpty()) {
            // R07: even without composing, any cursor/selection move invalidates
            // an AI-lexicon request or its displayed candidates.
            val hasAiCandidates = state.aiCandidates.isNotEmpty() ||
                state.aiCandidatesLoading ||
                state.aiCandidatesError != null ||
                state.aiCandidatesFor.isNotEmpty()
            if (!hasAiCandidates) return Reduction(state.copy(hasSelection = hasSelection))
            return Reduction(
                state.copy(
                    hasSelection = hasSelection,
                    aiCandidates = emptyList(),
                    aiCandidatesFor = "",
                    aiCandidatesLoading = false,
                    aiCandidatesError = null,
                ),
            )
        }
        val hasCandidatesRegion = action.candidatesStart >= 0 &&
            action.candidatesEnd >= action.candidatesStart
        // The normal IME-own callback after setComposingText places a collapsed
        // caret exactly at the end of the composing region; that must NOT end the
        // composition on every keystroke, but it must still clear a stale
        // hasSelection flag because the callback describes a collapsed caret.
        val normalImeCallback = hasCandidatesRegion &&
            action.selectionStart == action.selectionEnd &&
            action.selectionEnd == action.candidatesEnd
        if (normalImeCallback) return Reduction(state.copy(hasSelection = hasSelection))

        // Any caret inside the composition (before its end), any real selection,
        // or a moved/cleared candidate region is handled safely: finish the
        // composition without deleting text, clear candidates and queued confirms.
        return endComposition(state.copy(hasSelection = hasSelection))
    }

    private fun endComposition(state: KeyboardUiState): Reduction = Reduction(
        state.copy(
            composing = "",
            compositionVersion = state.compositionVersion + 1,
            candidates = emptyList(),
            candidatesFor = "",
            candidateQueryError = null,
            pendingConfirmation = null,
            pendingComposing = "",
            queuedActions = emptyList(),
            ai = AiPanelUiState(),
            aiCandidates = emptyList(),
            aiCandidatesFor = "",
            aiCandidatesLoading = false,
            aiCandidatesError = null,
            shift = if (state.shift == ShiftState.Locked) ShiftState.Locked else ShiftState.Off,
        ),
        listOf(ImeCommand.FinishComposing),
    )

    private fun requestAiCandidates(state: KeyboardUiState): Reduction {
        if (!state.aiEnabled || !state.editorInfo.aiContextAllowed) return Reduction(state)
        // D07: a literal English run is not pinyin; never query the AI lexicon for it.
        if (isLiteralComposing(state)) return Reduction(state)
        // A second request while one is loading is a cancel, not a restart. The
        // controller turns RequestAiCandidates into cancelAi() in that case; this
        // guard keeps a direct reducer caller from arming a duplicate request.
        if (state.aiCandidatesLoading) return Reduction(state)
        return Reduction(
            state.copy(
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = true,
                aiCandidatesError = null,
            ),
        )
    }

    private fun aiCandidatesUpdated(state: KeyboardUiState, candidates: List<String>): Reduction {
        // A late result for a closed AI setting or a forbidden password context
        // must never reappear in the candidate strip.
        if (!state.aiEnabled || !state.editorInfo.aiContextAllowed) return Reduction(state)
        val cleaned = candidates.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 24 }
            .distinct()
            .take(6)
            .toList()
        return Reduction(
            state.copy(
                aiCandidates = cleaned,
                aiCandidatesFor = state.composing,
                aiCandidatesLoading = false,
                aiCandidatesError = if (cleaned.isEmpty()) "AI 词库没有返回候选" else null,
            ),
        )
    }

    private fun aiCandidatesFailed(state: KeyboardUiState, message: String): Reduction {
        if (!state.aiEnabled || !state.editorInfo.aiContextAllowed) return Reduction(state)
        return Reduction(
            state.copy(
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = message,
            ),
        )
    }

    private fun aiCandidateTapped(state: KeyboardUiState, word: String): Reduction {
        if (word.isBlank()) return Reduction(state)
        if (!state.aiEnabled || !state.editorInfo.aiContextAllowed || state.aiCandidatesLoading) {
            return Reduction(state)
        }
        if (state.aiCandidates.none { it == word }) return Reduction(state)
        // A candidate requested for an older composing session must not be
        // committed after the composition has changed. Raw-context requests use
        // aiCandidatesFor = "" and are only valid while composing is empty.
        if (state.aiCandidatesFor != state.composing &&
            (state.composing.isNotEmpty() || state.aiCandidatesFor.isNotEmpty())
        ) {
            return Reduction(state)
        }
        return commitCandidate(
            state = state,
            word = word,
            pinyin = state.aiCandidatesFor.ifBlank { state.composing },
            source = "ai",
        )
    }

    private fun aiPanelOpened(state: KeyboardUiState, rawSource: String): Reduction {
        if (!state.aiEnabled || !state.editorInfo.aiContextAllowed) return Reduction(state)
        val source = rawSource.trim().take(MAX_AI_SOURCE_CHARS)
        return if (!state.aiReady) {
            Reduction(
                state.copy(
                    ai = AiPanelUiState(
                        visible = true,
                        source = source,
                        status = AiStatus.Unavailable,
                        error = "本地模型未就绪，请先在 Bitap 中导入模型",
                    ),
                ),
            )
        } else {
            Reduction(
                state.copy(
                    ai = AiPanelUiState(
                        visible = true,
                        source = source,
                        status = AiStatus.Idle,
                        error = if (source.isEmpty()) "没有可改写的文本" else null,
                    ),
                ),
            )
        }
    }

    private fun aiToneChosen(state: KeyboardUiState, tone: RewriteTone): Reduction {
        val panel = state.ai
        if (!panel.visible) return Reduction(state)
        if (!state.aiEnabled || !state.aiReady || !state.editorInfo.aiContextAllowed) {
            return Reduction(
                state.copy(
                    ai = panel.copy(
                        status = AiStatus.Unavailable,
                        error = "本地模型未就绪，请先在 Bitap 中导入模型",
                    ),
                ),
            )
        }
        if (panel.source.isBlank()) {
            return Reduction(state.copy(ai = panel.copy(status = AiStatus.Failed, error = "没有可改写的文本")))
        }
        return Reduction(
            state.copy(
                ai = panel.copy(
                    tone = tone,
                    status = AiStatus.Thinking,
                    result = "",
                    error = null,
                ),
            ),
        )
    }

    private fun aiCandidatesCancelled(state: KeyboardUiState): Reduction = Reduction(
        state.copy(
            aiCandidates = emptyList(),
            aiCandidatesFor = "",
            aiCandidatesLoading = false,
            aiCandidatesError = null,
        ),
    )

    private fun aiCancel(state: KeyboardUiState): Reduction {
        val panel = state.ai
        val hasCandidateRequest = state.aiCandidatesLoading ||
            state.aiCandidates.isNotEmpty() ||
            state.aiCandidatesFor.isNotEmpty() ||
            state.aiCandidatesError != null
        // AiCancel is the single user-visible "stop AI" action: it must stop both
        // the rewrite panel and an in-flight AI candidate request. If neither is
        // active this is a no-op so repeated taps cannot reset unrelated state.
        if (!panel.visible && !hasCandidateRequest) return Reduction(state)
        return Reduction(
            state.copy(
                ai = if (panel.visible) {
                    panel.copy(
                        tone = null,
                        status = if (state.aiReady) AiStatus.Idle else AiStatus.Unavailable,
                        result = "",
                        error = null,
                    )
                } else {
                    panel
                },
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
            ),
        )
    }

    private fun aiDelta(state: KeyboardUiState, delta: String): Reduction {
        val panel = state.ai
        if (!panel.visible || !panel.isRunning || delta.isEmpty()) return Reduction(state)
        return Reduction(state.copy(ai = panel.copy(status = AiStatus.Streaming, result = panel.result + delta)))
    }

    private fun aiCompleted(state: KeyboardUiState, text: String): Reduction {
        val panel = state.ai
        if (!panel.visible) return Reduction(state)
        val cleaned = text.trim().take(MAX_AI_RESULT_CHARS)
        return if (cleaned.isEmpty()) {
            Reduction(state.copy(ai = panel.copy(status = AiStatus.Failed, error = "这次没想好")))
        } else {
            Reduction(state.copy(ai = panel.copy(status = AiStatus.Completed, result = cleaned, error = null)))
        }
    }

    private fun aiFailed(state: KeyboardUiState, message: String): Reduction {
        val panel = state.ai
        if (!panel.visible) return Reduction(state)
        return Reduction(
            state.copy(
                ai = panel.copy(
                    status = AiStatus.Failed,
                    error = message.ifBlank { "这次没想好" },
                ),
            ),
        )
    }

    private fun aiInsert(state: KeyboardUiState): Reduction {
        val panel = state.ai
        if (!panel.visible || !panel.canInsert) return Reduction(state)
        return Reduction(state.copy(ai = AiPanelUiState()), listOf(ImeCommand.CommitText(panel.result)))
    }

    private fun aiDismiss(state: KeyboardUiState): Reduction {
        if (!state.ai.visible) return Reduction(state)
        return Reduction(state.copy(ai = AiPanelUiState()))
    }

    private fun aiAvailabilityChanged(
        state: KeyboardUiState,
        action: KeyboardAction.AiAvailabilityChanged,
    ): Reduction {
        val ready = action.enabled && action.ready
        val panel = when {
            !action.enabled -> AiPanelUiState()
            !ready && state.ai.visible -> state.ai.copy(
                status = AiStatus.Unavailable,
                error = "本地模型未就绪，请先在 Bitap 中导入模型",
            )
            ready && state.ai.visible && state.ai.status == AiStatus.Unavailable -> state.ai.copy(
                status = AiStatus.Idle,
                error = null,
            )
            else -> state.ai
        }
        val clearAiCandidates = !action.enabled || !ready
        return Reduction(
            state.copy(
                aiEnabled = action.enabled,
                aiReady = ready,
                ai = panel,
                aiCandidates = if (clearAiCandidates) emptyList() else state.aiCandidates,
                aiCandidatesFor = if (clearAiCandidates) "" else state.aiCandidatesFor,
                aiCandidatesLoading = if (clearAiCandidates) false else state.aiCandidatesLoading,
                aiCandidatesError = if (clearAiCandidates) null else state.aiCandidatesError,
            ),
        )
    }
}
