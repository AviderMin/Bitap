package com.bitap.keyboard.core

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D05 cross-source diagnostic dev baseline.
 *
 * This test reads the development set that is explicitly allowed to be inspected
 * case by case. Its output is a development diagnostic signal, never acceptance:
 * v3 validation remains the only frozen acceptance set. The test writes:
 *  - build/d05/dev-baseline.txt: aggregate/category Top1/Top5/rankCost/oracle/
 *    fullCoverage/latency and failureClass distribution.
 *  - build/d05/dev-failures.tsv: one row per engine-scored Top5 miss with the
 *    debugSearch evidence used for its heuristic failure class.
 *
 * failureClass definitions (heuristic clues, not causal proof):
 *  - noRecall: expected text is absent from both debugSearch.latticeFullTexts
 *    and debugSearch.collected (full-coverage candidates).
 *  - pruningLoss: expected text survives in latticeFullTexts or collected but
 *    does not appear in finalCandidates (candidate cap/partial quota/beam prune
 *    downstream of the lattice).
 *  - rankedLow: expected candidate exists in finalCandidates but at rank >= 8.
 *  - displayLoss: expected candidate exists at rank 5..7 (generated but outside
 *    top five; visible in an eight-item display but not a Top5 hit).
 */
class PinyinD05DevEvalTest {

    private data class EvalCase(
        val id: String,
        val category: String,
        val input: String,
        val expected: List<String>,
        val note: String,
        val source: String,
    )

    private data class CaseOutcome(
        val case: EvalCase,
        val rankTop8: Int,
        val finalRank64: Int,
        val latencyNanos: Long,
        val fullCoverage: Boolean,
        val oracleHit: Boolean,
        val latticeHasExpected: Boolean,
        val collectedHasExpected: Boolean,
        val top8: List<Candidate>,
        val prefixRankTop8: Int,
    ) {
        val top1: Boolean get() = rankTop8 == 0
        val top5: Boolean get() = rankTop8 in 0..4
        val rankCost: Int get() = if (rankTop8 in 0..4) rankTop8 + 1 else MISS_RANK_COST
        val prefixTop1: Boolean get() = prefixRankTop8 == 0
        val prefixTop5: Boolean get() = prefixRankTop8 in 0..4
    }

    private val categoryOrder = listOf(
        "natural_short",
        "natural_medium",
        "natural_long",
        "typo",
        "shorthand",
        "mixed_pinyin",
    )

    private val categoryLabels = mapOf(
        "natural_short" to "自然短句",
        "natural_medium" to "自然中句",
        "natural_long" to "自然长句",
        "typo" to "错拼",
        "shorthand" to "简拼",
        "mixed_pinyin" to "混拼",
        "mixed_en" to "中英混输交互项",
    )

    @Test
    fun evaluateD05Dev() {
        val root = findRepoRoot()
        val devFile = File(root, "eval/d05/dev/dev.tsv")
        assertTrue(
            "D05 dev corpus missing; run eval/d05/dev/build_d05_dev.py first: " + devFile.absolutePath,
            devFile.isFile,
        )
        val dictionaryFile = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assertTrue("pinyin dictionary missing: " + dictionaryFile.absolutePath, dictionaryFile.isFile)

        val cases = loadCases(devFile)
        assertTrue("D05 dev needs >=900 cases but was " + cases.size, cases.size >= 900)
        assertEquals("duplicate D05 dev ids", cases.size, cases.map { it.id }.toSet().size)
        assertEquals("duplicate D05 dev inputs", cases.size, cases.map { it.input }.toSet().size)
        assertEquals(
            "D05 dev categories",
            setOf("natural_short", "natural_medium", "natural_long", "typo", "shorthand", "mixed_pinyin", "mixed_en"),
            cases.map { it.category }.toSet(),
        )

        val engine = dictionaryFile.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue("engine failed to load dictionary", engine.isReady)
        engine.setLearningEnabled(false)
        cases.take(24).forEach { engine.search(it.input, limit = SEARCH_LIMIT) }

        val outcomes = cases.map { evaluateCase(engine, it) }
        val scored = outcomes.filter { !isInteraction(it.case) }
        val interactions = outcomes.filter { isInteraction(it.case) }

        val baseline = buildBaselineReport(root, devFile, engine, cases, scored, interactions)
        val baselineFile = File(root, "build/d05/dev-baseline.txt")
        baselineFile.parentFile?.mkdirs()
        baselineFile.writeText(baseline, Charsets.UTF_8)

        val failures = scored.filter { it.rankTop8 !in 0..4 }
        val failureFile = File(root, "build/d05/dev-failures.tsv")
        failureFile.parentFile?.mkdirs()
        failureFile.writeText(buildFailuresTsv(failures), Charsets.UTF_8)

        println(baseline)
        println("wrote " + baselineFile.absolutePath)
        println("wrote " + failureFile.absolutePath + " (" + failures.size + " Top5 misses)")
    }

