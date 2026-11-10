package com.bitap.keyboard.ime

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.bitap.keyboard.core.Candidate
import com.bitap.keyboard.core.DictionaryLoadState
import com.bitap.keyboard.core.EditorInputKind
import com.bitap.keyboard.core.InputLanguage
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardLayer
import com.bitap.keyboard.core.KeyboardReducer
import com.bitap.keyboard.core.KeyboardUiState
import com.bitap.keyboard.core.NumericInputMode
import com.bitap.keyboard.core.ShiftState
import com.bitap.keyboard.ui.theme.LocalBitapPalette

/**
 * Shared keyboard layout for both themes. Miuix and Glasmio only swap palette
 * tokens through [LocalBitapPalette]; all key positions and interactions stay
 * identical.
 */
private val KeyboardDockShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val LocalKeyboardKeyHeight = staticCompositionLocalOf { 54.dp }
private val LocalKeyboardRowGap = staticCompositionLocalOf { 0.dp }

/**
 * Pure layout policy for the expanded candidate panel.
 *
 * Compact (landscape/tiny window) must render the panel as a floating overlay so
 * it never contributes to the keyboard Column height; portrait keeps the inline
 * expanding panel. Extracted as pure functions so the policy is JVM-testable.
 */
internal fun usesCandidatePanelOverlay(compact: Boolean): Boolean = compact

/**
 * Expanded panel geometry.
 *
 * Chips flow horizontally and wrap by their intrinsic width, so short words
 * share a row while long words can span more width instead of every candidate
 * claiming its own full row. [candidatePanelVisibleRows] still fixes the panel
 * height budget: portrait 4 rows, compact 2 rows, plus the fixed 48 dp footer.
 * [candidatePanelPageSize] is the number of candidates per page (not rows); a
 * page may scroll internally when large fonts or many long words need it.
 */
internal const val CANDIDATE_PANEL_ROW_HEIGHT_DP = 50
internal const val CANDIDATE_PANEL_FOOTER_HEIGHT_DP = 48
internal const val CANDIDATE_PANEL_INNER_PADDING_DP = 16

/** Rows that must fit without scrolling at the default font scale. */
internal fun candidatePanelVisibleRows(compact: Boolean): Int = if (compact) 2 else 4

/**
 * Candidate capacity per page. Flow layout fits several short chips per row,
 * so a page holds more candidates than the row budget; the item area scrolls
 * when long words or an accessibility font increase the row count.
 */
internal fun candidatePanelPageSize(compact: Boolean): Int = if (compact) 8 else 12

internal fun candidatePanelMaxHeightDp(compact: Boolean): Int =
    candidatePanelVisibleRows(compact) * CANDIDATE_PANEL_ROW_HEIGHT_DP +
        CANDIDATE_PANEL_FOOTER_HEIGHT_DP + CANDIDATE_PANEL_INNER_PADDING_DP

/** Number of pages for [candidateCount] candidates; an empty result still has one page. */
internal fun candidatePanelPageCount(candidateCount: Int, pageSize: Int): Int {
    if (candidateCount <= 0 || pageSize <= 0) return 1
    return (candidateCount + pageSize - 1) / pageSize
}

/** Page containing the zero-based [index]; negative indices clamp to the first page. */
internal fun pageForIndex(index: Int, pageSize: Int): Int {
    if (index <= 0 || pageSize <= 0) return 0
    return index / pageSize
}

/**
 * A partial candidate covers only a prefix of the current composition. A
 * consumed length of 0 is the legacy "whole composition" encoding, so only
 * lengths in `1 until composingLength` are partial.
 */
internal fun isPartialCandidate(candidate: Candidate, composingLength: Int): Boolean =
    composingLength > 0 && candidate.consumedInputLength in 1 until composingLength

internal fun firstPartialCandidateIndex(candidates: List<Candidate>, composingLength: Int): Int =
    candidates.indexOfFirst { isPartialCandidate(it, composingLength) }

/** Badge under a candidate word; correction and partial markers can combine. */
internal fun candidateBadgeLabel(isCorrection: Boolean, isPartial: Boolean): String? = when {
    isPartial && isCorrection -> "纠错·分段"
    isPartial -> "分段"
    isCorrection -> "纠错"
    else -> null
}

/**
 * Accessibility description for one candidate chip. A partial selection keeps
 * the unconsumed pinyin suffix editable, so its description must announce that.
 */
internal fun candidateChipContentDescription(candidate: Candidate, composingLength: Int): String {
    val partial = isPartialCandidate(candidate, composingLength)
    return when {
        partial && candidate.isCorrection -> "纠错·分段候选 " + candidate.word + "，选择后其余拼音保留"
        partial -> "分段候选 " + candidate.word + "，选择后其余拼音保留"
        candidate.isCorrection -> "纠错候选 " + candidate.word
        else -> "候选词 " + candidate.word
    }
}

/**
 * User-visible semantics of the ✦ AI entry. It is a local AI lexicon candidate
 * generator driven by pinyin — never a text-rewrite entry.
 */
internal fun aiEntryContentDescription(aiLoading: Boolean, aiReady: Boolean): String = when {
    aiLoading -> "取消 AI 词库请求"
    aiReady -> "AI 词库候选（只根据拼音）"
    else -> "AI 词库未就绪"
}

internal fun aiEntryLabel(aiLoading: Boolean): String = if (aiLoading) "取消 AI" else "✦ AI"

/**
 * Progressive delete-repeat interval. A held backspace starts at the existing
 * 55 ms cadence, speeds up after eight repeats and again after twenty so long
 * deletions do not require dozens of separate presses. Pure so JVM tests can
 * pin the curve without a device.
 */
internal fun deleteRepeatIntervalMs(repeatCount: Int): Long = when {
    repeatCount <= 8 -> 55L
    repeatCount <= 20 -> 40L
    else -> 25L
}

/** P0-B: exact dictionary status copy, shown only in Chinese mode. */
internal const val DICTIONARY_LOADING_TEXT = "词库加载中…"

/** P0-B: failed state copy; the whole line is tappable to retry the load. */
internal const val DICTIONARY_FAILED_TEXT = "词库加载失败，点此重试（仍可提交原文）"

/**
 * P0-B: single-line dictionary status for the candidate bar. [Ready] renders
 * nothing (no permanent placeholder), and English mode hides dictionary status
 * entirely because pinyin candidates do not apply there.
 */
internal fun dictionaryStatusText(
    language: InputLanguage,
    state: DictionaryLoadState,
): String? = when {
    language != InputLanguage.Chinese -> null
    state == DictionaryLoadState.Loading -> DICTIONARY_LOADING_TEXT
    state == DictionaryLoadState.Failed -> DICTIONARY_FAILED_TEXT
    else -> null
}

/** P0-B: only the failed dictionary state turns the status line into a retry button. */
internal fun dictionaryStatusActionable(state: DictionaryLoadState): Boolean =
    state == DictionaryLoadState.Failed

