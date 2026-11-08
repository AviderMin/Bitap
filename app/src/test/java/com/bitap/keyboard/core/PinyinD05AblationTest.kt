package com.bitap.keyboard.core

import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D05 mechanism ablation: A/B/C candidate tunings plus the selected
 * A-partial-early-after-full-5 default. Test-only; no production data is changed.
 *
 * Outputs (build/, ignored):
 *  - build/d05/ablation-tuning.txt: naturalOnly sweep of the tuning variants.
 *  - build/d05/ablation-mechanism.txt: baseline (before) vs selected default
 *    (after) for naturalOnly, engineScored, every category, failureClass
 *    distribution, and v1 dev/holdout regression.
 *
 * The test pins the D05 dev hash and fails if the corpus changes without the
 * documentation/table being regenerated.
 */
class PinyinD05AblationTest {

    private data class EvalCase(
        val id: String,
        val category: String,
        val input: String,
        val expected: List<String>,
    )

    private data class Row(
        val case: EvalCase,
        val rank8: Int,
        val finalRank64: Int,
        val latencyNanos: Long,
        val fullCoverage: Boolean,
        val oracleHit: Boolean,
        val latticeHasExpected: Boolean,
        val collectedHasExpected: Boolean,
        val failureClass: String?,
    ) {
        val top1: Boolean get() = rank8 == 0
        val top5: Boolean get() = rank8 in 0..4
        val rankCost: Int get() = if (rank8 in 0..4) rank8 + 1 else MISS_RANK_COST
    }

    private data class Summary(
        val n: Int,
        val top1: Int,
        val top5: Int,
        val rankCost: Double,
        val oracle: Int,
        val fullCoverage: Int,
        val p50: Double,
        val p95: Double,
        val failures: Map<String, Int>,
    )

    private val categoryOrder = listOf(
        "natural_short",
        "natural_medium",
        "natural_long",
        "typo",
        "shorthand",
        "mixed_pinyin",
    )

    @Test
    fun runTuningAblations() {
        val root = findRepoRoot()
        val naturalCases = loadCases(File(root, "eval/d05/dev/dev.tsv"))
            .filter { it.category.startsWith("natural_") }
        assertTrue("natural cases missing", naturalCases.size >= 500)
        val engine = dictionaryEngine(root)

        // Explicit pre-change tuning: production was earlySlotCount=1 with the
        // old hard-coded earlyFull=3 (afterFull=3) before the selected change.
        val oldDefault = PinyinEngine.EngineTuning(
            partialEarlySlotCount = 1,
            partialEarlySlotAfterFull = 3,
            corpusWordNgramWeight = 0.35,
        )
        val variants = linkedMapOf(
            "baseline" to oldDefault,
            "A-partialQuota-0" to oldDefault.copy(partialQuota = 0, partialEarlySlotCount = 0),
            "A-partialQuota-8" to oldDefault.copy(partialQuota = 8),
            "A-partialQuota-12" to oldDefault.copy(partialQuota = 12),
            "A-earlySlot-0" to oldDefault.copy(partialEarlySlotCount = 0),
            "A-earlyAfter5 (selected)" to oldDefault.copy(partialEarlySlotAfterFull = 5),
            "B-beam-96" to oldDefault.copy(beamSize = 96),
            "B-beam-128" to oldDefault.copy(beamSize = 128),
            "B-entries-12" to oldDefault.copy(entriesPerKey = 12),
            "B-entries-16" to oldDefault.copy(entriesPerKey = 16),
            "C-corpus-0.05" to oldDefault.copy(sentenceCorpusWeight = 0.05),
            "C-corpus-0.10" to oldDefault.copy(sentenceCorpusWeight = 0.10),
            "C-corpus-0.20" to oldDefault.copy(sentenceCorpusWeight = 0.20),
            "C-dict-0.05" to oldDefault.copy(sentenceDictionaryWeight = 0.05),
            "C-dict-0.10" to oldDefault.copy(sentenceDictionaryWeight = 0.10),
        )
        val builder = StringBuilder()
        builder.appendLine("variant\tn\ttop1\ttop5\trankCost\toracle\tfullCoverage\tp50\tp95")
        variants.forEach { (name, tuning) ->
            val variantEngine = engine.withTuning(tuning)
            val rows = naturalCases.map { evaluate(variantEngine, it, diagnoseFailures = false) }
            val summary = summarize(rows)
            builder.appendLine(
                name + "\t" + summary.n + "\t" +
                    summary.top1 + "/" + summary.n + "(" + pct(summary.top1, summary.n) + ")\t" +
                    summary.top5 + "/" + summary.n + "(" + pct(summary.top5, summary.n) + ")\t" +
                    fmt(summary.rankCost) + "\t" +
                    summary.oracle + "/" + summary.n + "\t" +
                    summary.fullCoverage + "/" + summary.n + "\t" +
                    fmt(summary.p50) + "\t" + fmt(summary.p95),
            )
        }
        write(root, "build/d05/ablation-tuning.txt", builder.toString())
        println(builder)
    }

