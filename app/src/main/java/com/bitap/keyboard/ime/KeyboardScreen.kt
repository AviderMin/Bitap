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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitap.keyboard.ai.RewriteTone
import com.bitap.keyboard.core.AiPanelUiState
import com.bitap.keyboard.core.AiStatus
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
@Composable
fun KeyboardScreen(
    state: KeyboardUiState,
    onAction: (KeyboardAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBitapPalette.current
    val view = LocalView.current
    val haptic = {
        if (state.hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
    val press: (KeyboardAction) -> Unit = { action ->
        haptic()
        onAction(action)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(vertical = 4.dp),
    ) {
        CandidateBar(state = state, onAction = onAction, haptic = haptic)
        AiPanel(panel = state.ai, onAction = onAction, haptic = haptic)
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

@Composable
private fun CandidateBar(
    state: KeyboardUiState,
    onAction: (KeyboardAction) -> Unit,
    haptic: () -> Unit,
) {
    val palette = LocalBitapPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.composing.isNotEmpty() || state.candidates.isNotEmpty()) {
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
            }
        } else {
            Text(
                text = if (state.language == InputLanguage.Chinese) "拼音候选" else "英文输入",
                color = palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
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
                        onAction(KeyboardAction.AiPanelOpened())
                    }
                    .semantics {
                        contentDescription = if (ready) "AI 改写" else "AI 改写未就绪，点击查看提示"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✦", color = if (ready) palette.accent else palette.textSecondary, fontSize = 20.sp)
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

@Composable
private fun KeyButton(
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    isFunction: Boolean = false,
    isActive: Boolean = false,
    fontSize: TextUnit = 20.sp,
    onClick: () -> Unit,
) {
    val palette = LocalBitapPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 700f),
        label = "keyScale",
    )
    val shape = RoundedCornerShape(if (palette.isGlass) 12.dp else 10.dp)
    val background = when {
        isActive -> palette.keyPressed
        isFunction -> palette.functionKey
        else -> palette.key
    }
    Box(
        modifier = modifier
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .heightIn(min = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(background)
            .border(0.5.dp, palette.hairline, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (isActive) palette.accent else palette.textPrimary,
            fontSize = fontSize,
            fontWeight = if (isFunction) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun LetterLayout(
    state: KeyboardUiState,
    press: (KeyboardAction) -> Unit,
) {
    val upper = state.shift != ShiftState.Off
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            QwertyRows[0].forEach { letter ->
                KeyButton(
                    label = if (upper) letter.uppercase() else letter,
                    description = "字母 " + letter.uppercase(),
                    modifier = Modifier.weight(1f),
                    onClick = { press(KeyboardAction.AppendInput(letter)) },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            QwertyRows[1].forEach { letter ->
                KeyButton(
                    label = if (upper) letter.uppercase() else letter,
                    description = "字母 " + letter.uppercase(),
                    modifier = Modifier.weight(1f),
                    onClick = { press(KeyboardAction.AppendInput(letter)) },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            val shiftDescription = when (state.shift) {
                ShiftState.Off -> "切换到大写"
                ShiftState.Shifted -> "开启大写锁定"
                ShiftState.Locked -> "关闭大写锁定"
            }
            KeyButton(
                label = if (state.shift == ShiftState.Locked) "⇪" else "⇧",
                description = shiftDescription,
                modifier = Modifier.weight(1.5f),
                isFunction = true,
                isActive = state.shift != ShiftState.Off,
                fontSize = 18.sp,
                onClick = { press(KeyboardAction.ShiftTapped(System.currentTimeMillis())) },
            )
            QwertyRows[2].forEach { letter ->
                KeyButton(
                    label = if (upper) letter.uppercase() else letter,
                    description = "字母 " + letter.uppercase(),
                    modifier = Modifier.weight(1f),
                    onClick = { press(KeyboardAction.AppendInput(letter)) },
                )
            }
            KeyButton(
                label = "⌫",
                description = "删除",
                modifier = Modifier.weight(1.5f),
                isFunction = true,
                fontSize = 20.sp,
                onClick = { press(KeyboardAction.Backspace) },
            )
        }
    }
}

@Composable
private fun SymbolLayout(
    rows: List<List<String>>,
    bottomRow: List<String>,
    switchLabel: String,
    switchDescription: String,
    nextLayer: KeyboardLayer,
    press: (KeyboardAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (index == 1) 6.dp else 0.dp),
            ) {
                row.forEach { symbol ->
                    KeyButton(
                        label = symbol,
                        description = "符号 " + symbol,
                        modifier = Modifier.weight(1f),
                        fontSize = 18.sp,
                        onClick = { press(KeyboardAction.AppendInput(symbol)) },
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            KeyButton(
                label = switchLabel,
                description = switchDescription,
                modifier = Modifier.weight(1.4f),
                isFunction = true,
                fontSize = 14.sp,
                onClick = { press(KeyboardAction.ShowLayer(nextLayer)) },
            )
            bottomRow.forEach { symbol ->
                KeyButton(
                    label = symbol,
                    description = "符号 " + symbol,
                    modifier = Modifier.weight(1f),
                    fontSize = 18.sp,
                    onClick = { press(KeyboardAction.AppendInput(symbol)) },
                )
            }
            KeyButton(
                label = "⌫",
                description = "删除",
                modifier = Modifier.weight(1.4f),
                isFunction = true,
                fontSize = 20.sp,
                onClick = { press(KeyboardAction.Backspace) },
            )
        }
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
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 1.dp)) {
        KeyButton(
            label = layerLabel,
            description = if (state.layer == KeyboardLayer.Symbols) "切换到字母键盘" else "切换到符号键盘",
            modifier = Modifier.weight(1.2f),
            isFunction = true,
            fontSize = 14.sp,
            onClick = { press(KeyboardAction.ShowLayer(nextLayer)) },
        )
        KeyButton(
            label = if (chinese) "英" else "中",
            description = if (chinese) "切换到英文" else "切换到中文",
            modifier = Modifier.weight(1f),
            isFunction = true,
            fontSize = 16.sp,
            onClick = { press(KeyboardAction.ToggleLanguage) },
        )
        KeyButton(
            label = comma,
            description = "逗号",
            modifier = Modifier.weight(1f),
            fontSize = 18.sp,
            onClick = { press(KeyboardAction.AppendInput(comma)) },
        )
        KeyButton(
            label = "空格",
            description = "空格",
            modifier = Modifier.weight(3.4f),
            isFunction = true,
            fontSize = 14.sp,
            onClick = { press(KeyboardAction.Space) },
        )
        KeyButton(
            label = period,
            description = "句号",
            modifier = Modifier.weight(1f),
            fontSize = 18.sp,
            onClick = { press(KeyboardAction.AppendInput(period)) },
        )
        KeyButton(
            label = "↵",
            description = "回车",
            modifier = Modifier.weight(1.2f),
            isFunction = true,
            fontSize = 20.sp,
            onClick = { press(KeyboardAction.Enter) },
        )
    }
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
