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

enum class KeyboardTheme(val label: String, val tagline: String) {
    Paper("纸白", "干净、克制，接近纸面"),
    Ink("墨玉", "温润纸感，青绿色强调"),
    Aurora("极光", "半透明键帽与冷色渐变"),
}

data class BitapSettings(
    val themeFamily: ThemeFamily = ThemeFamily.Miuix,
    val darkMode: DarkMode = DarkMode.System,
    val aiEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    /** Whether local candidate learning may write to the user lexicon. */
    val learningEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val smartPunctuation: Boolean = true,
    /** 0.85f = compact, 1.0f = standard, 1.35f = extra tall. */
    val keyboardHeightScale: Float = 1.0f,
    val keyboardTheme: KeyboardTheme = KeyboardTheme.Paper,
)
