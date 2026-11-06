package com.bitap.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small, synchronous preference store. No text, prompts or model data is ever written here.
 */
class SettingsRepository(context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<BitapSettings> = _settings.asStateFlow()

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    fun setThemeFamily(value: ThemeFamily) {
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    fun setDarkMode(value: DarkMode) {
        prefs.edit().putString(KEY_DARK, value.name).apply()
    }

    fun setAiEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AI, value).apply()
    }

    fun setHapticsEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTICS, value).apply()
    }

    fun setSoundEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND, value).apply()
    }

    fun setSmartPunctuation(value: Boolean) {
        prefs.edit().putBoolean(KEY_PUNCTUATION, value).apply()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        _settings.value = read()
    }

    private fun read(): BitapSettings = BitapSettings(
        themeFamily = prefs.getString(KEY_THEME, null)
            ?.let { runCatching { ThemeFamily.valueOf(it) }.getOrNull() }
            ?: ThemeFamily.Miuix,
        darkMode = prefs.getString(KEY_DARK, null)
            ?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() }
            ?: DarkMode.System,
        aiEnabled = prefs.getBoolean(KEY_AI, true),
        hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true),
        soundEnabled = prefs.getBoolean(KEY_SOUND, false),
        smartPunctuation = prefs.getBoolean(KEY_PUNCTUATION, true),
    )

    private companion object {
        const val FILE = "bitap_settings"
        const val KEY_THEME = "theme_family"
        const val KEY_DARK = "dark_mode"
        const val KEY_AI = "ai_enabled"
        const val KEY_HAPTICS = "haptics"
        const val KEY_SOUND = "sound"
        const val KEY_PUNCTUATION = "smart_punctuation"
    }
}
