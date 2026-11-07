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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.bitap.keyboard.core.EditorInputKind
import com.bitap.keyboard.core.InputLanguage
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardLayer
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

internal fun candidatePanelMaxHeightDp(compact: Boolean): Int = if (compact) 120 else 220

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
    // T06: in landscape/tiny windows the classic 54 dp rows + candidate strips
    // cover the editor. Compact mode keeps the row hit target at 48 dp
    // (42 dp keycap + 6 dp vertical padding) while reducing paddings, removing
    // the decorative dock line and collapsing candidate extras to one row.
    val compact = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenHeightDp < 480
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
                .padding(top = if (compact) 2.dp else 5.dp, bottom = if (compact) 2.dp else 4.dp + bottomLift),
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
                onAction = onAction,
                haptic = haptic,
                onOpenSettings = onOpenSettings,
                compact = compact,
                candidatesExpanded = candidatesExpanded,
                onCandidatesExpandedChange = { candidatesExpanded = it },
                candidatePage = candidatePage,
                onCandidatePageChange = { candidatePage = it },
            )
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
        }
        // P0 landscape fix: the expanded panel is an overlay anchored above the
        // fixed bottom function row. It never adds layout height, so the key area
        // and the bottom row stay on screen and the IME window does not resize.
        if (usesCandidatePanelOverlay(compact)) {
            CandidatePanelOverlay(
                state = state,
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
    val expandedPageSize = if (compact) 3 else 6
    val candidateVersion = state.candidates.firstOrNull()?.word
    // R11: a new composition/result set starts at the first candidate instead of
    // leaving the previous scroll position at the end of the old result list.
    LaunchedEffect(state.composing, state.candidatesFor, candidateVersion, state.candidates.size) {
        onCandidatePageChange(0)
        candidateScrollState.scrollTo(0)
        if (state.candidates.isEmpty()) onCandidatesExpandedChange(false)
    }
    // Collapse the expanded candidate page whenever the composition changes.
    LaunchedEffect(state.composing) {
        onCandidatesExpandedChange(false)
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
        if (state.composing.isNotEmpty() || state.candidates.isNotEmpty() || state.aiCandidates.isNotEmpty()) {
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
                if (state.candidates.isEmpty() && state.composing.isNotEmpty()) {
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
                    state.candidates.forEachIndexed { index, candidate ->
                        CandidateChip(
                            label = candidate.word,
                            description = if (candidate.isCorrection) {
                                "纠错候选 " + candidate.word
                            } else {
                                "候选词 " + candidate.word
                            },
                            accent = index == 0,
                            isCorrection = candidate.isCorrection,
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
        if (state.candidates.isNotEmpty()) {
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
                visible = candidatesExpanded && state.candidates.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                CandidateExpandedPanel(
                    candidates = state.candidates,
                    page = candidatePage,
                    pageSize = expandedPageSize,
                    compact = compact,
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
}

@Composable
private fun CandidateExpandedPanel(
    candidates: List<Candidate>,
    page: Int,
    pageSize: Int,
    compact: Boolean = false,
    onPageChange: (Int) -> Unit,
    onCandidateClick: (Int) -> Unit,
    haptic: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val palette = LocalBitapPalette.current
    val pageCount = ((candidates.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pageCount - 1)
    val pageStart = currentPage * pageSize
    val pageItems = candidates.drop(pageStart).take(pageSize)

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
            .padding(8.dp)
            .heightIn(max = candidatePanelMaxHeightDp(compact).dp)
            .verticalScroll(rememberScrollState()),
    ) {
        pageItems.forEachIndexed { itemIndex, candidate ->
            val globalIndex = pageStart + itemIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                CandidateChip(
                    modifier = Modifier.fillMaxWidth(),
                    label = candidate.word,
                    description = if (candidate.isCorrection) {
                        "纠错候选 " + candidate.word
                    } else {
                        "候选词 " + candidate.word
                    },
                    accent = globalIndex == 0,
                    maxLines = 3,
                    isCorrection = candidate.isCorrection,
                    onClick = {
                        haptic()
                        onCandidateClick(globalIndex)
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (candidates.size > pageSize) {
                    "第 " + (currentPage + 1) + " / " + pageCount + " 页"
                } else {
                    "更多候选占位 · 本地词库已全部展示"
                },
                color = palette.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
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
    expanded: Boolean,
    page: Int,
    onPageChange: (Int) -> Unit,
    onCandidateClick: (Int) -> Unit,
    onDismiss: () -> Unit,
    haptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = expanded && state.candidates.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        CandidateExpandedPanel(
            candidates = state.candidates,
            page = page,
            pageSize = 3,
            compact = true,
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
    onClick: () -> Unit,
) {
    val palette = LocalBitapPalette.current
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
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
            if (isCorrection) {
                Text(
                    text = "纠错",
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
                                tracker.recordRepeat(pointerId)
                                while (isActive) {
                                    repeat()
                                    delay(55)
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
                    weight = 4.4f,
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