    @Test
    fun evaluateSelectedMechanism() {
        val root = findRepoRoot()
        val allCases = loadCases(File(root, "eval/d05/dev/dev.tsv"))
        assertTrue("D05 dev needs >=900 cases", allCases.size >= 900)
        val engine = dictionaryEngine(root)
        val oldDefault = PinyinEngine.EngineTuning(
            partialEarlySlotCount = 1,
            partialEarlySlotAfterFull = 3,
            corpusWordNgramWeight = 0.35,
        )
        // Historical task-11 after tuning: the ranking weight was still 0.35 then.
        // Explicit values keep this evidence reproducible after task-13 changed the
        // production ranking default; do not rebase old D05 results onto new weights.
        val afterDefault = PinyinEngine.EngineTuning(
            partialEarlySlotCount = 1,
            partialEarlySlotAfterFull = 5,
            corpusWordNgramWeight = 0.35,
        )
        val beforeEngine = engine.withTuning(oldDefault)
        val afterEngine = engine.withTuning(afterDefault)
        val engineScored = allCases.filter { !isInteraction(it) }
        val naturalCases = engineScored.filter { it.category.startsWith("natural_") }

        val builder = StringBuilder()
        builder.appendLine("# D05 mechanism ablation — baseline (earlySlot=1/afterFull=3) vs after (earlySlot=1/afterFull=5)")
        builder.appendLine("d05DevSha256=" + sha256(File(root, "eval/d05/dev/dev.tsv")))
        builder.appendLine("baselineTuning=partialEarlySlotCount=1, partialEarlySlotAfterFull=3")
        builder.appendLine("afterTuning=partialEarlySlotCount=1, partialEarlySlotAfterFull=5 (production default; all other tuning defaults)")
        builder.appendLine()
        appendPair(builder, "d05.engineScored", beforeEngine, afterEngine, engineScored)
        builder.appendLine()
        appendPair(builder, "d05.naturalOnly", beforeEngine, afterEngine, naturalCases)
        builder.appendLine()
        builder.appendLine("[d05.byCategory]")
        categoryOrder.forEach { category ->
            val rows = engineScored.filter { it.category == category }
            appendPair(builder, category, beforeEngine, afterEngine, rows, indent = "  ")
        }
        builder.appendLine()
        builder.appendLine("[d05.failureClasses]")
        builder.appendLine("before=" + failureDistribution(beforeEngine, engineScored))
        builder.appendLine("after=" + failureDistribution(afterEngine, engineScored))
        builder.appendLine("definition: noRecall/pruningLoss/rankedLow/displayLoss from debugSearch; see D05_FAILURE_DIAGNOSIS.md")
        builder.appendLine()
        appendV1(builder, root, "v1dev", File(root, "eval/pinyin_cases_dev.tsv"), beforeEngine, afterEngine)
        appendV1(builder, root, "v1holdout", File(root, "eval/pinyin_cases_holdout.tsv"), beforeEngine, afterEngine)
        write(root, "build/d05/ablation-mechanism.txt", builder.toString())
        println(builder)
    }

