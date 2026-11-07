package com.bitap.keyboard.ime

import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.core.AiPanelUiState
import com.bitap.keyboard.core.AiStatus
import com.bitap.keyboard.core.Candidate
import com.bitap.keyboard.core.InputLanguage
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardLayer
import com.bitap.keyboard.core.KeyboardUiState
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

@Composable
fun KeyboardScreen(
    state: KeyboardUiState,
    onAction: (KeyboardAction) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalBitapPalette.current
    val view = LocalView.current
    val heightScale = state.keyboardHeightScale.coerceIn(0.85f, 1.35f)
    // Keys stay at a fixed 54 dp. The overall-height control only adds
    // breathing room between keyboard rows so touch target size never changes.
    val keyHeight = 54.dp
    val rowGap = 4.dp
    // All rows keep the same 4 dp vertical rhythm. Height customization only
    // reserves empty space at the very bottom, above the system IME shortcut bar.
    val bottomLift = (80.dp * (heightScale - 0.85f)).coerceAtLeast(0.dp)
    val haptic = {
        if (state.hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
    val press: (KeyboardAction) -> Unit = { action ->
        haptic()
        onAction(action)
    }

    CompositionLocalProvider(
        LocalKeyboardKeyHeight provides keyHeight,
        LocalKeyboardRowGap provides rowGap,
    ) {
        Column(
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
                .padding(top = 5.dp, bottom = 4.dp + bottomLift),
            verticalArrangement = Arrangement.spacedBy(rowGap),
        ) {
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
            CandidateBar(
                state = state,
                onAction = onAction,
                haptic = haptic,
                onOpenSettings = onOpenSettings,
            )
            when (state.layer) {
                KeyboardLayer.Letters -> LetterLayout(state = state, press = press)
                KeyboardLayer.Numbers -> SymbolLayout(
                    rows = NumberRows,
                    bottomRow = listOf(".", ",", "?", "!", "'", "\""),
                    switchLabel = "#+=",
                    switchDescription = "切换到更多符号",
                    nextLayer = KeyboardLayer.Symbols,
                    press = press,
                )

                KeyboardLayer.Symbols -> SymbolLayout(
                    rows = SymbolRows,
                    bottomRow = listOf(".", ",", "?", "!", ":", ";"),
                    switchLabel = "ABC",
                    switchDescription = "切换到字母键盘",
                    nextLayer = KeyboardLayer.Letters,
                    press = press,
                )
            }
            FunctionRow(state = state, press = press)
        }
    }
}

@Composable
private fun CandidateBar(
    state: KeyboardUiState,
    onAction: (KeyboardAction) -> Unit,
    haptic: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalBitapPalette.current
    var candidatesExpanded by remember { mutableStateOf(false) }
    var candidatePage by remember { mutableIntStateOf(0) }
    val expandedPageSize = 6
    LaunchedEffect(state.candidates) {
        candidatePage = 0
        if (state.candidates.isEmpty()) candidatesExpanded = false
    }
    Column(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.composing.isNotEmpty() || state.candidates.isNotEmpty() || state.aiCandidates.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                            description = "候选词 " + candidate.word,
                            accent = index == 0,
                            onClick = {
                                haptic()
                                onAction(KeyboardAction.CandidateTapped(index))
                            },
                        )
                    }
                }
                if (state.aiCandidates.isNotEmpty()) {
                    Text(
                        text = "AI",
                        color = palette.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp, end = 2.dp),
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
            }
        } else {
            Text(
                text = if (state.language == InputLanguage.Chinese) "拼音候选" else "英文输入",
                color = palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }

        if (state.candidates.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable {
                        haptic()
                        candidatesExpanded = !candidatesExpanded
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
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .alpha(if (ready) 1f else 0.45f)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable {
                        haptic()
                        onAction(KeyboardAction.RequestAiCandidates)
                    }
                    .semantics {
                        contentDescription = if (ready) "AI 词库候选" else "AI 词库未就绪，点击查看提示"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.aiCandidatesLoading) "…" else "✦ AI",
                    color = if (ready) palette.accent else palette.textSecondary,
                    fontSize = 12.sp,
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
        AnimatedVisibility(
            visible = candidatesExpanded && state.candidates.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            CandidateExpandedPanel(
                candidates = state.candidates,
                page = candidatePage,
                pageSize = expandedPageSize,
                onPageChange = { candidatePage = it },
                onCandidateClick = { index ->
                    haptic()
                    onAction(KeyboardAction.CandidateTapped(index))
                },
                haptic = haptic,
            )
        }
    }
    val aiStatus = when {
        state.aiCandidatesLoading -> "AI 词库思考中…"
        !state.aiCandidatesError.isNullOrBlank() -> state.aiCandidatesError
        else -> null
    }
    if (aiStatus != null) {
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
    onPageChange: (Int) -> Unit,
    onCandidateClick: (Int) -> Unit,
    haptic: () -> Unit,
) {
    val palette = LocalBitapPalette.current
    val pageCount = ((candidates.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pageCount - 1)
    val pageStart = currentPage * pageSize
    val pageItems = candidates.drop(pageStart).take(pageSize)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.elevatedSurface)
            .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
            .padding(8.dp)
            .heightIn(max = 196.dp),
    ) {
        pageItems.chunked(3).forEachIndexed { chunkIndex, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowItems.forEachIndexed { itemIndex, candidate ->
                    val globalIndex = pageStart + chunkIndex * 3 + itemIndex
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CandidateChip(
                            label = candidate.word,
                            description = "候选词 " + candidate.word,
                            accent = globalIndex == 0,
                            onClick = {
                                haptic()
                                onCandidateClick(globalIndex)
                            },
                        )
                    }
                }
                repeat(3 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
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
        }
    }
}

@Composable
private fun CandidateChip(
    label: String,
    description: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalBitapPalette.current
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (accent) palette.accent else palette.textPrimary,
            fontSize = 18.sp,
            fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun AiPanel(
    panel: AiPanelUiState,
    onAction: (KeyboardAction) -> Unit,
    haptic: () -> Unit,
) {
    val palette = LocalBitapPalette.current
    AnimatedVisibility(
        visible = panel.visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.elevatedSurface)
                .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .heightIn(max = 260.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "✦ AI 改写", color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.weight(1f))
                if (panel.isRunning) {
                    PanelTextButton(text = "取消", onClick = {
                        haptic()
                        onAction(KeyboardAction.AiCancel)
                    })
                }
            }
            if (panel.source.isNotEmpty()) {
                Text(
                    text = panel.source,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            if (panel.isRunning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp),
                    color = palette.accent,
                    trackColor = palette.hairline,
                )
            }
            if (panel.status != AiStatus.Unavailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RewriteTone.entries.forEach { tone ->
                        ToneChip(
                            label = tone.label,
                            selected = panel.tone == tone,
                            onClick = {
                                haptic()
                                onAction(KeyboardAction.AiToneChosen(tone))
                            },
                        )
                    }
                }
            }
            when (panel.status) {
                AiStatus.Thinking -> Text(
                    text = "本地思考中…",
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                AiStatus.Streaming, AiStatus.Completed -> {
                    if (panel.result.isNotEmpty()) {
                        Text(
                            text = panel.result,
                            color = palette.textPrimary,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                        )
                    }
                }

                AiStatus.Failed -> Text(
                    text = panel.error ?: "这次没想好",
                    color = palette.danger,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                AiStatus.Unavailable -> Text(
                    text = panel.error ?: "本地模型未就绪",
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                AiStatus.Idle -> Text(
                    text = panel.error ?: "选择一种改写方式",
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelTextButton(text = "放弃", onClick = {
                    haptic()
                    onAction(KeyboardAction.AiDismiss)
                })
                Spacer(modifier = Modifier.width(6.dp))
                PanelAccentButton(
                    text = "插入",
                    enabled = panel.canInsert,
                    onClick = {
                        haptic()
                        onAction(KeyboardAction.AiInsert)
                    },
                )
            }
        }
    }
}

@Composable
private fun ToneChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalBitapPalette.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) palette.accent else palette.functionKey)
            .border(0.5.dp, if (selected) palette.accent else palette.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = "改写动作 " + label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) palette.onAccent else palette.textPrimary,
            fontSize = 12.sp,
            maxLines = 1,
        )
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

