package com.bitap.keyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitap.keyboard.settings.DarkMode
import com.bitap.keyboard.ui.BitapApp
import com.bitap.keyboard.ui.theme.BitapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BitapApplication
        val repository = app.settingsRepository
        val aiService = app.aiService
        setContent {
            val settings by repository.settings.collectAsStateWithLifecycle()
            val dark = when (settings.darkMode) {
                DarkMode.System -> isSystemInDarkTheme()
                DarkMode.Light -> false
                DarkMode.Dark -> true
            }
            BitapTheme(themeFamily = settings.themeFamily, darkTheme = dark) {
                BitapApp(repository = repository, aiService = aiService)
            }
        }
    }
}