    @Test
    fun reportEarlyPartialPlacement() {
        val root = findRepoRoot()
        val engine = dictionaryEngine(root)
        val cases = loadCases(File(root, "eval/d05/dev/dev.tsv"))
            .filter { it.category == "natural_medium" || it.category == "natural_long" }
        val distribution = linkedMapOf<Int, Int>()
        var withPartial = 0
        var firstFiveAllFull = 0
        cases.forEach { case ->
            val coverage = requiredCoverage(case.input)
            if (coverage < 24) return@forEach
            val top8 = engine.search(case.input, limit = SEARCH_LIMIT)
            val firstPartial = top8.indexOfFirst { it.consumedInputLength < coverage }
            if (firstPartial >= 0) {
                withPartial += 1
                distribution[firstPartial] = (distribution[firstPartial] ?: 0) + 1
            }
            if (top8.size >= 5 && top8.take(5).all { it.consumedInputLength >= coverage }) {
                firstFiveAllFull += 1
            }
        }
        val builder = StringBuilder()
        builder.appendLine("casesWithCoverage>=24=" + cases.count { requiredCoverage(it.input) >= 24 })
        builder.appendLine("casesWithVisiblePartial=" + withPartial)
        builder.appendLine("casesWithFirstFiveAllFull=" + firstFiveAllFull)
        builder.appendLine("firstPartialIndexDistribution=" + distribution.entries.joinToString(",") { it.key.toString() + ":" + it.value })
        write(root, "build/d05/early-partial-placement.txt", builder.toString())
        println(builder)
    }

    private fun appendPair(
        builder: StringBuilder,
        label: String,
        before: PinyinEngine,
        after: PinyinEngine,
        cases: List<EvalCase>,
        indent: String = "",
    ) {
        val beforeRows = cases.map { evaluate(before, it, diagnoseFailures = true) }
        val afterRows = cases.map { evaluate(after, it, diagnoseFailures = true) }
        builder.appendLine(indent + label + ".before " + summaryText(summarize(beforeRows)))
        builder.appendLine(indent + label + ".after  " + summaryText(summarize(afterRows)))
    }

    private fun appendV1(
        builder: StringBuilder,
        root: File,
        label: String,
        file: File,
        before: PinyinEngine,
        after: PinyinEngine,
    ) {
        val cases = loadV1Cases(file).filter { !isInteraction(it) }
        val beforeRows = cases.map { evaluate(before, it, diagnoseFailures = false) }
        val afterRows = cases.map { evaluate(after, it, diagnoseFailures = false) }
        builder.appendLine(label + ".before " + summaryText(summarize(beforeRows)))
        builder.appendLine(label + ".after  " + summaryText(summarize(afterRows)))
    }

    private fun evaluate(engine: PinyinEngine, case: EvalCase, diagnoseFailures: Boolean): Row {
        val started = System.nanoTime()
        val top8 = engine.search(case.input, limit = SEARCH_LIMIT)
        val latencyNanos = System.nanoTime() - started
        val pool = engine.search(case.input, limit = FULL_LIMIT)
        val coverage = requiredCoverage(case.input)
        val rank8 = top8.indexOfFirst { matches(it, case) }
        val fullCoverage = pool.any { it.consumedInputLength >= coverage }
        val oracle = pool.any { matches(it, case) }
        var finalRank64 = -1
        var latticeHasExpected = false
        var collectedHasExpected = false
        var failureClass: String? = null
        if (diagnoseFailures && rank8 !in 0..4) {
            val debug = engine.debugSearch(case.input)
            finalRank64 = debug.finalCandidates.indexOfFirst { matches(it, case) }
            latticeHasExpected = debug.latticeFullTexts.any { matchesText(it, case) }
            collectedHasExpected = debug.collected.any { candidate ->
                matchesText(candidate.word, case) && candidate.consumed >= coverage
            }
            failureClass = failureClassOf(rank8, finalRank64, latticeHasExpected, collectedHasExpected)
        }
        return Row(case, rank8, finalRank64, latencyNanos, fullCoverage, oracle, latticeHasExpected, collectedHasExpected, failureClass)
    }

    private fun failureClassOf(
        rank8: Int,
        finalRank64: Int,
        latticeHasExpected: Boolean,
        collectedHasExpected: Boolean,
    ): String? {
        if (rank8 in 0..4) return null
        if (rank8 in 5..7) return "displayLoss"
        if (finalRank64 >= 8) return "rankedLow"
        if (finalRank64 in 0..7) return "displayLoss"
        if (latticeHasExpected || collectedHasExpected) return "pruningLoss"
        return "noRecall"
    }