/**
 * P0-B + P0-A: one merged candidate-bar status line.
 *
 * Priority: dictionary failure (with retry) > candidate query error > dictionary
 * loading. The two error kinds never overwrite each other in state; the UI shows
 * the higher-priority one on the same line so no extra permanent row is reserved.
 * [isError] selects the danger color.
 */
internal data class CandidateBarStatus(
    val text: String,
    val actionable: Boolean,
    val isError: Boolean,
)

internal fun candidateBarStatus(
    language: InputLanguage,
    dictionaryState: DictionaryLoadState,
    candidateQueryError: String?,
): CandidateBarStatus? {
    if (language != InputLanguage.Chinese) return null
    val dictionaryFailedText = dictionaryStatusText(language, DictionaryLoadState.Failed)
    if (dictionaryState == DictionaryLoadState.Failed && dictionaryFailedText != null) {
        return CandidateBarStatus(
            text = dictionaryFailedText,
            actionable = true,
            isError = true,
        )
    }
    val queryError = candidateQueryError?.takeIf { it.isNotBlank() }
    if (queryError != null) {
        return CandidateBarStatus(text = queryError, actionable = false, isError = true)
    }
    val loadingText = dictionaryStatusText(language, DictionaryLoadState.Loading)
    if (dictionaryState == DictionaryLoadState.Loading && loadingText != null) {
        return CandidateBarStatus(text = loadingText, actionable = false, isError = false)
    }
    return null
}

/**
 * Whether the last candidate row should remain on screen while the next
 * debounced query is pending. This is deliberately a display-only decision:
 * the reducer still clears stale candidates so they can never be committed.
 */
internal fun shouldRetainCandidateRow(
    composing: String,
    candidatesFor: String,
    hasFreshCandidates: Boolean,
    candidateQueryError: String?,
): Boolean = !hasFreshCandidates &&
    composing.isNotEmpty() &&
    candidatesFor != composing &&
    candidatesFor != KeyboardReducer.LITERAL_COMPOSING_MARKER &&
    candidateQueryError == null

/**
 * Editing tools are a dedicated keyboard page, not a transient row. The page
 * stays open only while the editor has no active pinyin composition: composing
 * text owns the candidate bar and the key area, so the page must not compete
 * with it for height or taps.
 */
internal fun shouldShowEditingPage(state: KeyboardUiState): Boolean =
    state.editingToolsVisible && state.composing.isEmpty()

/** One key of [EditingToolsPage]: visual label, accessibility text and action. */
internal data class EditingTool(
    val label: String,
    val description: String,
    val action: KeyboardAction,
)

/**
 * The eight editing-tool keys in visual order (select all, copy, cut, paste,
 * word-left, word-right, delete-word, collapse). Pure so JVM tests can pin the
 * copy and order without rendering the Compose row.
 */
internal fun editingTools(): List<EditingTool> = listOf(
    EditingTool(label = "全选", description = "全选文本", action = KeyboardAction.SelectAll),
    EditingTool(label = "复制", description = "复制选中文本", action = KeyboardAction.CopySelection),
    EditingTool(label = "剪切", description = "剪切选中文本", action = KeyboardAction.CutSelection),
    EditingTool(label = "粘贴", description = "粘贴剪贴板内容", action = KeyboardAction.Paste),
    EditingTool(label = "词左", description = "光标左移一个词", action = KeyboardAction.MoveCursorByWord(-1)),
    EditingTool(label = "词右", description = "光标右移一个词", action = KeyboardAction.MoveCursorByWord(1)),
    EditingTool(label = "删词", description = "删除前一个词", action = KeyboardAction.DeleteWord),
    EditingTool(label = "收起", description = "收起编辑工具", action = KeyboardAction.ToggleEditingTools),
)

/**
 * Undo/redo live only on the dedicated editing page. The main keyboard's
 * function row stays uncluttered next to the Chinese/English toggle.
 */
internal fun editingHistoryTools(): List<EditingTool> = listOf(
    EditingTool(label = "撤销", description = "撤销上次上屏", action = KeyboardAction.Undo),
    EditingTool(label = "重做", description = "重做上次撤销", action = KeyboardAction.Redo),
)

/** Labels of [editingTools] in visual order, exposed for JVM copy tests. */
internal fun editingToolLabels(): List<String> = editingTools().map { it.label }

/** Labels of [editingHistoryTools] in visual order, exposed for JVM copy tests. */
internal fun editingHistoryToolLabels(): List<String> = editingHistoryTools().map { it.label }

/** One horizontal cursor step for the space-below gesture pad. */
internal const val CURSOR_DRAG_STEP_DP = 16

/** Accessibility hint for the invisible long-press drag pad under the spacebar. */
internal const val SPACE_CURSOR_HINT = "空格下方长按滑动移动光标"

/**
 * Whole cursor steps represented by a horizontal drag distance. Pure so the
 * gesture threshold and direction can be pinned on the JVM without Compose.
 */
internal fun cursorStepsForDrag(dragPx: Float, thresholdPx: Float): Int {
    if (thresholdPx <= 0f) return 0
    return (dragPx / thresholdPx).toInt()
}


