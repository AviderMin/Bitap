package com.bitap.keyboard

import android.app.Application
import com.bitap.keyboard.ai.AiRewriteService
import com.bitap.keyboard.ai.LocalAiRewriteService
import com.bitap.keyboard.settings.SettingsRepository

class BitapApplication : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val aiService: AiRewriteService by lazy { LocalAiRewriteService(this) }

    override fun onTerminate() {
        aiService.close()
        super.onTerminate()
    }
}