    private fun evaluateCase(engine: PinyinEngine, case: EvalCase): CaseOutcome {
        val coverage = requiredCoverage(case.input)
        val started = System.nanoTime()
        val top8 = engine.search(case.input, limit = SEARCH_LIMIT)
        val latencyNanos = System.nanoTime() - started
        val rankTop8 = top8.indexOfFirst { matchesCandidate(it, case) }
        val interaction = isInteraction(case)

        val fullPool = if (interaction) emptyList() else engine.search(case.input, limit = FULL_LIMIT)
        val fullCoverage = fullPool.any { it.consumedInputLength >= coverage }
        val oracleHit = fullPool.any { matchesCandidate(it, case) }

        var debug: PinyinEngine.DebugSearchResult? = null
        if (!interaction && rankTop8 !in 0..4) {
            debug = engine.debugSearch(case.input)
        }
        val finalRank64 = debug?.finalCandidates?.indexOfFirst { matchesCandidate(it, case) } ?: -1
        val latticeHasExpected = debug?.latticeFullTexts?.any { matchesText(it, case) } ?: false
        val collectedHasExpected = debug?.collected?.any { candidate ->
            matchesText(candidate.word, case) && candidate.consumed >= coverage
        } ?: false
        val prefixRankTop8 = if (interaction) {
            top8.indexOfFirst { matchesExpectedPrefix(it, case) }
        } else {
            -1
        }

        return CaseOutcome(
            case = case,
            rankTop8 = rankTop8,
            finalRank64 = finalRank64,
            latencyNanos = latencyNanos,
            fullCoverage = fullCoverage,
            oracleHit = oracleHit,
            latticeHasExpected = latticeHasExpected,
            collectedHasExpected = collectedHasExpected,
            top8 = top8,
            prefixRankTop8 = prefixRankTop8,
        )
    }

    private fun buildBaselineReport(
        root: File,
        devFile: File,
        engine: PinyinEngine,
        allCases: List<EvalCase>,
        scored: List<CaseOutcome>,
        interactions: List<CaseOutcome>,
    ): String {
        val builder = StringBuilder()
        builder.appendLine("# Bitap pinyin evaluation D05 dev (diagnostic dev set; NOT acceptance)")
        builder.appendLine("split=dev")
        builder.appendLine("corpus=" + relativePath(root, devFile))
        builder.appendLine("corpusSha256=" + sha256(devFile))
        builder.appendLine("dictionaryEntries=" + engine.entryCount)
        builder.appendLine("searchLimit=" + SEARCH_LIMIT + " oracleLimit=" + FULL_LIMIT)
        builder.appendLine("cases=" + allCases.size)
        builder.appendLine("engineScored=" + scored.size)
        builder.appendLine("interactionExcluded=" + interactions.size)
        builder.appendLine()
        builder.appendLine("[engineScored]")
        appendSummary(builder, scored)
        builder.appendLine()
        builder.appendLine("[byCategory]")
        categoryOrder.forEach { category ->
            val categoryResults = scored.filter { it.case.category == category }
            if (categoryResults.isEmpty()) return@forEach
            appendCategory(builder, category, categoryResults)
        }
        builder.appendLine()
        builder.appendLine("[naturalOnly]")
        appendSummary(builder, scored.filter { it.case.category.startsWith("natural_") })
        builder.appendLine()
        builder.appendLine("[interaction]")
        if (interactions.isEmpty()) {
            builder.appendLine("none")
        } else {
            builder.appendLine(
                "mixed_en n=" + interactions.size +
                    " prefixTop1=" + ratio(interactions.count { it.prefixTop1 }, interactions.size) +
                    " prefixTop5=" + ratio(interactions.count { it.prefixTop5 }, interactions.size) +
                    " note=excluded from engineScored (interaction case)",
            )
        }
        builder.appendLine()
        appendLatency(builder, scored)
        builder.appendLine("[failureClasses] " + failureClassDistribution(scored))
        return builder.toString()
    }

