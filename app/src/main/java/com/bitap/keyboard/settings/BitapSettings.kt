package com.bitap.keyboard.settings

enum class ThemeFamily {
    Miuix,
    Glasmio,
}

enum class DarkMode {
    System,
    Light,
    Dark,
}

data class BitapSettings(
    val themeFamily: ThemeFamily = ThemeFamily.Miuix,
    val darkMode: DarkMode = DarkMode.System,
    val aiEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val smartPunctuation: Boolean = true,
    /** 0.85f = compact, 1.0f = standard, 1.35f = extra tall. */
    val keyboardHeightScale: Float = 1.0f,
)