@Composable
fun KeyboardScreen(
    state: KeyboardUiState,
    onAction: (KeyboardAction) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalBitapPalette.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    // Candidate panel state is hoisted here so compact (landscape) mode can
    // render the expanded panel as an overlay instead of adding keyboard height.
    var candidatesExpanded by remember { mutableStateOf(false) }
    var candidatePage by remember { mutableIntStateOf(0) }
    // Preserve the last rendered candidate row while the next debounced query is
    // in flight. The reducer must clear stale candidates for correctness, but the
    // UI should not blink blank on every letter: old chips stay visible (disabled)
    // until the new query publishes, then they are replaced atomically.
    var retainedCandidates by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var retainedComposingLength by remember { mutableIntStateOf(0) }
    // T06: in landscape/tiny windows the classic 54 dp rows + candidate strips
    // cover the editor. Compact mode keeps the row hit target at 48 dp
    // (42 dp keycap + 6 dp vertical padding) while reducing paddings, removing
    // the decorative dock line and collapsing candidate extras to one row.
    val compact = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenHeightDp < 480
    val freshCandidates = state.candidates
    val freshCandidatesFor = state.candidatesFor
    LaunchedEffect(freshCandidates, freshCandidatesFor, state.composing, state.candidateQueryError) {
        when {
            freshCandidates.isNotEmpty() -> {
                retainedCandidates = freshCandidates
                retainedComposingLength = freshCandidatesFor.length.takeIf { it > 0 } ?: state.composing.length
            }

            !shouldRetainCandidateRow(
                composing = state.composing,
                candidatesFor = freshCandidatesFor,
                hasFreshCandidates = freshCandidates.isNotEmpty(),
                candidateQueryError = state.candidateQueryError,
            ) -> {
                retainedCandidates = emptyList()
                retainedComposingLength = 0
            }
        }
    }
    val displayCandidates = if (freshCandidates.isNotEmpty()) freshCandidates else retainedCandidates
    val displayComposingLength = if (freshCandidates.isNotEmpty()) {
        freshCandidatesFor.length.takeIf { it > 0 } ?: state.composing.length
    } else {
        retainedComposingLength
    }
    val candidatesInteractive = freshCandidates.isNotEmpty() && freshCandidatesFor == state.composing
    val heightScale = state.keyboardHeightScale.coerceIn(0.85f, 1.35f)
    val keyHeight = if (compact) 42.dp else 54.dp
    val rowGap = if (compact) 2.dp else 4.dp
    // Height customization only reserves empty space at the very bottom; it is
    // intentionally disabled in compact mode so the editor keeps its line/cursor.
    val bottomLift = if (compact) 0.dp else (80.dp * (heightScale - 0.85f)).coerceAtLeast(0.dp)
    val haptic = {
        if (state.hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
    val keySound = {
        if (state.soundEnabled) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
        }
    }
    val press: (KeyboardAction) -> Unit = { action ->
        haptic()
        keySound()
        onAction(action)
    }

    CompositionLocalProvider(
        LocalKeyboardKeyHeight provides keyHeight,
        LocalKeyboardRowGap provides rowGap,
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(KeyboardDockShape)
                .then(
                    if (palette.isGlass) {
                        Modifier.background(
                            Brush.verticalGradient(
                                listOf(palette.background, palette.elevatedSurface),
                            ),
                        )
                    } else {
                        Modifier.background(palette.background)
                    },
                )
                .border(0.5.dp, palette.hairline, KeyboardDockShape)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = if (compact) 2.dp else 5.dp, bottom = if (compact) 2.dp else 4.dp),
        ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(rowGap),
        ) {
            if (!compact) {
                // A quiet visual dock line replaces the old hard top edge. It is
                // decorative, not a drag handle: height is controlled in Settings.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(34.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(palette.textSecondary.copy(alpha = 0.28f)),
                    )
                }
            }
            CandidateBar(
                state = state,
                displayCandidates = displayCandidates,
                displayComposingLength = displayComposingLength,
                candidatesInteractive = candidatesInteractive,
                onAction = onAction,
                haptic = haptic,
                onOpenSettings = onOpenSettings,
                compact = compact,
                candidatesExpanded = candidatesExpanded,
                onCandidatesExpandedChange = { candidatesExpanded = it },
                candidatePage = candidatePage,
                onCandidatePageChange = { candidatePage = it },
            )
            if (shouldShowEditingPage(state)) {
                EditingToolsPage(press = press)
            } else {
                when (state.layer) {
                    KeyboardLayer.Letters -> LetterLayout(state = state, press = press)
                    KeyboardLayer.Numbers -> if (state.numericMode != NumericInputMode.None) {
                        NumericLayout(state = state, press = press)
                    } else {
                        SymbolLayout(
                            rows = NumberRows,
                            bottomRow = listOf(".", ",", "?", "!", "'", "\""),
                            switchLabel = "#+=",
                            switchDescription = "切换到更多符号",
                            nextLayer = KeyboardLayer.Symbols,
                            press = press,
                        )
                    }

                    KeyboardLayer.Symbols -> SymbolLayout(
                        rows = SymbolRows,
                        bottomRow = listOf(".", ",", "?", "!", ":", ";", "'"),
                        switchLabel = "ABC",
                        switchDescription = "切换到字母键盘",
                        nextLayer = KeyboardLayer.Letters,
                        press = press,
                    )
                }
                FunctionRow(state = state, press = press)
                if (!compact) {
                    SpaceCursorPad(
                        press = press,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .height(maxOf(4.dp + bottomLift, 16.dp)),
                    )
                }
            }
        }
        // P0 landscape fix: the expanded panel is an overlay anchored above the
        // fixed bottom function row. It never adds layout height, so the key area
        // and the bottom row stay on screen and the IME window does not resize.
        if (usesCandidatePanelOverlay(compact)) {
            CandidatePanelOverlay(
                state = state,
                displayCandidates = displayCandidates,
                displayComposingLength = displayComposingLength,
                candidatesInteractive = candidatesInteractive,
                expanded = candidatesExpanded,
                page = candidatePage,
                onPageChange = { candidatePage = it },
                onCandidateClick = { index ->
                    haptic()
                    onAction(KeyboardAction.CandidateTapped(index))
                },
                onDismiss = { candidatesExpanded = false },
                haptic = haptic,
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(10f),
            )
        }
        }
    }
}