    private fun appendSummary(builder: StringBuilder, results: List<CaseOutcome>) {
        builder.appendLine("n=" + results.size)
        builder.appendLine("top1=" + ratio(results.count { it.top1 }, results.size))
        builder.appendLine("top5=" + ratio(results.count { it.top5 }, results.size))
        builder.appendLine("top1Ci95=" + wilson(results.count { it.top1 }, results.size))
        builder.appendLine("top5Ci95=" + wilson(results.count { it.top5 }, results.size))
        builder.appendLine("rankCost.avg=" + averageCost(results))
        builder.appendLine("rankCost.total=" + results.sumOf { it.rankCost })
        builder.appendLine("fullCoverage=" + ratio(results.count { it.fullCoverage }, results.size))
        builder.appendLine("oracle=" + ratio(results.count { it.oracleHit }, results.size))
        builder.appendLine("top5ErrorRate=" + percent(results.count { !it.top5 }, results.size) + "%")
    }

    private fun appendCategory(builder: StringBuilder, category: String, results: List<CaseOutcome>) {
        val latency = latencyValues(results)
        builder.appendLine(
            "  " + category + " (" + (categoryLabels[category] ?: category) + "): " +
                "n=" + results.size +
                " top1=" + ratio(results.count { it.top1 }, results.size) +
                " top5=" + ratio(results.count { it.top5 }, results.size) +
                " rankCost=" + averageCost(results) +
                " fullCoverage=" + ratio(results.count { it.fullCoverage }, results.size) +
                " oracle=" + ratio(results.count { it.oracleHit }, results.size) +
                " top1Ci95=" + wilson(results.count { it.top1 }, results.size) +
                " latencyP50=" + formatMillis(percentile(latency, 50)) +
                " latencyP95=" + formatMillis(percentile(latency, 95)),
        )
    }

    private fun appendLatency(builder: StringBuilder, results: List<CaseOutcome>) {
        val values = latencyValues(results)
        if (values.isEmpty()) {
            builder.appendLine("[latency] n/a")
            return
        }
        builder.appendLine(
            "[latency] p50=" + formatMillis(percentile(values, 50)) +
                " p95=" + formatMillis(percentile(values, 95)) +
                " p99=" + formatMillis(percentile(values, 99)) +
                " max=" + formatMillis(values.last()),
        )
    }

    private fun latencyValues(results: List<CaseOutcome>): List<Double> =
        results.map { it.latencyNanos / 1_000_000.0 }.sorted()

    private fun percentile(values: List<Double>, percent: Int): Double {
        if (values.isEmpty()) return 0.0
        val index = ((values.size - 1) * percent / 100).coerceIn(0, values.lastIndex)
        return values[index]
    }

    private fun buildFailuresTsv(failures: List<CaseOutcome>): String {
        val header = listOf(
            "id",
            "category",
            "input",
            "expected",
            "rank",
            "candidateTop8",
            "failureClass",
            "latticeHasExpected",
            "collectedHasExpected",
        ).joinToString("\t")
        val rows = failures.map { outcome ->
            val rank = if (outcome.finalRank64 >= 0) outcome.finalRank64.toString() else "-1"
            listOf(
                clean(outcome.case.id),
                clean(outcome.case.category),
                clean(outcome.case.input),
                clean(outcome.case.expected.joinToString("|")),
                rank,
                clean(outcome.top8.joinToString("|") { it.word }),
                failureClass(outcome) ?: "none",
                outcome.latticeHasExpected.toString(),
                outcome.collectedHasExpected.toString(),
            ).joinToString("\t")
        }
        return (listOf(header) + rows).joinToString("\n") + "\n"
    }

    private fun failureClass(outcome: CaseOutcome): String? {
        if (outcome.rankTop8 in 0..4) return null
        if (outcome.rankTop8 in 5..7) return "displayLoss"
        if (outcome.finalRank64 >= 8) return "rankedLow"
        if (outcome.finalRank64 in 0..7) return "displayLoss"
        if (outcome.latticeHasExpected || outcome.collectedHasExpected) return "pruningLoss"
        return "noRecall"
    }