    private fun failureDistribution(engine: PinyinEngine, cases: List<EvalCase>): String {
        val order = listOf("noRecall", "pruningLoss", "rankedLow", "displayLoss")
        val rows = cases.map { evaluate(engine, it, diagnoseFailures = true) }
        return order.joinToString(" ") { name -> name + "=" + rows.count { it.failureClass == name } }
    }

    private fun summarize(rows: List<Row>): Summary {
        val values = rows.map { it.latencyNanos / 1_000_000.0 }.sorted()
        return Summary(
            n = rows.size,
            top1 = rows.count { it.top1 },
            top5 = rows.count { it.top5 },
            rankCost = if (rows.isEmpty()) 0.0 else rows.sumOf { it.rankCost }.toDouble() / rows.size,
            oracle = rows.count { it.oracleHit },
            fullCoverage = rows.count { it.fullCoverage },
            p50 = if (values.isEmpty()) 0.0 else values[(values.size - 1) * 50 / 100],
            p95 = if (values.isEmpty()) 0.0 else values[(values.size - 1) * 95 / 100],
            failures = rows.mapNotNull { it.failureClass }.groupingBy { it }.eachCount(),
        )
    }

    private fun summaryText(summary: Summary): String =
        "n=" + summary.n +
            " top1=" + summary.top1 + "/" + summary.n + "(" + pct(summary.top1, summary.n) + ")" +
            " top5=" + summary.top5 + "/" + summary.n + "(" + pct(summary.top5, summary.n) + ")" +
            " rankCost=" + fmt(summary.rankCost) +
            " oracle=" + summary.oracle + "/" + summary.n +
            " fullCoverage=" + summary.fullCoverage + "/" + summary.n +
            " p50=" + fmt(summary.p50) + "ms p95=" + fmt(summary.p95) + "ms"

    private fun matches(candidate: Candidate, case: EvalCase): Boolean {
        val text = normalize(candidate.word)
        return text.isNotEmpty() && candidate.consumedInputLength >= requiredCoverage(case.input) &&
            case.expected.any { normalize(it) == text }
    }

    private fun matchesText(text: String, case: EvalCase): Boolean {
        val normalized = normalize(text)
        return normalized.isNotEmpty() && case.expected.any { normalize(it) == normalized }
    }

    private fun requiredCoverage(input: String): Int = input.count { it.isLetterOrDigit() || it == '\'' }

    private fun normalize(text: String): String = text.filter { it.isLetterOrDigit() || isCjk(it) }

    private fun isCjk(char: Char): Boolean =
        char.code in 0x3400..0x4DBF || char.code in 0x4E00..0x9FFF || char.code in 0xF900..0xFAFF

    private fun isInteraction(case: EvalCase): Boolean =
        case.category == "mixed_en" || (case.category == "number_punct" && case.input.any { it.isDigit() })

    private fun dictionaryEngine(root: File): PinyinEngine =
        File(root, "app/src/main/assets/pinyin_dict.tsv").useLines { PinyinEngine.fromLines(it) }.also {
            it.setLearningEnabled(false)
        }

    private fun loadCases(file: File): List<EvalCase> = file.useLines { lines ->
        lines.mapNotNull { raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size != 6) return@mapNotNull null
            EvalCase(parts[0], parts[1], parts[2], parts[3].split('|').map { it.trim() }.filter { it.isNotEmpty() })
        }.toList()
    }

    private fun loadV1Cases(file: File): List<EvalCase> = file.useLines { lines ->
        lines.mapNotNull { raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            EvalCase(parts[0], parts[1], parts[2], parts[3].split('|').map { it.trim() }.filter { it.isNotEmpty() })
        }.toList()
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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

    private fun findRepoRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "eval/d05/dev/dev.tsv").isFile &&
                File(directory, "app/src/main/assets/pinyin_dict.tsv").isFile
            ) {
                return directory
            }
            directory = directory.parentFile
        }
        error("repo root not found")
    }

    private fun write(root: File, path: String, text: String) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun pct(value: Int, total: Int): String =
        if (total == 0) "0.0" else String.format(Locale.ROOT, "%.1f", value * 100.0 / total)

    companion object {
        private const val SEARCH_LIMIT = 8
        private const val FULL_LIMIT = 64
        private const val MISS_RANK_COST = 9
    }
}