@Composable
private fun CandidateBar(
    state: KeyboardUiState,
    displayCandidates: List<Candidate>,
    displayComposingLength: Int,
    candidatesInteractive: Boolean,
    onAction: (KeyboardAction) -> Unit,
    haptic: () -> Unit,
    onOpenSettings: () -> Unit,
    compact: Boolean = false,
    candidatesExpanded: Boolean = false,
    onCandidatesExpandedChange: (Boolean) -> Unit = {},
    candidatePage: Int = 0,
    onCandidatePageChange: (Int) -> Unit = {},
) {
    val palette = LocalBitapPalette.current
    val candidateScrollState = rememberScrollState()
    val expandedPageSize = candidatePanelPageSize(compact)
    val composingLength = displayComposingLength
    // R11: a new candidate result set starts at the first candidate instead of
    // leaving the previous scroll position at the end of the old result list.
    // Do not key this on state.composing: the retained row must stay put while
    // the next query is in flight.
    LaunchedEffect(displayCandidates, displayComposingLength) {
        onCandidatePageChange(0)
        candidateScrollState.scrollTo(0)
        if (displayCandidates.isEmpty()) onCandidatesExpandedChange(false)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
    if (!compact && state.composing.isNotEmpty()) {
        ComposingStrip(
            composing = state.composing,
            onCommitRaw = {
                haptic()
                onAction(KeyboardAction.CommitRaw)
            },
        )
    }
    if (!compact && state.aiEnabled && state.aiCandidates.isNotEmpty()) {
        AiCandidateStrip(
            candidates = state.aiCandidates,
            onCandidateClick = { word ->
                haptic()
                onAction(KeyboardAction.AiCandidateTapped(word))
            },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact && state.composing.isNotEmpty()) {
            Text(
                text = "拼音 " + state.composing,
                color = palette.accent,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 72.dp)
                    .padding(start = 4.dp, end = 2.dp),
            )
        }
        if (state.composing.isNotEmpty() || displayCandidates.isNotEmpty() ||
            state.aiCandidates.isNotEmpty() || state.englishSuggestions.isNotEmpty()
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(candidateScrollState),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compact && state.aiEnabled && state.aiCandidates.isNotEmpty()) {
                    Text(
                        text = "AI",
                        color = palette.accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 2.dp, end = 1.dp),
                    )
                    state.aiCandidates.forEach { word ->
                        CandidateChip(
                            label = word,
                            description = "AI 词库候选 " + word,
                            accent = true,
                            onClick = {
                                haptic()
                                onAction(KeyboardAction.AiCandidateTapped(word))
                            },
                        )
                    }
                }
                if (state.language == InputLanguage.English && state.englishSuggestions.isNotEmpty()) {
                    Text(
                        text = "联想",
                        color = palette.accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 2.dp, end = 1.dp),
                    )
                    state.englishSuggestions.forEachIndexed { index, word ->
                        CandidateChip(
                            label = word,
                            description = "英文联想 " + word,
                            accent = index == 0,
                            onClick = {
                                haptic()
                                onAction(KeyboardAction.EnglishSuggestionTapped(word))
                            },
                        )
                    }
                }
                if (displayCandidates.isEmpty() && state.composing.isNotEmpty()) {
                    CandidateChip(
                        label = state.composing,
                        description = "提交原始拼音 " + state.composing,
                        accent = true,
                        onClick = {
                            haptic()
                            onAction(KeyboardAction.CommitRaw)
                        },
                    )
                } else {
                    displayCandidates.forEachIndexed { index, candidate ->
                        CandidateChip(
                            label = candidate.word,
                            description = candidateChipContentDescription(candidate, composingLength),
                            accent = index == 0,
                            isCorrection = candidate.isCorrection,
                            isPartial = isPartialCandidate(candidate, composingLength),
                            enabled = candidatesInteractive,
                            onClick = {
                                haptic()
                                onAction(KeyboardAction.CandidateTapped(index))
                            },
                        )
                    }
                }
            }
        } else {
            Text(
                text = if (state.language == InputLanguage.Chinese) "拼音候选" else "英文输入",
                color = palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }

        if (compact && state.composing.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable {
                        haptic()
                        onAction(KeyboardAction.CommitRaw)
                    }
                    .semantics { contentDescription = "提交原始拼音 " + state.composing },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "原文", color = palette.accent, fontSize = 11.sp)
            }
        }
        if (displayCandidates.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable {
                        haptic()
                        onCandidatesExpandedChange(!candidatesExpanded)
                    }
                    .semantics {
                        contentDescription = if (candidatesExpanded) "收起候选列表" else "展开候选列表"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (candidatesExpanded) "⌃" else "⌄",
                    color = if (candidatesExpanded) palette.accent else palette.textSecondary,
                    fontSize = 14.sp,
                )
            }
        }
        if (state.aiEnabled) {
            val ready = state.aiReady
            val aiLoading = state.aiCandidatesLoading
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .alpha(if (ready || aiLoading) 1f else 0.45f)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable {
                        haptic()
                        // P0: while loading the same entry is an explicit cancel.
                        onAction(
                            if (aiLoading) KeyboardAction.AiCancel else KeyboardAction.RequestAiCandidates,
                        )
                    }
                    .semantics {
                        contentDescription = aiEntryContentDescription(
                            aiLoading = aiLoading,
                            aiReady = ready,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = aiEntryLabel(aiLoading),
                    color = if (ready || aiLoading) palette.accent else palette.textSecondary,
                    fontSize = if (aiLoading) 10.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        // Dedicated editing page entry: between the AI entry and settings, not
        // in the bottom function row.
        if (state.composing.isEmpty()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable {
                        haptic()
                        onAction(KeyboardAction.ToggleEditingTools)
                    }
                    .semantics {
                        contentDescription = if (state.editingToolsVisible) "返回键盘" else "打开编辑工具页"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "编辑",
                    color = if (state.editingToolsVisible) palette.accent else palette.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable {
                    haptic()
                    onOpenSettings()
                }
                .semantics { contentDescription = "打开 Bitap 设置" },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "⚙", color = palette.textSecondary, fontSize = 18.sp)
        }
    }
        // Portrait keeps the inline panel. Compact (landscape) renders the same
        // panel as a fixed-height overlay in KeyboardScreen so it can never push
        // the key rows or the bottom function row off screen.
        if (!compact) {
            AnimatedVisibility(
                visible = candidatesExpanded && displayCandidates.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                CandidateExpandedPanel(
                    candidates = displayCandidates,
                    composingLength = composingLength,
                    page = candidatePage,
                    pageSize = expandedPageSize,
                    compact = compact,
                    enabled = candidatesInteractive,
                    onPageChange = onCandidatePageChange,
                    onCandidateClick = { index ->
                        haptic()
                        onAction(KeyboardAction.CandidateTapped(index))
                    },
                    haptic = haptic,
                )
            }
        }
    }
    val aiStatus = when {
        state.aiCandidatesLoading -> "AI 词库思考中…"
        !state.aiCandidatesError.isNullOrBlank() -> state.aiCandidatesError
        else -> null
    }
    if (aiStatus != null && (!compact || !state.aiCandidatesError.isNullOrBlank())) {
        Text(
            text = aiStatus,
            color = if (state.aiCandidatesError.isNullOrBlank()) palette.accent else palette.danger,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        )
    }
    // P0-B + P0-A: one contextual status line under the candidate strip. It is
    // absent in Ready/English, so no permanent placeholder changes keyboard
    // height; a failed dictionary reuses the line as a full-width retry button
    // while CommitRaw (raw chip / 原文) stays independently available.
    val statusLine = candidateBarStatus(
        language = state.language,
        dictionaryState = state.dictionaryState,
        candidateQueryError = state.candidateQueryError,
    )
    if (statusLine != null) {
        Text(
            text = statusLine.text,
            color = if (statusLine.isError) palette.danger else palette.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (statusLine.actionable) {
                        Modifier
                            .clickable {
                                haptic()
                                onAction(KeyboardAction.RetryDictionaryLoad)
                            }
                            .semantics { contentDescription = "重新加载词库" }
                    } else {
                        Modifier
                    },
                )
                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateExpandedPanel(
    candidates: List<Candidate>,
    composingLength: Int,
    page: Int,
    pageSize: Int,
    compact: Boolean = false,
    enabled: Boolean = true,
    onPageChange: (Int) -> Unit,
    onCandidateClick: (Int) -> Unit,
    haptic: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val palette = LocalBitapPalette.current
    val pageCount = candidatePanelPageCount(candidates.size, pageSize)
    val currentPage = page.coerceIn(0, pageCount - 1)
    val pageStart = currentPage * pageSize
    val pageItems = candidates.drop(pageStart).take(pageSize)
    val firstPartialIndex = firstPartialCandidateIndex(candidates, composingLength)
    val firstPartialPage = if (firstPartialIndex >= 0) pageForIndex(firstPartialIndex, pageSize) else -1
    val itemScrollState = rememberScrollState()
    // Page changes must start at the first row of the new page instead of keeping
    // the previous page's internal scroll offset.
    LaunchedEffect(currentPage) {
        itemScrollState.scrollTo(0)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.elevatedSurface)
            .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
            // In compact mode this panel floats over the keys; consume blank-area
            // taps so they cannot leak to the key row underneath.
            .then(if (compact) Modifier.clickable { } else Modifier)
            // The budget includes the 8 dp inner padding, so all page rows plus the
            // fixed 48 dp footer fit without scrolling at the default font scale.
            .heightIn(max = candidatePanelMaxHeightDp(compact).dp)
            .padding(8.dp),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(itemScrollState),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            pageItems.forEachIndexed { itemIndex, candidate ->
                val globalIndex = pageStart + itemIndex
                CandidateChip(
                    label = candidate.word,
                    description = candidateChipContentDescription(candidate, composingLength),
                    accent = globalIndex == 0,
                    maxLines = 3,
                    isCorrection = candidate.isCorrection,
                    isPartial = isPartialCandidate(candidate, composingLength),
                    enabled = enabled,
                    onClick = {
                        haptic()
                        onCandidateClick(globalIndex)
                    },
                )
            }
        }
        // The footer is outside the item scroller so the page controls stay visible
        // even when a large accessibility font forces the item list to scroll.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (candidates.size > pageSize) {
                    "第 " + (currentPage + 1) + " / " + pageCount + " 页 · 共 " + candidates.size + " 项"
                } else {
                    "共 " + candidates.size + " 项"
                },
                color = palette.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (firstPartialPage >= 0 && currentPage != firstPartialPage) {
                PanelTextButton(text = "看分段", onClick = {
                    haptic()
                    onPageChange(firstPartialPage)
                })
            }
            if (currentPage > 0) {
                PanelTextButton(text = "上一页", onClick = {
                    haptic()
                    onPageChange(currentPage - 1)
                })
            }
            if (currentPage < pageCount - 1) {
                PanelTextButton(text = "下一页", onClick = {
                    haptic()
                    onPageChange(currentPage + 1)
                })
            }
            if (onDismiss != null) {
                PanelTextButton(text = "收起候选", onClick = {
                    haptic()
                    onDismiss()
                })
            }
        }
    }
}