@Composable
private fun PanelAccentButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalBitapPalette.current
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) palette.accent else palette.functionKey)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = "插入改写结果" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) palette.onAccent else palette.textSecondary.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
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
 * A row is the hit-testing unit: every pixel inside it belongs to the nearest
 * key by weight, so the 2 dp visual gaps and the 3 dp vertical padding are no
 * longer dead zones. The visual keycap stays inset and keeps the fixed 54 dp
 * height.
 *
 * Gesture contract:
 * - pointer down selects the nearest key and shows its pressed state;
 * - horizontal movement selects the nearest sibling under the finger (basic
 *   slide-out / neighbour correction);
 * - vertical movement more than 48 dp outside the row cancels the gesture;
 * - release commits exactly one key, except after a long-press repeat fired.
 */
@Composable
private fun KeyRow(
    keys: List<KeyCap>,
    modifier: Modifier = Modifier,
) {
    var pressedIndex by remember { mutableIntStateOf(-1) }
    val currentKeys = rememberUpdatedState(keys)
    val pointerScope = rememberCoroutineScope()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val rowKeys = currentKeys.value
                    val rowWidth = size.width.toFloat()
                    var activeIndex = rowKeyIndexAt(down.position.x, rowWidth, rowKeys)
                    if (activeIndex < 0) return@awaitEachGesture
                    pressedIndex = activeIndex

                    var repeatJob: Job? = null
                    var repeated = false

                    fun stopRepeat() {
                        repeatJob?.cancel()
                        repeatJob = null
                        repeated = false
                    }

                    fun startRepeat(index: Int) {
                        stopRepeat()
                        val repeat = rowKeys.getOrNull(index)?.longPressRepeat ?: return
                        repeatJob = pointerScope.launch {
                            delay(400)
                            repeated = true
                            while (isActive) {
                                repeat()
                                delay(55)
                            }
                        }
                    }

                    startRepeat(activeIndex)

                    var released = false
                    var cancelled = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                released = true
                                break
                            }
                            val outsideRow = change.position.y < -48.dp.toPx() ||
                                change.position.y > size.height + 48.dp.toPx()
                            if (outsideRow) {
                                if (!cancelled) {
                                    cancelled = true
                                    stopRepeat()
                                    pressedIndex = -1
                                }
                            } else {
                                if (cancelled) {
                                    cancelled = false
                                    startRepeat(activeIndex)
                                }
                                val nextIndex = rowKeyIndexAt(change.position.x, rowWidth, rowKeys)
                                if (nextIndex != activeIndex && nextIndex >= 0) {
                                    activeIndex = nextIndex
                                    pressedIndex = nextIndex
                                    startRepeat(nextIndex)
                                }
                            }
                            change.consume()
                        }
                    } finally {
                        stopRepeat()
                        pressedIndex = -1
                    }
                    if (released && !cancelled && !repeated) {
                        rowKeys.getOrNull(activeIndex)?.onClick?.invoke()
                    }
                }
            },
    ) {
        keys.forEachIndexed { index, key ->
            KeyButton(
                key = key,
                modifier = Modifier.weight(key.weight),
                isPressed = pressedIndex == index,
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
                    add(
                        KeyCap(
                            label = symbol,
                            description = "符号 " + symbol,
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
    val comma = if (chinese) "，" else ","
    val period = if (chinese) "。" else "."
    KeyRow(
        keys = listOf(
            KeyCap(
                label = layerLabel,
                description = if (state.layer == KeyboardLayer.Symbols) "切换到字母键盘" else "切换到符号键盘",
                weight = 1.1f,
                isFunction = true,
                fontSize = 14.sp,
                onClick = { press(KeyboardAction.ShowLayer(nextLayer)) },
            ),
            KeyCap(
                label = "中/英",
                description = "切换中英文",
                weight = 1f,
                isFunction = true,
                fontSize = 12.sp,
                onClick = { press(KeyboardAction.ToggleLanguage) },
            ),
            KeyCap(
                label = comma,
                description = "逗号",
                weight = 0.9f,
                fontSize = 18.sp,
                onClick = { press(KeyboardAction.AppendInput(comma)) },
            ),
            KeyCap(
                label = "空格",
                description = "空格",
                weight = 4.4f,
                isFunction = true,
                fontSize = 14.sp,
                onClick = { press(KeyboardAction.Space) },
            ),
            KeyCap(
                label = period,
                description = "句号",
                weight = 0.9f,
                fontSize = 18.sp,
                onClick = { press(KeyboardAction.AppendInput(period)) },
            ),
            KeyCap(
                label = "↵",
                description = "回车",
                weight = 1.2f,
                isFunction = true,
                fontSize = 20.sp,
                onClick = { press(KeyboardAction.Enter) },
            ),
        ),
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
