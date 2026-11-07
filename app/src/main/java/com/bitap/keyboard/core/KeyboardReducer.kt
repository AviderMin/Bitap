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
        val leavesLetters = layer != KeyboardLayer.Letters
        val commands = if (leavesLetters && state.composing.isNotEmpty()) {
            listOf(ImeCommand.CommitText(state.composing))
        } else {
            emptyList()
        }
        return Reduction(
            state.copy(
                layer = layer,
                shift = ShiftState.Off,
                composing = if (leavesLetters) "" else state.composing,
                candidates = if (leavesLetters) emptyList() else state.candidates,
                candidatesFor = if (leavesLetters) "" else state.candidatesFor,
                lastShiftTapAt = 0L,
            ),
            commands,
        )
    }

    private fun appendInput(state: KeyboardUiState, text: String): Reduction {
        if (text.isEmpty()) return Reduction(state)
        if (state.layer != KeyboardLayer.Letters) {
            return Reduction(state, listOf(ImeCommand.CommitText(text)))
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
                    // Punctuation must not be inserted on top of an active pinyin
                    // composition: commit the current candidate/raw text first.
                    val commands = buildList {
                        if (state.composing.isNotEmpty()) {
                            val currentCandidate = if (state.candidatesFor == state.composing) {
                                state.candidates.firstOrNull()?.word
                            } else {
                                null
                            }
                            add(ImeCommand.CommitText(currentCandidate ?: state.composing))
                        }
                        add(ImeCommand.CommitText(text))
                    }
                    Reduction(
                        state.copy(
                            composing = "",
                            candidates = emptyList(),
                            candidatesFor = "",
                            aiCandidates = emptyList(),
                            aiCandidatesLoading = false,
                            aiCandidatesError = null,
                        ),
                        commands,
                    )
                } else {
                    val composing = state.composing + lower
                    // Keep the previous candidates on screen until the debounced
                    // pinyin result arrives; clearing them here makes fast typing
                    // flash the candidate strip between every keystroke.
                    Reduction(
                        state.copy(composing = composing),
                        listOf(ImeCommand.SetComposingText(composing)),
                    )
                }
            }
        }
    }

    private fun applyShift(text: String, shift: ShiftState): String = when (shift) {
        ShiftState.Off -> text
        ShiftState.Shifted, ShiftState.Locked -> text.uppercase()
    }

    private fun composingUpdated(state: KeyboardUiState, action: KeyboardAction.ComposingUpdated): Reduction {
        if (action.composing != state.composing) return Reduction(state)
        return Reduction(
            state.copy(
                candidates = action.candidates.take(PinyinEngine.DEFAULT_LIMIT),
                candidatesFor = action.composing,
            ),
        )
    }

    private fun candidateTapped(state: KeyboardUiState, index: Int): Reduction {
        if (state.candidatesFor != state.composing) return commitRaw(state)
        val candidate = state.candidates.getOrNull(index) ?: return Reduction(state)
        return commitCandidate(state, candidate.word)
    }

    private fun commitCandidate(state: KeyboardUiState, word: String): Reduction = Reduction(
        state.copy(
            composing = "",
            candidates = emptyList(),
            candidatesFor = "",
            aiCandidates = emptyList(),
            aiCandidatesLoading = false,
            aiCandidatesError = null,
            shift = if (state.shift == ShiftState.Locked) ShiftState.Locked else ShiftState.Off,
        ),
        listOf(ImeCommand.CommitText(word)),
    )

    private fun commitRaw(state: KeyboardUiState): Reduction {
        if (state.composing.isEmpty()) return Reduction(state)
        return Reduction(
            state.copy(composing = "", candidates = emptyList(), candidatesFor = ""),
            listOf(ImeCommand.CommitText(state.composing)),
        )
    }

    private fun space(state: KeyboardUiState): Reduction = when {
        state.composing.isNotEmpty() &&
            state.candidates.isNotEmpty() &&
            state.candidatesFor == state.composing ->
            commitCandidate(state, state.candidates.first().word)

        state.composing.isNotEmpty() -> commitRaw(state)

        else -> Reduction(state, listOf(ImeCommand.CommitText(" ")))
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
                state.copy(composing = "", candidates = emptyList(), candidatesFor = ""),
                listOf(ImeCommand.FinishComposing, ImeCommand.DeleteBackward),
            )
        } else {
            Reduction(
                state.copy(composing = remaining),
                listOf(ImeCommand.SetComposingText(remaining)), // candidatesFor stays old until fresh query
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
                    aiCandidates = emptyList(),
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
                ai = AiPanelUiState(),
                aiEnabled = action.aiSettingEnabled && info.aiContextAllowed,
                aiCandidates = emptyList(),
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
        if (state.composing.isEmpty()) return Reduction(state)
        val hasCandidatesRegion = action.candidatesStart >= 0 &&
            action.candidatesEnd >= action.candidatesStart
        val selectionInsideRegion = hasCandidatesRegion &&
            action.selectionStart >= action.candidatesStart &&
            action.selectionEnd <= action.candidatesEnd
        if (selectionInsideRegion) return Reduction(state)
        return Reduction(
            state.copy(
                composing = "",
                candidates = emptyList(),
                candidatesFor = "",
                ai = AiPanelUiState(),
                aiCandidates = emptyList(),
                aiCandidatesLoading = false,
                aiCandidatesError = null,
                shift = if (state.shift == ShiftState.Locked) ShiftState.Locked else ShiftState.Off,
            ),
            listOf(ImeCommand.FinishComposing),
        )
    }

    private fun requestAiCandidates(state: KeyboardUiState): Reduction {
        if (!state.aiEnabled || !state.editorInfo.aiContextAllowed) return Reduction(state)
        return Reduction(
            state.copy(
                aiCandidates = emptyList(),
                aiCandidatesLoading = true,
                aiCandidatesError = null,
            ),
        )
    }

    private fun aiCandidatesUpdated(state: KeyboardUiState, candidates: List<String>): Reduction {
        val cleaned = candidates.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 24 }
            .distinct()
            .take(6)
            .toList()
        return Reduction(
            state.copy(
                aiCandidates = cleaned,
                aiCandidatesLoading = false,
                aiCandidatesError = if (cleaned.isEmpty()) "AI 词库没有返回候选" else null,
            ),
        )
    }

    private fun aiCandidatesFailed(state: KeyboardUiState, message: String): Reduction = Reduction(
        state.copy(
            aiCandidatesLoading = false,
            aiCandidatesError = message,
        ),
    )

    private fun aiCandidateTapped(state: KeyboardUiState, word: String): Reduction {
        if (word.isBlank()) return Reduction(state)
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
        return Reduction(state.copy(aiEnabled = action.enabled, aiReady = ready, ai = panel))
    }
}
