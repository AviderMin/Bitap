package com.bitap.keyboard.qa

import com.bitap.keyboard.settings.BitapSettings
import com.bitap.keyboard.settings.DarkMode
import com.bitap.keyboard.settings.ThemeFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA acceptance guard for the settings contract shared by settings/, ui/ and ime/.
 *
 * Values are persisted to SharedPreferences by enum [Enum.name], therefore both the
 * defaults and the enum spellings are part of the 0.1 user-visible contract.
 */
class QaSettingsContractTest {

    @Test
    fun defaultSettingsMatchProductSpec() {
        val defaults = BitapSettings()
        assertEquals(ThemeFamily.Miuix, defaults.themeFamily)
        assertEquals(DarkMode.System, defaults.darkMode)
        assertTrue(defaults.aiEnabled)
        assertTrue(defaults.hapticsEnabled)
        assertFalse(defaults.soundEnabled)
        assertTrue(defaults.smartPunctuation)
        assertEquals(1.0f, defaults.keyboardHeightScale, 0.001f)
    }

    @Test
    fun persistedEnumNamesAreStable() {
        assertEquals(listOf("Miuix", "Glasmio"), ThemeFamily.entries.map { it.name })
        assertEquals(listOf("System", "Light", "Dark"), DarkMode.entries.map { it.name })
    }

    @Test
    fun everyPersistedEnumNameRoundTripsThroughValueOf() {
        ThemeFamily.entries.forEach { assertEquals(it, ThemeFamily.valueOf(it.name)) }
        DarkMode.entries.forEach { assertEquals(it, DarkMode.valueOf(it.name)) }
    }

    @Test
    fun settingsAreImmutableValueObjects() {
        val base = BitapSettings()
        val dark = base.copy(darkMode = DarkMode.Dark, aiEnabled = false, keyboardHeightScale = 1.25f)
        assertEquals(DarkMode.System, base.darkMode)
        assertTrue(base.aiEnabled)
        assertEquals(DarkMode.Dark, dark.darkMode)
        assertFalse(dark.aiEnabled)
        assertEquals(1.25f, dark.keyboardHeightScale, 0.001f)
    }
}
