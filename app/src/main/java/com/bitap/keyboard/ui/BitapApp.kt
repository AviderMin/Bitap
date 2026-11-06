package com.bitap.keyboard.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitap.keyboard.ai.AiModelInfo
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.AiRuntimeState
import com.bitap.keyboard.settings.BitapSettings
import com.bitap.keyboard.settings.DarkMode
import com.bitap.keyboard.settings.SettingsRepository
import com.bitap.keyboard.settings.ThemeFamily
import com.bitap.keyboard.ui.theme.BitapTheme
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Bitap home / settings. One calm page: brand, three setup steps, appearance,
 * input preferences, local model management and the privacy promise.
 *
 * Theme is applied by the caller through [BitapTheme]; this screen only consumes
 * [BitapTheme.palette] plus the matching Miuix/Glasmio material components.
 */
@Composable
fun BitapApp(repository: SettingsRepository, aiService: AiRewriteService) {
    val settings by repository.settings.collectAsStateWithLifecycle()
    val modelInfo by aiService.modelInfo.collectAsStateWithLifecycle()
    val runtimeState by aiService.runtimeState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val palette = BitapTheme.palette
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.darkMode) {
        DarkMode.System -> systemDark
        DarkMode.Light -> false
        DarkMode.Dark -> true
    }
    val scope = rememberCoroutineScope()

    var imeEnabled by remember { mutableStateOf(isBitapImeEnabled(context)) }
    var imeSelected by remember { mutableStateOf(isBitapImeSelected(context)) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Keep system bar icons readable when the in-app theme differs from the system theme.
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                importing = true
                importError = null
                val result = try {
                    aiService.importModel(uri)
                } catch (t: Throwable) {
                    Result.failure<AiModelInfo>(t)
                }
                importing = false
                result.onFailure { importError = it.message ?: "导入失败，请重试" }
            }
        }
    }

    LaunchedEffect(Unit) { aiService.refresh() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        imeEnabled = isBitapImeEnabled(context)
        imeSelected = isBitapImeSelected(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (palette.isGlass) Color.Transparent else palette.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 36.dp),
        ) {
            item { BrandHeader(darkTheme = darkTheme) }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ChecklistSection(
                        imeEnabled = imeEnabled,
                        imeSelected = imeSelected,
                        modelInfo = modelInfo,
                        importing = importing,
                        importError = importError,
                        onOpenImeSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        onShowImePicker = {
                            val imm = context.getSystemService(InputMethodManager::class.java)
                            imm?.showInputMethodPicker()
                        },
                        onImportModel = { importLauncher.launch(arrayOf("*/*")) },
                    )
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AppearanceSection(
                        settings = settings,
                        onThemeFamily = repository::setThemeFamily,
                        onDarkMode = repository::setDarkMode,
                    )
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    InputSection(
                        settings = settings,
                        onAiEnabled = {
                            repository.setAiEnabled(it)
                            if (!it) aiService.cancel()
                        },
                        onHaptics = repository::setHapticsEnabled,
                        onSound = repository::setSoundEnabled,
                    )
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ModelSection(
                        modelInfo = modelInfo,
                        runtimeState = runtimeState,
                        importing = importing,
                        importError = importError,
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onDelete = { showDeleteDialog = true },
                    )
                }
            }

            item { PrivacyCard() }

            item {
                Text(
                    text = "Bitap 0.1 · 本地优先",
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除本地模型？") },
            text = { Text("删除后 AI 改写不可用，可以随时重新导入 .litertlm 文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        aiService.deleteModel()
                        importError = null
                    },
                ) {
                    Text(text = "删除", color = palette.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = "取消", color = palette.accent)
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun BrandHeader(darkTheme: Boolean) {
    val palette = BitapTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            palette.accent,
                            if (darkTheme) Color(0xFF8B5CF6) else Color(0xFF7C5CFF),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = "Bitap",
                color = palette.textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "轻触恰到好处",
                color = palette.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ChecklistSection(
    imeEnabled: Boolean,
    imeSelected: Boolean,
    modelInfo: AiModelInfo,
    importing: Boolean,
    importError: String?,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onImportModel: () -> Unit,
) {
    val palette = BitapTheme.palette
    BitapSectionTitle(
        title = "开始使用",
        subtitle = "三步完成；没有完成的步骤不会阻塞其他步骤",
    )
    BitapCard {
        StepRow(
            index = 1,
            title = "启用 Bitap 键盘",
            description = if (imeEnabled) "系统已启用 Bitap 键盘" else "打开系统输入法设置并启用 Bitap",
            done = imeEnabled,
            actionLabel = "去启用",
            onAction = onOpenImeSettings,
        )
        BitapDivider()
        StepRow(
            index = 2,
            title = "切换到 Bitap",
            description = if (imeSelected) "当前输入法已是 Bitap" else "在系统输入法选择器中选择 Bitap",
            done = imeSelected,
            actionLabel = "去切换",
            onAction = onShowImePicker,
        )
        BitapDivider()
        StepRow(
            index = 3,
            title = "导入本地模型（可选）",
            description = if (modelInfo.installed) {
                "${modelInfo.displayName} · ${formatBytes(modelInfo.sizeBytes)}"
            } else {
                "选择 .litertlm 文件，模型只保存在本机"
            },
            done = modelInfo.installed,
            actionLabel = "选择文件",
            onAction = onImportModel,
        )
        if (importing) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = palette.accent,
            )
            Text(
                text = "正在导入模型…请保持页面开启",
                color = palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (importError != null) {
            Text(
                text = importError,
                color = palette.danger,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AppearanceSection(
    settings: BitapSettings,
    onThemeFamily: (ThemeFamily) -> Unit,
    onDarkMode: (DarkMode) -> Unit,
) {
    val palette = BitapTheme.palette
    BitapSectionTitle(
        title = "外观",
        subtitle = "切换主题只改变视觉 token，不影响输入状态",
    )
    BitapCard {
        Text(
            text = "主题",
            color = palette.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThemeChoiceCard(
                family = ThemeFamily.Miuix,
                selected = settings.themeFamily == ThemeFamily.Miuix,
                modifier = Modifier.weight(1f),
                onClick = { onThemeFamily(ThemeFamily.Miuix) },
            )
            ThemeChoiceCard(
                family = ThemeFamily.Glasmio,
                selected = settings.themeFamily == ThemeFamily.Glasmio,
                modifier = Modifier.weight(1f),
                onClick = { onThemeFamily(ThemeFamily.Glasmio) },
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "深色模式",
            color = palette.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        DarkModeSelector(current = settings.darkMode, onSelect = onDarkMode)
    }
}

@Composable
private fun InputSection(
    settings: BitapSettings,
    onAiEnabled: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onSound: (Boolean) -> Unit,
) {
    BitapSectionTitle(
        title = "输入与 AI",
        subtitle = "AI 永不自动触发；每次改写都由你点击开始",
    )
    BitapCard {
        BitapSettingRow(
            title = "本地 AI 改写",
            description = "仅在点击候选条 ✦ 时调用本地模型",
            trailing = { BitapSwitch(checked = settings.aiEnabled, onCheckedChange = onAiEnabled) },
        )
        BitapDivider()
        BitapSettingRow(
            title = "按键触觉反馈",
            description = "按键时短促振动，可在系统设置统一关闭",
            trailing = { BitapSwitch(checked = settings.hapticsEnabled, onCheckedChange = onHaptics) },
        )
        BitapDivider()
        BitapSettingRow(
            title = "按键声音",
            description = "默认关闭，保持安静",
            trailing = { BitapSwitch(checked = settings.soundEnabled, onCheckedChange = onSound) },
        )
    }
}

@Composable
private fun ModelSection(
    modelInfo: AiModelInfo,
    runtimeState: AiRuntimeState,
    importing: Boolean,
    importError: String?,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = BitapTheme.palette
    BitapSectionTitle(
        title = "本地模型",
        subtitle = "支持 .litertlm，导入后存放在应用私有目录，可随时删除",
    )
    BitapCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(palette.functionKey),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SmartToy,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(23.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modelInfo.displayName,
                    color = palette.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (modelInfo.installed) {
                        "${formatBytes(modelInfo.sizeBytes)} · ${modelStatusLabel(modelInfo, runtimeState)}"
                    } else {
                        "未安装 · ${modelStatusLabel(modelInfo, runtimeState)}"
                    },
                    color = if (modelInfo.error != null || runtimeState == AiRuntimeState.Error) {
                        palette.danger
                    } else {
                        palette.textSecondary
                    },
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            StatusPill(modelInfo = modelInfo, runtimeState = runtimeState)
        }

        if (importing) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = palette.accent,
            )
            Text(
                text = "正在写入应用私有目录…",
                color = palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        val errorText = importError ?: modelInfo.error
        if (errorText != null) {
            Text(
                text = errorText,
                color = palette.danger,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BitapButton(
                text = if (modelInfo.installed) "重新导入" else "导入模型",
                onClick = onImport,
                modifier = Modifier.weight(1f),
                enabled = !importing,
                compact = true,
            )
            if (modelInfo.installed) {
                BitapTextButton(
                    text = "删除",
                    onClick = onDelete,
                    enabled = !importing,
                    danger = true,
                )
            }
        }
    }
}

@Composable
private fun PrivacyCard() {
    val palette = BitapTheme.palette
    BitapSectionTitle(title = "隐私")
    BitapCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "无网络权限 · 数据不出设备",
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "Bitap 不申请 INTERNET 权限，不接入分析或广告 SDK，不读取剪贴板。输入内容与模型都留在本机，AI 只在你在键盘上点击 ✦ 时运行。",
            color = palette.textSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Small building blocks
// ---------------------------------------------------------------------------

@Composable
private fun StepRow(
    index: Int,
    title: String,
    description: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val palette = BitapTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (done) palette.accent else palette.functionKey),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = palette.onAccent,
                    modifier = Modifier.size(17.dp),
                )
            } else {
                Text(
                    text = index.toString(),
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                color = palette.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (done) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "已完成",
                tint = palette.accent,
                modifier = Modifier.size(22.dp),
            )
        } else {
            BitapTextButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
private fun ThemeChoiceCard(
    family: ThemeFamily,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = BitapTheme.palette
    val isMiuix = family == ThemeFamily.Miuix
    val shape = RoundedCornerShape(20.dp)
    val borderColor = if (selected) palette.accent else palette.hairline
    Box(
        modifier = modifier
            .height(150.dp)
            .clip(shape)
            .background(
                if (isMiuix) {
                    Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF0F0F5)))
                } else {
                    Brush.linearGradient(
                        listOf(Color(0xFFDDEBFF), Color(0xFFEDDCFF), Color(0xFFD8F4FF)),
                    )
                },
            )
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isMiuix) "Miuix" else "Glasmio",
                    color = Color(0xFF0B0B0F),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "已选择",
                        tint = palette.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = if (isMiuix) "清透" else "流光",
                color = Color(0xFF5E5E66),
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ThemePreviewKeys(isGlass = !isMiuix)
        }
    }
}

@Composable
private fun ThemePreviewKeys(isGlass: Boolean) {
    val keyColor = if (isGlass) Color.White.copy(alpha = 0.58f) else Color.White
    val keyBorder = if (isGlass) Color.White.copy(alpha = 0.85f) else Color(0x22000000)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(4, 3, 3).forEach { count ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(count) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(keyColor)
                            .border(1.dp, keyBorder, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun DarkModeSelector(
    current: DarkMode,
    onSelect: (DarkMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BitapTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.functionKey)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DarkMode.entries.forEach { mode ->
            val selected = current == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) palette.surface else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = darkModeLabel(mode),
                    color = if (selected) palette.textPrimary else palette.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(modelInfo: AiModelInfo, runtimeState: AiRuntimeState) {
    val palette = BitapTheme.palette
    val isOk = modelInfo.installed && runtimeState != AiRuntimeState.Error
    val text = when {
        modelInfo.error != null -> "异常"
        runtimeState == AiRuntimeState.Loading -> "加载中"
        runtimeState == AiRuntimeState.Busy -> "思考中"
        runtimeState == AiRuntimeState.Ready -> "就绪"
        runtimeState == AiRuntimeState.Error -> "异常"
        modelInfo.installed -> "已安装"
        else -> "未安装"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (isOk) palette.accent.copy(alpha = 0.14f) else palette.functionKey)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = if (isOk) palette.accent else palette.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun darkModeLabel(mode: DarkMode): String = when (mode) {
    DarkMode.System -> "跟随系统"
    DarkMode.Light -> "浅色"
    DarkMode.Dark -> "深色"
}

private fun modelStatusLabel(modelInfo: AiModelInfo, runtimeState: AiRuntimeState): String = when {
    modelInfo.error != null -> "异常"
    runtimeState == AiRuntimeState.Loading -> "加载中"
    runtimeState == AiRuntimeState.Busy -> "思考中"
    runtimeState == AiRuntimeState.Ready -> "就绪"
    runtimeState == AiRuntimeState.Error -> "异常"
    modelInfo.installed -> "已安装"
    else -> "未安装"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    val pattern = if (index == 0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[index])
}

private fun isBitapImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

private fun isBitapImeSelected(context: Context): Boolean {
    val selected = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    ) ?: return false
    return selected.startsWith(context.packageName)
}