    private fun failureClassDistribution(results: List<CaseOutcome>): String {
        val order = listOf("noRecall", "pruningLoss", "rankedLow", "displayLoss")
        val counts = order.associateWith { className ->
            results.count { failureClass(it) == className }
        }
        return counts.entries.joinToString(" ") { it.key + "=" + it.value }
    }

    private fun matchesCandidate(candidate: Candidate, case: EvalCase): Boolean {
        val candidateText = normalizeForMatch(candidate.word)
        if (candidateText.isEmpty()) return false
        val coverage = requiredCoverage(case.input)
        return candidate.consumedInputLength >= coverage &&
            case.expected.any { normalizeForMatch(it) == candidateText }
    }

    private fun matchesText(text: String, case: EvalCase): Boolean {
        val normalized = normalizeForMatch(text)
        if (normalized.isEmpty()) return false
        return case.expected.any { normalizeForMatch(it) == normalized }
    }

    private fun matchesExpectedPrefix(candidate: Candidate, case: EvalCase): Boolean {
        val candidatePrefix = normalizeCjk(candidate.word.takeWhile { isCjk(it) })
        if (candidatePrefix.isEmpty()) return false
        return case.expected.any { expected ->
            val prefix = normalizeCjk(expected.takeWhile { isCjk(it) })
            prefix.isNotEmpty() && prefix == candidatePrefix
        }
    }

    private fun requiredCoverage(input: String): Int = input.count { it.isLetterOrDigit() || it == '\'' }

    private fun normalizeForMatch(text: String): String =
        text.filter { it.isLetterOrDigit() || isCjk(it) }

    private fun normalizeCjk(text: String): String = text.filter { isCjk(it) }

    private fun isCjk(char: Char): Boolean =
        char.code in 0x3400..0x4DBF || char.code in 0x4E00..0x9FFF || char.code in 0xF900..0xFAFF

    private fun isInteraction(case: EvalCase): Boolean =
        case.category == "mixed_en" || (case.category == "number_punct" && case.input.any { it.isDigit() })

    private fun loadCases(file: File): List<EvalCase> = file.useLines { lines ->
        lines.mapNotNull { raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("#")) return@mapNotNull null
            val parts = line.split('\t')
            require(parts.size == 6) { "malformed D05 dev corpus line: " + line }
            EvalCase(
                id = parts[0].trim(),
                category = parts[1].trim(),
                input = parts[2].trim(),
                expected = parts[3].split('|').map { it.trim() }.filter { it.isNotEmpty() },
                note = parts[4].trim(),
                source = parts[5].trim(),
            )
        }.toList()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    private fun relativePath(root: File, file: File): String =
        file.relativeTo(root).path.replace('\\', '/')

    private fun findRepoRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/assets/pinyin_dict.tsv").isFile &&
                File(directory, "eval/d05/dev/dev.tsv").isFile
            ) {
                return directory
            }
            directory = directory.parentFile
        }
        // Keep the diagnostic failure explicit instead of silently skipping.
        error("repository root with app/src/main/assets/pinyin_dict.tsv and eval/d05/dev/dev.tsv not found")
    }

    private fun averageCost(results: List<CaseOutcome>): String =
        if (results.isEmpty()) "0.00" else String.format(Locale.ROOT, "%.2f", results.sumOf { it.rankCost }.toDouble() / results.size)

    private fun ratio(value: Int, total: Int): String =
        value.toString() + "/" + total + "(" + percent(value, total) + "%)"

    private fun percent(value: Int, total: Int): String =
        if (total == 0) "0.0" else String.format(Locale.ROOT, "%.1f", value * 100.0 / total)

    private fun wilson(hit: Int, total: Int): String {
        if (total == 0) return "n/a"
        val p = hit.toDouble() / total
        val z = 1.96
        val z2 = z * z
        val denominator = 1.0 + z2 / total
        val center = (p + z2 / (2.0 * total)) / denominator
        val half = z * sqrt(p * (1.0 - p) / total + z2 / (4.0 * total * total)) / denominator
        return String.format(
            Locale.ROOT,
            "%.1f%% [%.1f%%, %.1f%%]",
            p * 100.0,
            ((center - half) * 100.0).coerceIn(0.0, 100.0),
            ((center + half) * 100.0).coerceIn(0.0, 100.0),
        )
    }

    private fun formatMillis(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun clean(value: String): String = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    companion object {
        private const val SEARCH_LIMIT = 8
        private const val FULL_LIMIT = 64
        private const val MISS_RANK_COST = 9
    }
}
