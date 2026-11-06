package com.bitap.keyboard.qa

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static privacy gates from docs/05-privacy-security.md section 5.
 * These are file-level checks so they fail fast if a future change adds a network
 * permission or prints user text, without needing an emulator.
 */
class QaPrivacyGuardTest {

    private val manifestText: String by lazy {
        File("src/main/AndroidManifest.xml").readText()
    }

    @Test
    fun manifestDeclaresNoNetworkPermissions() {
        assertTrue(
            "AndroidManifest must not declare INTERNET",
            !manifestText.contains("android.permission.INTERNET"),
        )
        assertTrue(
            "AndroidManifest must not declare ACCESS_NETWORK_STATE",
            !manifestText.contains("android.permission.ACCESS_NETWORK_STATE"),
        )
    }

    @Test
    fun manifestDisablesBackupAndCleartextTraffic() {
        assertTrue(
            "allowBackup must be false",
            manifestText.contains("android:allowBackup=\"false\""),
        )
        assertTrue(
            "usesCleartextTraffic must be false",
            manifestText.contains("android:usesCleartextTraffic=\"false\""),
        )
    }

    @Test
    fun imeServiceKeepsBindInputMethodPermission() {
        assertTrue(
            "IME service must be guarded by BIND_INPUT_METHOD",
            manifestText.contains("android.permission.BIND_INPUT_METHOD"),
        )
    }

    @Test
    fun productionSourcesContainNoLoggingOrPrintln() {
        val roots = listOf(File("src/main/java"), File("src/main/kotlin"))
        val loggingPattern = Regex("""\bLog\.[dviwe]\s*\(|(^|[^A-Za-z0-9_])println\s*\(""")
        val offenders = ArrayList<String>()
        roots.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown()
                .filter { file -> file.isFile && file.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        if (loggingPattern.containsMatchIn(line)) {
                            offenders += file.path + ":" + (index + 1)
                        }
                    }
                }
        }
        assertTrue("production code must not log: " + offenders, offenders.isEmpty())
    }
}