/**
 * P0 landscape overlay: fixed-height, scrollable candidate panel drawn above the
 * key area but below the candidate bar and above the bottom function row. Because
 * it is a Box sibling (not a Column child) it cannot change the IME window height.
 */
@Composable
private fun CandidatePanelOverlay(
    state: KeyboardUiState,
    displayCandidates: List<Candidate>,
    displayComposingLength: Int,
    candidatesInteractive: Boolean,
    expanded: Boolean,
    page: Int,
    onPageChange: (Int) -> Unit,
    onCandidateClick: (Int) -> Unit,
    onDismiss: () -> Unit,
    haptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = expanded && displayCandidates.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        CandidateExpandedPanel(
            candidates = displayCandidates,
            composingLength = displayComposingLength,
            page = page,
            pageSize = candidatePanelPageSize(compact = true),
            compact = true,
            enabled = candidatesInteractive,
            onPageChange = onPageChange,
            onCandidateClick = onCandidateClick,
            haptic = haptic,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 50.dp),
            onDismiss = onDismiss,
        )
    }
}

/** Fixed AI lexicon strip: AI results stay discoverable even with 64 local candidates. */
@Composable
private fun AiCandidateStrip(
    candidates: List<String>,
    onCandidateClick: (String) -> Unit,
) {
    val palette = LocalBitapPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "AI 词库",
            color = palette.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            candidates.forEach { word ->
                CandidateChip(
                    label = word,
                    description = "AI 词库候选 " + word,
                    accent = true,
                    onClick = { onCandidateClick(word) },
                )
            }
        }
    }
}

/**
 * Stable composing display above the candidate scroller. It keeps the current
 * pinyin visible even when candidates are present, and exposes an explicit
 * "commit raw" action so users can always submit the letters themselves.
 */
