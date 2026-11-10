package com.bitap.keyboard.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-E synthetic correctness for the default-off long-input local correction
 * window. Uses only generic dictionary words; no sentence-specific rules.
 */
class PinyinLocalCorrectionSyntheticTest {

    private data class SyntheticCase(
        val id: String,
        val input: String,
        val expectedWord: String,
        val expectedConsumed: Int,
        val expectedSuffix: String,
    )

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/assets/pinyin_dict.tsv").isFile) return dir
            dir = dir.parentFile
        }
        error("repo root not found")
    }

    private fun norm(text: String): String = text.filter { it.isLetterOrDigit() || it.code in 0x3400..0x9FFF }

    @Test
    fun syntheticLocalCorrectionCases() {
        val root = findRepoRoot()
        val dict = File(root, "app/src/main/assets/pinyin_dict.tsv")
        val base = dict.useLines { PinyinEngine.fromLines(it) }
        base.setLearningEnabled(false)
        val enabled = base.withTuning(
            PinyinEngine.EngineTuning(
                localCorrectionWindowEnabled = true,
                localCorrectionTailLimit = 12,
                localCorrectionMaxWindow = 12,
                localCorrectionBudget = 32,
            ),
        )
        val prefix = "我们一起去"
        val expected = prefix + "看" + "明天"
        val cases = listOf(
            // Extra character inside the tail word: delete one from the window.
            SyntheticCase(
                id = "synthetic-tail-extra",
                input = "womenyiqiqukanmingtnian",
                expectedWord = expected,
                expectedConsumed = 23,
                expectedSuffix = "",
            ),
            // Missing character: insert one into the window.
            SyntheticCase(
                id = "synthetic-tail-missing",
                input = "womenyiqiqukanmingtia",
                expectedWord = expected,
                expectedConsumed = 21,
                expectedSuffix = "",
            ),
            // Adjacent transposition.
            SyntheticCase(
                id = "synthetic-tail-swap",
                input = "womenyiqiqukanmingtain",
                expectedWord = expected,
                expectedConsumed = 22,
                expectedSuffix = "",
            ),
            // Substitution (neighbouring-key style typo).
            SyntheticCase(
                id = "synthetic-tail-substitute",
                input = "womenyiqiqukanmingtiam",
                expectedWord = expected,
                expectedConsumed = 22,
                expectedSuffix = "",
            ),
            // Typo before trailing "de": suffix must remain unconsumed.
            SyntheticCase(
                id = "synthetic-window-suffix",
                input = "womenyiqiqukanmingtniande",
                expectedWord = expected,
                expectedConsumed = 23,
                expectedSuffix = "de",
            ),
        )
        val report = StringBuilder()
        cases.forEach { case ->
            val baselineExact = base.search(case.input, limit = 64)
                .filter { norm(it.word) == norm(case.expectedWord) }
            assertTrue("production default must not emit the synthetic correction for " + case.id, baselineExact.isEmpty())

            val debug = enabled.debugSearch(case.input)
            val corrections = debug.collected.filter { it.isCorrection }
            val hit = corrections.firstOrNull { norm(it.word) == norm(case.expectedWord) }
            assertNotNull("local correction missing for " + case.id + "; candidates=" + corrections.joinToString("|") { it.word }, hit)
            hit!!
            assertEquals("consumed for " + case.id, case.expectedConsumed, hit.consumed)
            assertTrue("hit must be marked isCorrection for " + case.id, hit.isCorrection)
            assertEquals("suffix length for " + case.id, case.input.length - case.expectedConsumed, case.expectedSuffix.length)
            if (case.expectedSuffix.isNotEmpty()) {
                assertEquals("suffix must be retained for " + case.id, case.expectedSuffix, case.input.substring(case.expectedConsumed))
                assertTrue("suffix case must be partial", hit.consumed < case.input.length)
            }
            val searchHits = enabled.search(case.input, limit = 64)
                .filter { it.isCorrection && norm(it.word) == norm(case.expectedWord) }
            report.appendLine(
                case.id + " input=" + case.input + " word=" + hit.word + " consumed=" + hit.consumed +
                    " suffix=" + (if (hit.consumed < case.input.length) case.input.substring(hit.consumed) else "") +
                    " score=" + String.format(java.util.Locale.ROOT, "%.2f", hit.score) +
                    " search64Hits=" + searchHits.size + " totalCorrections=" + corrections.size,
            )
        }
        println("[syntheticLocalCorrection]\n" + report)
        assertFalse("synthetic coverage must not be empty", cases.isEmpty())
    }
}
