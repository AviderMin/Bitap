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
    private const val DOUBLE_TAP_WINDOW_MS = 350L

    fun reduce(state: KeyboardUiState, action: KeyboardAction): Reduction = when (action) {
        KeyboardAction.ToggleLanguage -> toggleLanguage(state)
        is KeyboardAction.ShiftTapped -> shiftTapped(state, action.now)
        is KeyboardAction.ShowLayer -> showLayer(state, action.layer)
        is KeyboardAction.AppendInput -> appendInput(state, action.text)
        is KeyboardAction.ComposingUpdated -> composingUpdated(state, action)
        is KeyboardAction.CandidateTapped -> candidateTapped(state, action.index)
        KeyboardAction.CommitRaw -> commitRaw(state)
        KeyboardAction.Space -> space(state)
        KeyboardAction.Backspace -> backspace(state)
        KeyboardAction.Enter -> enter(state)
        is KeyboardAction.EditorInfoChanged -> editorInfoChanged(state, action)
        is KeyboardAction.SelectionChanged -> selectionChanged(state, action)
        KeyboardAction.RequestAiCandidates -> requestAiCandidates(state)
        is KeyboardAction.AiCandidatesUpdated -> aiCandidatesUpdated(state, action.candidates)
        is KeyboardAction.AiCandidatesFailed -> aiCandidatesFailed(state, action.message)
        is KeyboardAction.AiCandidateTapped -> aiCandidateTapped(state, action.word)
        is KeyboardAction.AiPanelOpened -> aiPanelOpened(state, action.source)
        is KeyboardAction.AiToneChosen -> aiToneChosen(state, action.tone)
        KeyboardAction.AiCancel -> aiCancel(state)
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
        // Email/URL/number/phone/password fields are English-only by policy.
        if (!state.editorInfo.allowsChineseInput) return Reduction(state)
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
        if (layer == state.layer) return Reduction(state)
        // R05: switching to the symbols/numbers layer must not commit an active
        // pinyin composition. The separator key on those layers can then append
        // to composing; ordinary symbols commit the composition when pressed.
        return Reduction(
            state.copy(
                layer = layer,
                shift = ShiftState.Off,
                candidates = emptyList(),
                candidatesFor = "",
                lastShiftTapAt = 0L,
            ),
        )
    }

    private fun appendInput(state: KeyboardUiState, text: String): Reduction {
        if (text.isEmpty()) return Reduction(state)
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
        if (!state.editorInfo.allowsChineseInput && state.language == InputLanguage.Chinese) {
            return Reduction(state, listOf(ImeCommand.CommitText(text)))
        }
        return when (state.language) {
            InputLanguage.English -> {
                val output = applyShift(text, state.shift)
                val nextShift = if (state.shift == ShiftState.Locked) ShiftState.Locked else ShiftState.Off
                Reduction(state.copy(shift = nextShift), listOf(ImeCommand.CommitText(output)))
            }

            InputLanguage.Chinese -> {
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
                            candidates = emptyList(),
                            candidatesFor = "",
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

    private fun appendPinyinSeparator(state: KeyboardUiState): Reduction {
        // Leading separators are ignored; repeated separators collapse to one.
        if (state.composing.isEmpty() || state.composing.endsWith("'")) return Reduction(state)
        if (state.pendingConfirmation != null) return Reduction(state)
        val composing = state.composing + "'"
        return Reduction(
            state.copy(
                composing = composing,
                candidates = emptyList(),
                candidatesFor = "",
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
        if (state.composing.isEmpty()) {
            return Reduction(
                state.copy(
                    aiCandidates = emptyList(),
                    aiCandidatesFor = "",
                    aiCandidatesLoading = false,
                    aiCandidatesError = null,
                    pendingConfirmation = null,
                ),
                listOf(ImeCommand.CommitText(text)),
            )
        }
        if (state.pendingConfirmation != null) return Reduction(state)
        if (state.candidatesFor != state.composing) {
            return queueConfirmation(state, PendingConfirmation.Punctuation(text))
        }
        val currentCandidate = state.candidates.firstOrNull()
        val commands = buildList {
            add(ImeCommand.CommitText(currentCandidate?.word ?: state.composing))
            add(ImeCommand.CommitText(text))
        }
        return Reduction(
            state.copy(
                composing = "",
                candidates = emptyList(),
                candidatesFor = "",
                pendingConfirmation = null,
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
            ),
            commands,
        )
    }

    private fun applyShift(text: String, shift: ShiftState): String = when (shift) {
        ShiftState.Off -> text
        ShiftState.Shifted, ShiftState.Locked -> text.uppercase()
    }

    private fun composingUpdated(state: KeyboardUiState, action: KeyboardAction.ComposingUpdated): Reduction {
        if (action.composing != state.composing) return Reduction(state)
        val updated = state.copy(
            candidates = action.candidates.take(PinyinEngine.MAX_CANDIDATES),
            candidatesFor = action.composing,
        )
        val pending = state.pendingConfirmation ?: return Reduction(updated)
        return resolvePending(updated, pending)
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
     * outstanding. Store it and hide old candidates, but emit no text command.
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
            ),
        )
    }

    private fun resolvePending(
        state: KeyboardUiState,
        pending: PendingConfirmation,
    ): Reduction {
        val fresh = state.candidatesFor == state.composing
        return when (pending) {
            PendingConfirmation.Space -> when {
                fresh && state.candidates.isNotEmpty() ->
                    commitCandidate(state, state.candidates.first())

                fresh -> commitRaw(state)
                else -> Reduction(state.copy(pendingConfirmation = null))
            }

            is PendingConfirmation.Candidate -> {
                if (!fresh || state.candidates.isEmpty()) {
                    // No fresh candidate exists; never silently commit raw pinyin
                    // for a candidate tap. Leave the composition for the user.
                    Reduction(state.copy(pendingConfirmation = null))
                } else {
                    val candidate = state.candidates.getOrNull(pending.index)
                        ?: state.candidates.first()
                    commitCandidate(state, candidate)
                }
            }

            is PendingConfirmation.Punctuation -> {
                val commands = buildList {
                    val text = if (fresh && state.candidates.isNotEmpty()) {
                        state.candidates.first().word
                    } else {
                        state.composing
                    }
                    if (text.isNotEmpty()) add(ImeCommand.CommitText(text))
                    add(ImeCommand.CommitText(pending.text))
                }
                Reduction(
                    state.copy(
                        composing = "",
                        candidates = emptyList(),
                        candidatesFor = "",
                        pendingConfirmation = null,
                        aiCandidates = emptyList(),
                        aiCandidatesLoading = false,
                        aiCandidatesError = null,
                    ),
                    commands,
                )
            }
        }
    }

    private fun commitCandidate(state: KeyboardUiState, candidate: Candidate): Reduction {
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
        return Reduction(
            state.copy(
                composing = remaining,
                candidates = emptyList(),
                candidatesFor = "",
                pendingConfirmation = null,
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
                shift = if (state.shift == ShiftState.Locked) ShiftState.Locked else ShiftState.Off,
            ),
            commands,
        )
    }

    private fun commitCandidate(state: KeyboardUiState, word: String): Reduction =
        commitCandidate(state, Candidate(word = word, consumedInputLength = state.composing.length))

    private fun commitRaw(state: KeyboardUiState): Reduction {
        if (state.composing.isEmpty()) return Reduction(state)
        return Reduction(
            state.copy(
                composing = "",
                candidates = emptyList(),
                candidatesFor = "",
                pendingConfirmation = null,
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
            ),
            listOf(ImeCommand.CommitText(state.composing)),
        )
    }

    private fun space(state: KeyboardUiState): Reduction = when {
        state.composing.isEmpty() -> Reduction(state, listOf(ImeCommand.CommitText(" ")))
        state.pendingConfirmation != null -> Reduction(state)
        state.candidatesFor != state.composing -> queueConfirmation(state, PendingConfirmation.Space)
        state.candidates.isNotEmpty() -> commitCandidate(state, state.candidates.first())
        else -> commitRaw(state)
    }

    private fun backspace(state: KeyboardUiState): Reduction {
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
                    candidates = emptyList(),
                    candidatesFor = "",
                    pendingConfirmation = null,
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
                    candidates = emptyList(),
                    candidatesFor = "",
                    pendingConfirmation = null,
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
        if (state.composing.isNotEmpty()) {
            return Reduction(
                state.copy(
                    composing = "",
                    candidates = emptyList(),
                    candidatesFor = "",
                    pendingConfirmation = null,
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
                candidates = emptyList(),
                candidatesFor = "",
                pendingConfirmation = null,
                ai = AiPanelUiState(),
                aiEnabled = action.aiSettingEnabled && info.aiContextAllowed,
                aiCandidates = emptyList(),
                aiCandidatesFor = "",
                aiCandidatesLoading = false,
                aiCandidatesError = null,
                lastShiftTapAt = 0L,
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
        if (state.composing.isEmpty()) {
            // R07: even without composing, any cursor/selection move invalidates
            // an AI-lexicon request or its displayed candidates.
            val hasAiCandidates = state.aiCandidates.isNotEmpty() ||
                state.aiCandidatesLoading ||
                state.aiCandidatesError != null ||
                state.aiCandidatesFor.isNotEmpty()
            if (!hasAiCandidates) return Reduction(state)
            return Reduction(
                state.copy(
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
        // composition on every keystroke.
        val normalImeCallback = hasCandidatesRegion &&
            action.selectionStart == action.selectionEnd &&
            action.selectionEnd == action.candidatesEnd
        if (normalImeCallback) return Reduction(state)

        // Any caret inside the composition (before its end), any real selection,
        // or a moved/cleared candidate region is handled safely: finish the
        // composition without deleting text, clear candidates and queued confirms.
        return endComposition(state)
    }

    private fun endComposition(state: KeyboardUiState): Reduction = Reduction(
        state.copy(
            composing = "",
            candidates = emptyList(),
            candidatesFor = "",
            pendingConfirmation = null,
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
        return commitCandidate(state, word)
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

    private fun aiCancel(state: KeyboardUiState): Reduction {
        val panel = state.ai
        if (!panel.visible) return Reduction(state)
        return Reduction(
            state.copy(
                ai = panel.copy(
                    tone = null,
                    status = if (state.aiReady) AiStatus.Idle else AiStatus.Unavailable,
                    result = "",
                    error = null,
                ),
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