@Composable
private fun ComposingStrip(
    composing: String,
    onCommitRaw: () -> Unit,
) {
    val palette = LocalBitapPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "拼音",
                    color = palette.textSecondary,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = composing,
                    color = palette.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onCommitRaw)
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = "提交原始拼音 " + composing },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "上屏原文",
                color = palette.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun CandidateChip(
    modifier: Modifier = Modifier,
    label: String,
    description: String,
    accent: Boolean,
    maxLines: Int = 1,
    isCorrection: Boolean = false,
    isPartial: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalBitapPalette.current
    val badge = candidateBadgeLabel(isCorrection = isCorrection, isPartial = isPartial)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = if (accent) palette.accent else palette.textPrimary,
                fontSize = 18.sp,
                fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (badge != null) {
                Text(
                    text = badge,
                    color = palette.textSecondary,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PanelTextButton(text: String, onClick: () -> Unit) {
    val palette = LocalBitapPalette.current
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = palette.textSecondary, fontSize = 13.sp)
    }
}

/**
 * One logical key in a row. The row owns hit-testing; this description only
 * carries layout weight, visual state and the action to run on a successful tap.
 */
private data class KeyCap(
    val label: String,
    val description: String,
    val weight: Float = 1f,
    val isFunction: Boolean = false,
    val isActive: Boolean = false,
    val fontSize: TextUnit = 20.sp,
    val showPressPreview: Boolean = false,
    /** Fired once after the long-press threshold; release then does not click. */
    val longPressAction: (() -> Unit)? = null,
    /** Fired repeatedly after the long-press threshold (delete key). */
    val longPressRepeat: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** Maps an x coordinate inside [widthPx] to the nearest key by weight. */
private fun rowKeyIndexAt(x: Float, widthPx: Float, keys: List<KeyCap>): Int {
    if (keys.isEmpty() || widthPx <= 0f) return -1
    val totalWeight = keys.sumOf { it.weight.toDouble() }.toFloat()
    if (totalWeight <= 0f) return -1
    val clamped = x.coerceIn(0f, widthPx - 0.01f)
    var cumulative = 0f
    keys.forEachIndexed { index, key ->
        cumulative += (key.weight / totalWeight) * widthPx
        if (clamped < cumulative) return index
    }
    return keys.lastIndex
}

/**
 * Pure multi-pointer bookkeeping for one key row.
 *
 * The Compose pointer handler below drives this state machine from real
 * PointerInputChanges. Extracting it keeps R01/R02 semantics testable on the JVM:
 * each pointer owns its key, cancelled flag and repeat count, so lifting one
 * finger cannot cancel or commit another finger's key.
 */
internal class KeyRowPointerTracker {
    private data class PointerState(
        var keyIndex: Int,
        var repeated: Boolean = false,
        var repeatCount: Int = 0,
        var cancelled: Boolean = false,
    )

    private val pointers = LinkedHashMap<Long, PointerState>()

    val activePointerCount: Int
        get() = pointers.size

    val pressedKeyIndices: Set<Int>
        get() = pointers.values
            .asSequence()
            .filter { !it.cancelled }
            .map { it.keyIndex }
            .toSet()

    fun contains(pointerId: Long): Boolean = pointers.containsKey(pointerId)

    fun keyIndexOf(pointerId: Long): Int? = pointers[pointerId]?.keyIndex

    fun down(pointerId: Long, keyIndex: Int) {
        pointers[pointerId] = PointerState(keyIndex)
    }

    /** @return true when this pointer moved to a different key and its repeat state restarted. */
    fun setKey(pointerId: Long, keyIndex: Int): Boolean {
        val pointer = pointers[pointerId] ?: return false
        if (pointer.keyIndex == keyIndex) return false
        pointer.keyIndex = keyIndex
        pointer.repeated = false
        pointer.repeatCount = 0
        return true
    }

    /** @return true when this pointer moved from outside the row back inside it. */
    fun resume(pointerId: Long): Boolean {
        val pointer = pointers[pointerId] ?: return false
        if (!pointer.cancelled) return false
        pointer.cancelled = false
        // A re-entry onto the same key continues the same long-press, so keep
        // the repeated fact. Moving to a different key resets it in setKey().
        return true
    }

    /** @return true when this call cancelled a still-active pointer. */
    fun cancel(pointerId: Long): Boolean {
        val pointer = pointers[pointerId] ?: return false
        if (pointer.cancelled) return false
        pointer.cancelled = true
        return true
    }

    /**
     * Record one long-press repeat. This fact must survive repeat-job cancellation;
     * that is the R02 invariant that prevents the extra click on release.
     */
    fun recordRepeat(pointerId: Long) {
        val pointer = pointers[pointerId] ?: return
        pointer.repeated = true
        pointer.repeatCount += 1
    }

    fun repeatCount(pointerId: Long): Int = pointers[pointerId]?.repeatCount ?: 0

    fun isRepeated(pointerId: Long): Boolean = pointers[pointerId]?.repeated == true

    /**
     * Remove the pointer and return the key index to click, or null when this
     * pointer already repeated (its deletions already happened) or was cancelled.
     */
    fun release(pointerId: Long): Int? {
        val pointer = pointers.remove(pointerId) ?: return null
        return if (!pointer.cancelled && !pointer.repeated) pointer.keyIndex else null
    }

    fun clear() {
        pointers.clear()
    }
}

/**
 * A row is the hit-testing unit: every pixel inside it belongs to the nearest
 * key by weight, so the 2 dp visual gaps and the 3 dp vertical padding are no
 * longer dead zones. The visual keycap stays inset and keeps the fixed 54 dp
 * height.
 *
 * Multi-touch contract (R01/R02):
 * - every pointer has its own key, pressed state, repeat job and repeated fact;
 * - every pointer in a batched initial down event is registered (q+w same frame);
 * - across multiple pointers, keys commit in the order the pointers are lifted;
 * - lifting one finger never cancels another finger's selection;
 * - horizontal movement selects the nearest sibling under that finger;
 * - vertical movement more than 48 dp outside the row cancels only that pointer;
 * - a pointer that fired at least one long-press repeat never triggers a
 *   single-click action on release; stopping the repeat job does not clear that fact.
 */
@Composable
private fun KeyRow(
    keys: List<KeyCap>,
    modifier: Modifier = Modifier,
) {
    var pressedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val currentKeys = rememberUpdatedState(keys)
    val pointerScope = rememberCoroutineScope()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val tracker = KeyRowPointerTracker()
                    val repeatJobs = mutableMapOf<Long, Job>()
                    val rowWidth = size.width.toFloat()

                    fun refreshPressed() {
                        pressedIndices = tracker.pressedKeyIndices
                    }

                    fun stopRepeat(pointerId: Long) {
                        repeatJobs.remove(pointerId)?.cancel()
                    }

                    fun startRepeat(pointerId: Long) {
                        stopRepeat(pointerId)
                        val index = tracker.keyIndexOf(pointerId) ?: return
                        val key = currentKeys.value.getOrNull(index) ?: return
                        val repeat = key.longPressRepeat
                        val longPressAction = key.longPressAction
                        if (repeat == null && longPressAction == null) return
                        repeatJobs[pointerId] = pointerScope.launch {
                            delay(400)
                            if (repeat != null) {
                                while (isActive) {
                                    repeat()
                                    tracker.recordRepeat(pointerId)
                                    delay(deleteRepeatIntervalMs(tracker.repeatCount(pointerId)))
                                }
                            } else {
                                tracker.recordRepeat(pointerId)
                                longPressAction?.invoke()
                            }
                        }
                    }

                    fun handleDown(change: PointerInputChange) {
                        val index = rowKeyIndexAt(change.position.x, rowWidth, currentKeys.value)
                        if (index < 0) return
                        tracker.down(change.id.value, index)
                        refreshPressed()
                        startRepeat(change.id.value)
                    }

                    fun handleMove(change: PointerInputChange) {
                        val pointerId = change.id.value
                        val outsideRow = change.position.y < -48.dp.toPx() ||
                            change.position.y > size.height + 48.dp.toPx()
                        if (outsideRow) {
                            if (tracker.cancel(pointerId)) {
                                stopRepeat(pointerId)
                                refreshPressed()
                            }
                            return
                        }
                        val index = rowKeyIndexAt(change.position.x, rowWidth, currentKeys.value)
                        if (index < 0) return
                        if (tracker.resume(pointerId)) {
                            refreshPressed()
                            startRepeat(pointerId)
                        }
                        if (tracker.setKey(pointerId, index)) {
                            startRepeat(pointerId)
                        }
                        refreshPressed()
                    }

                    fun handleUp(change: PointerInputChange) {
                        val pointerId = change.id.value
                        val committedIndex = tracker.release(pointerId)
                        stopRepeat(pointerId)
                        refreshPressed()
                        if (committedIndex != null) {
                            currentKeys.value.getOrNull(committedIndex)?.onClick?.invoke()
                        }
                    }

                    // Process every pointer that went down in the initial event.
                    // awaitFirstDown() would discard additional simultaneous downs,
                    // which is exactly the q+w case from R01.
                    val initialEvent = awaitPointerEvent()
                    initialEvent.changes
                        .filter { it.pressed && !it.previousPressed }
                        .forEach { handleDown(it) }
                    try {
                        while (tracker.activePointerCount > 0) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                val known = tracker.contains(change.id.value)
                                when {
                                    !known && change.pressed -> handleDown(change)
                                    known && !change.pressed -> handleUp(change)
                                    known -> handleMove(change)
                                }
                                if (change.pressed != change.previousPressed ||
                                    change.position != change.previousPosition
                                ) {
                                    change.consume()
                                }
                            }
                        }
                    } finally {
                        repeatJobs.values.forEach { it.cancel() }
                        repeatJobs.clear()
                        tracker.clear()
                        pressedIndices = emptySet()
                    }
                }
            },
    ) {
        keys.forEachIndexed { index, key ->
            KeyButton(
                key = key,
                modifier = Modifier.weight(key.weight),
                isPressed = index in pressedIndices,
                onPress = key.onClick,
            )
        }
    }
}

