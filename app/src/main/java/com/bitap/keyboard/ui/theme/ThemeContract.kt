package com.bitap.keyboard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class BitapPalette(
    val background: Color,
    val surface: Color,
    val elevatedSurface: Color,
    val key: Color,
    val keyPressed: Color,
    val functionKey: Color,
    val accent: Color,
    val onAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val hairline: Color,
    val danger: Color,
    val glassHighlight: Color = Color.Transparent,
    val isGlass: Boolean = false,
)

val LocalBitapPalette = staticCompositionLocalOf {
    BitapPalette(
        background = Color(0xFFF5F5F7),
        surface = Color.White,
        elevatedSurface = Color.White,
        key = Color.White,
        keyPressed = Color(0xFFE8E8ED),
        functionKey = Color(0xFFE9E9EE),
        accent = Color(0xFF3482FF),
        onAccent = Color.White,
        textPrimary = Color(0xFF111114),
        textSecondary = Color(0xFF8A8A8E),
        hairline = Color(0x14000000),
        danger = Color(0xFFFF3B30),
    )
}

object BitapTheme {
    val palette: BitapPalette
        @Composable @ReadOnlyComposable
        get() = LocalBitapPalette.current

    val isGlass: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalBitapPalette.current.isGlass
}
