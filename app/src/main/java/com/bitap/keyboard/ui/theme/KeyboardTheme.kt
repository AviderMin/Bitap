package com.bitap.keyboard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.bitap.keyboard.settings.KeyboardTheme

/**
 * Keyboard-only theme system.
 *
 * The app shell (home/settings) uses Miuix or Glasmio via [BitapTheme]. The IME
 * deliberately does not use either UI framework: it gets its own compact palette
 * so keyboard density, contrast and material stay stable when the app theme changes.
 */
@Composable
fun BitapKeyboardTheme(
    theme: KeyboardTheme,
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBitapPalette provides keyboardPalette(theme, darkTheme)) {
        content()
    }
}

fun keyboardPalette(theme: KeyboardTheme, darkTheme: Boolean): BitapPalette = when (theme) {
    KeyboardTheme.Paper -> if (darkTheme) PaperDark else PaperLight
    KeyboardTheme.Ink -> if (darkTheme) InkDark else InkLight
    KeyboardTheme.Aurora -> if (darkTheme) AuroraDark else AuroraLight
}

private val PaperLight = BitapPalette(
    background = Color(0xFFF7F7F9),
    surface = Color(0xFFFFFFFF),
    elevatedSurface = Color(0xFFFFFFFF),
    key = Color(0xFFFFFFFF),
    keyPressed = Color(0xFFE7E7EC),
    functionKey = Color(0xFFEDEDF1),
    accent = Color(0xFF3366FF),
    onAccent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF111114),
    textSecondary = Color(0xFF6E6E73),
    hairline = Color(0x1A000000),
    danger = Color(0xFFFF3B30),
)

private val PaperDark = BitapPalette(
    background = Color(0xFF111114),
    surface = Color(0xFF1C1C20),
    elevatedSurface = Color(0xFF242429),
    key = Color(0xFF2A2A2E),
    keyPressed = Color(0xFF3A3A3F),
    functionKey = Color(0xFF1F1F23),
    accent = Color(0xFF7AA2FF),
    onAccent = Color(0xFF081020),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFA5A5AC),
    hairline = Color(0x24FFFFFF),
    danger = Color(0xFFFF453A),
)

private val InkLight = BitapPalette(
    background = Color(0xFFF3F0EA),
    surface = Color(0xFFFFFCF5),
    elevatedSurface = Color(0xFFFFFCF5),
    key = Color(0xFFFFFCF5),
    keyPressed = Color(0xFFE5DFD3),
    functionKey = Color(0xFFE8E1D5),
    accent = Color(0xFF2C7A6B),
    onAccent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF1A1712),
    textSecondary = Color(0xFF69635A),
    hairline = Color(0x1F000000),
    danger = Color(0xFFB3261E),
)

private val InkDark = BitapPalette(
    background = Color(0xFF0D1110),
    surface = Color(0xFF202A27),
    elevatedSurface = Color(0xFF202A27),
    key = Color(0xFF202A27),
    keyPressed = Color(0xFF30413B),
    functionKey = Color(0xFF151D1A),
    accent = Color(0xFF6BD6B8),
    onAccent = Color(0xFF05211B),
    textPrimary = Color(0xFFEDF7F3),
    textSecondary = Color(0xFFA9B8B2),
    hairline = Color(0x24FFFFFF),
    danger = Color(0xFFFF8A80),
)

private val AuroraLight = BitapPalette(
    background = Color(0xFFEAF2FF),
    surface = Color(0xFFF8FBFF),
    elevatedSurface = Color(0xFFFFFFFF),
    key = Color.White.copy(alpha = 0.58f),
    keyPressed = Color.White.copy(alpha = 0.78f),
    functionKey = Color.White.copy(alpha = 0.36f),
    accent = Color(0xFF0A84FF),
    onAccent = Color.White,
    textPrimary = Color(0xFF0B0F1A),
    textSecondary = Color(0xFF4A4A52),
    hairline = Color(0x1F000000),
    danger = Color(0xFFFF453A),
    glassHighlight = Color.White.copy(alpha = 0.72f),
    isGlass = true,
)

private val AuroraDark = BitapPalette(
    background = Color(0xFF0A1524),
    surface = Color(0xFF10233A),
    elevatedSurface = Color(0xFF16304A),
    key = Color.White.copy(alpha = 0.12f),
    keyPressed = Color.White.copy(alpha = 0.22f),
    functionKey = Color.White.copy(alpha = 0.08f),
    accent = Color(0xFF64D2FF),
    onAccent = Color(0xFF04121A),
    textPrimary = Color(0xFFF5F7FB),
    textSecondary = Color(0xFFA8B0C0),
    hairline = Color(0x2EFFFFFF),
    danger = Color(0xFFFF6961),
    glassHighlight = Color.White.copy(alpha = 0.38f),
    isGlass = true,
)