@Composable
private fun KeyButton(
    key: KeyCap,
    modifier: Modifier = Modifier,
    isPressed: Boolean,
    onPress: () -> Unit,
) {
    val palette = LocalBitapPalette.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 700f),
        label = "keyScale",
    )
    val shape = RoundedCornerShape(if (palette.isGlass) 12.dp else 10.dp)
    val restBackground = when {
        key.isActive -> palette.keyPressed
        key.isFunction -> palette.functionKey
        else -> palette.key
    }
    Box(modifier = modifier.zIndex(if (isPressed) 2f else 0f)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 3.dp)
                .heightIn(min = LocalKeyboardKeyHeight.current)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(if (isPressed) palette.keyPressed else restBackground)
                .border(0.5.dp, palette.hairline, shape)
                .semantics {
                    contentDescription = key.description
                    role = Role.Button
                    onClick {
                        onPress()
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = key.label,
                color = if (key.isActive) palette.accent else palette.textPrimary,
                fontSize = key.fontSize,
                fontWeight = if (key.isFunction) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
        }
        if (isPressed && key.showPressPreview) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-52).dp)
                    .zIndex(3f)
                    .width(48.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.elevatedSurface)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                    .semantics { contentDescription = "按键预览 " + key.label },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = key.label,
                    color = palette.textPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LetterLayout(
    state: KeyboardUiState,
    press: (KeyboardAction) -> Unit,
) {
    val upper = state.shift != ShiftState.Off
    val shiftDescription = when (state.shift) {
        ShiftState.Off -> "切换到大写"
        ShiftState.Shifted -> "开启大写锁定"
        ShiftState.Locked -> "关闭大写锁定"
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
        verticalArrangement = Arrangement.spacedBy(LocalKeyboardRowGap.current),
    ) {
        KeyRow(keys = QwertyRows[0].map { letter -> letterKey(letter, upper, press) })
        KeyRow(
            keys = QwertyRows[1].map { letter -> letterKey(letter, upper, press) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        KeyRow(
            keys = buildList {
                add(
                    KeyCap(
                        label = if (state.shift == ShiftState.Locked) "⇪" else "⇧",
                        description = shiftDescription,
                        weight = 1.5f,
                        isFunction = true,
                        isActive = state.shift != ShiftState.Off,
                        fontSize = 18.sp,
                        onClick = { press(KeyboardAction.ShiftTapped(System.currentTimeMillis())) },
                    ),
                )
                QwertyRows[2].forEach { letter -> add(letterKey(letter, upper, press)) }
                add(
                    KeyCap(
                        label = "⌫",
                        description = "删除",
                        weight = 1.5f,
                        isFunction = true,
                        fontSize = 20.sp,
                        longPressRepeat = { press(KeyboardAction.Backspace) },
                        onClick = { press(KeyboardAction.Backspace) },
                    ),
                )
            },
        )
    }
}

private fun letterKey(
    letter: String,
    upper: Boolean,
    press: (KeyboardAction) -> Unit,
): KeyCap = KeyCap(
    label = if (upper) letter.uppercase() else letter,
    description = "字母 " + letter.uppercase(),
    showPressPreview = true,
    onClick = { press(KeyboardAction.AppendInput(letter)) },
)

@Composable
private fun SymbolLayout(
    rows: List<List<String>>,
    bottomRow: List<String>,
    switchLabel: String,
    switchDescription: String,
    nextLayer: KeyboardLayer,
    press: (KeyboardAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
        verticalArrangement = Arrangement.spacedBy(LocalKeyboardRowGap.current),
    ) {
        rows.forEachIndexed { index, row ->
            KeyRow(
                keys = row.map { symbol ->
                    KeyCap(
                        label = symbol,
                        description = "符号 " + symbol,
                        fontSize = 18.sp,
                        onClick = { press(KeyboardAction.AppendInput(symbol)) },
                    )
                },
                modifier = Modifier.padding(horizontal = if (index == 1) 6.dp else 0.dp),
            )
        }
        KeyRow(
            keys = buildList {
                add(
                    KeyCap(
                        label = switchLabel,
                        description = switchDescription,
                        weight = 1.4f,
                        isFunction = true,
                        fontSize = 14.sp,
                        onClick = { press(KeyboardAction.ShowLayer(nextLayer)) },
                    ),
                )
                bottomRow.forEach { symbol ->
                    val displayLabel = if (symbol.isBlank()) "空格" else symbol
                    add(
                        KeyCap(
                            label = displayLabel,
                            description = "符号 " + displayLabel,
                            fontSize = 18.sp,
                            onClick = { press(KeyboardAction.AppendInput(symbol)) },
                        ),
                    )
                }
                add(
                    KeyCap(
                        label = "⌫",
                        description = "删除",
                        weight = 1.4f,
                        isFunction = true,
                        fontSize = 20.sp,
                        longPressRepeat = { press(KeyboardAction.Backspace) },
                        onClick = { press(KeyboardAction.Backspace) },
                    ),
                )
            },
        )
    }
}

/**
 * Number/Decimal/Signed/Phone layouts use only the digits plus the editor's
 * declared numeric symbols (see KeyboardUiState.numericSymbols). The switch and
 * delete keys are shared with SymbolLayout, so the fixed key height/row gap stay
 * identical to every other layer.
 */
/**
 * Numeric/phone/date-time layout. It intentionally has no path to the generic
 * alphabet or symbol layers: those keys are rejected by many numeric editors.
 * Decimal and signed flags are independent in the core model, so both "+"/"-"
 * and "." appear together when both capabilities are declared.
 */
@Composable
private fun NumericLayout(state: KeyboardUiState, press: (KeyboardAction) -> Unit) {
    val symbols = state.numericSymbols
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
        verticalArrangement = Arrangement.spacedBy(LocalKeyboardRowGap.current),
    ) {
        KeyRow(
            keys = NumberRows[0].map { digit ->
                KeyCap(
                    label = digit,
                    description = "数字 " + digit,
                    weight = 1f,
                    fontSize = 20.sp,
                    onClick = { press(KeyboardAction.AppendInput(digit)) },
                )
            },
        )
        KeyRow(
            keys = buildList {
                symbols.forEach { symbol ->
                    val displayLabel = if (symbol.isBlank()) "空格" else symbol
                    add(
                        KeyCap(
                            label = displayLabel,
                            description = "符号 " + displayLabel,
                            weight = 1f,
                            fontSize = 18.sp,
                            onClick = { press(KeyboardAction.AppendInput(symbol)) },
                        ),
                    )
                }
                add(
                    KeyCap(
                        label = "⌫",
                        description = "删除",
                        weight = 1.4f,
                        isFunction = true,
                        fontSize = 20.sp,
                        longPressRepeat = { press(KeyboardAction.Backspace) },
                        onClick = { press(KeyboardAction.Backspace) },
                    ),
                )
            },
        )
    }
}

/**
 * Dedicated editing-tools keyboard page. It replaces the letter/symbol rows
 * instead of adding a transient row, so the key area keeps a stable height and
 * the edit entry lives in the candidate bar between AI and settings.
 */
@Composable
private fun EditingToolsPage(press: (KeyboardAction) -> Unit) {
    val palette = LocalBitapPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "编辑工具",
            color = palette.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
        CompositionLocalProvider(LocalKeyboardKeyHeight provides 48.dp) {
            KeyRow(
                keys = editingTools().take(4).map { tool ->
                    KeyCap(
                        label = tool.label,
                        description = tool.description,
                        weight = 1f,
                        isFunction = true,
                        fontSize = 15.sp,
                        onClick = { press(tool.action) },
                    )
                },
                modifier = Modifier.padding(horizontal = 3.dp),
            )
            KeyRow(
                keys = editingTools().drop(4).map { tool ->
                    KeyCap(
                        label = tool.label,
                        description = tool.description,
                        weight = 1f,
                        isFunction = true,
                        fontSize = 15.sp,
                        onClick = { press(tool.action) },
                    )
                },
                modifier = Modifier.padding(horizontal = 3.dp),
            )
            KeyRow(
                keys = editingHistoryTools().map { tool ->
                    KeyCap(
                        label = tool.label,
                        description = tool.description,
                        weight = 1f,
                        isFunction = true,
                        fontSize = 15.sp,
                        onClick = { press(tool.action) },
                    )
                },
                modifier = Modifier.padding(horizontal = 3.dp),
            )
        }
        Text(
            text = "提示：空格下方长按并左右滑动，可移动光标",
            color = palette.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Invisible interaction pad in the blank area below the spacebar.
 *
 * Long-press first, then slide horizontally: every 16 dp becomes one cursor
 * move. The pad only exists in portrait/expanded-height mode; it never takes
 * horizontal space from the spacebar row or shifts the key geometry when the
 * height setting changes.
 */
@Composable
private fun SpaceCursorPad(
    press: (KeyboardAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { CURSOR_DRAG_STEP_DP.dp.toPx() }
    var accumulated by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier
            .fillMaxWidth(0.38f)
            .heightIn(min = 16.dp)
            .semantics { contentDescription = SPACE_CURSOR_HINT }
            .pointerInput(thresholdPx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { accumulated = 0f },
                    onDragEnd = { accumulated = 0f },
                    onDragCancel = { accumulated = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount.x
                        val steps = cursorStepsForDrag(accumulated, thresholdPx)
                        if (steps != 0) {
                            accumulated -= steps * thresholdPx
                            val direction = if (steps > 0) 1 else -1
                            repeat(if (steps > 0) steps else -steps) {
                                press(KeyboardAction.MoveCursor(direction))
                            }
                        }
                    },
                )
            },
    )
}

@Composable
private fun FunctionRow(state: KeyboardUiState, press: (KeyboardAction) -> Unit) {
    if (state.numericMode != NumericInputMode.None) {
        // Numeric editors must not expose alphabet/symbol layer entries that the
        // editor may reject. NumericLayout already contains digits, its own
        // symbols and backspace; the bottom row only carries the enter action.
        KeyRow(
            keys = listOf(
                KeyCap(
                    label = state.enterKeyLabel,
                    description = "回车键：" + state.enterKeyLabel,
                    weight = 1f,
                    isFunction = true,
                    fontSize = when {
                        state.enterKeyLabel.length >= 3 -> 12.sp
                        state.enterKeyLabel.length == 2 -> 14.sp
                        else -> 20.sp
                    },
                    onClick = { press(KeyboardAction.Enter) },
                ),
            ),
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
        return
    }
    val chinese = state.language == InputLanguage.Chinese
    val layerLabel = when (state.layer) {
        KeyboardLayer.Letters -> "123"
        KeyboardLayer.Numbers -> "#+="
        KeyboardLayer.Symbols -> "ABC"
    }
    val nextLayer = when (state.layer) {
        KeyboardLayer.Letters -> KeyboardLayer.Numbers
        KeyboardLayer.Numbers -> KeyboardLayer.Symbols
        KeyboardLayer.Symbols -> KeyboardLayer.Letters
    }
    val emailMode = state.editorInfo.kind == EditorInputKind.Email
    val showPunctuation = state.numericMode == NumericInputMode.None
    val punctuation = if (emailMode) "@" else if (chinese) "，" else ","
    val punctuationDescription = if (emailMode) "at 符号" else "逗号（长按输入拼音分隔符）"
    val period = if (chinese) "。" else "."
    KeyRow(
        keys = buildList {
            add(
                KeyCap(
                    label = layerLabel,
                    description = if (state.layer == KeyboardLayer.Symbols) "切换到字母键盘" else "切换到符号键盘",
                    weight = 1.1f,
                    isFunction = true,
                    fontSize = 14.sp,
                    onClick = { press(KeyboardAction.ShowLayer(nextLayer)) },
                ),
            )
            if (state.showLanguageToggle) {
                add(
                    KeyCap(
                        label = "中/英",
                        description = "切换中英文",
                        weight = 1f,
                        isFunction = true,
                        fontSize = 12.sp,
                        onClick = { press(KeyboardAction.ToggleLanguage) },
                    ),
                )
            }
            if (showPunctuation) {
                add(
                    KeyCap(
                        label = punctuation,
                        description = punctuationDescription,
                        weight = 0.9f,
                        fontSize = 18.sp,
                        longPressAction = if (emailMode) null else {
                            { press(KeyboardAction.AppendInput("'")) }
                        },
                        onClick = { press(KeyboardAction.AppendInput(punctuation)) },
                    ),
                )
            }
            add(
                KeyCap(
                    label = "空格",
                    description = "空格",
                    weight = 4.2f,
                    isFunction = true,
                    fontSize = 14.sp,
                    onClick = { press(KeyboardAction.Space) },
                ),
            )
            if (showPunctuation) {
                add(
                    KeyCap(
                        label = period,
                        description = "句号",
                        weight = 0.9f,
                        fontSize = 18.sp,
                        onClick = { press(KeyboardAction.AppendInput(period)) },
                    ),
                )
            }
            add(
                KeyCap(
                    label = state.enterKeyLabel,
                    description = "回车键：" + state.enterKeyLabel,
                    weight = 1.2f,
                    isFunction = true,
                    fontSize = when {
                        state.enterKeyLabel.length >= 3 -> 12.sp
                        state.enterKeyLabel.length == 2 -> 14.sp
                        else -> 20.sp
                    },
                    onClick = { press(KeyboardAction.Enter) },
                ),
            )
        },
        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
    )
}

private val QwertyRows: List<List<String>> = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m"),
)

private val NumberRows: List<List<String>> = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")"),
)

private val SymbolRows: List<List<String>> = listOf(
    listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
    listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "·"),
)
