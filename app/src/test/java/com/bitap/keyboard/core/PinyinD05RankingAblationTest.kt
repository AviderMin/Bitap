package com.bitap.keyboard.core

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D05 ranking-mechanism ablation and selected-after evidence.
 *
 * D05 dev has 123 natural_long cases, 33 of which have pinyin length 65-70 and
 * exceed PinyinEngine's 64-character hard input budget. Ranking comparisons use
 * only the effective input subset (<=64); input-budget cases are reported
 * separately and must not be counted as ranking failures.
 *
 * Candidate variants are evaluated through EngineTuning/withTuning only. The
 * selected mechanism (corpusWordNgramWeight 0.35 -> 0.50) changes one score
 * weight; word list, search budgets and candidate display (including the
 * early-partial-after-5-full policy) stay fixed.
 */
class PinyinD05RankingAblationTest {

    private data class EvalCase(
        val id: String,
        val category: String,
        val input: String,
        val expected: List<String>,
    )

    private data class Row(
        val case: EvalCase,
        val rank8: Int,
        val latencyNanos: Long,
        val oracle: Boolean,
        val fullCoverage: Boolean,
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
    fun runRankingAblations() {
        val root = findRepoRoot()
        val devFile = File(root, "eval/d05/dev/dev.tsv")
        assertEquals("D05 dev hash", DEV_SHA256, sha256(devFile))
        val allNatural = loadCases(devFile).filter { it.category.startsWith("natural_") }
        val cases = allNatural.filter { rankable(it) }
        assertTrue("natural rankable cases missing", cases.size >= 500)

        val engine = dictionaryEngine(root)
        val base = PinyinEngine.EngineTuning(corpusWordNgramWeight = 0.35)
        val variants = linkedMapOf(
            "baseline" to base,
            "wordInsertionCompensation-8" to base.copy(wordInsertionCompensation = 8.0),
            "wordInsertionCompensation-10" to base.copy(wordInsertionCompensation = 10.0),
            "wordInsertionCompensation-14" to base.copy(wordInsertionCompensation = 14.0),
            "wordInsertionCompensation-16" to base.copy(wordInsertionCompensation = 16.0),
            "wordInsertionCompensation-20" to base.copy(wordInsertionCompensation = 20.0),
            "contextWeight-0.6" to base.copy(contextWeight = 0.6),
            "contextWeight-0.9" to base.copy(contextWeight = 0.9),
            "contextWeight-1.6" to base.copy(contextWeight = 1.6),
            "contextWeight-2.0" to base.copy(contextWeight = 2.0),
            "contextWeight-2.4" to base.copy(contextWeight = 2.4),
            "corpusWordNgramWeight-0.15" to base.copy(corpusWordNgramWeight = 0.15),
            "corpusWordNgramWeight-0.25" to base.copy(corpusWordNgramWeight = 0.25),
            "corpusWordNgramWeight-0.40" to base.copy(corpusWordNgramWeight = 0.40),
            "corpusWordNgramWeight-0.45" to base.copy(corpusWordNgramWeight = 0.45),
            "corpusWordNgramWeight-0.5" to base.copy(corpusWordNgramWeight = 0.5),
            "corpusWordNgramWeight-0.55" to base.copy(corpusWordNgramWeight = 0.55),
            "corpusWordNgramWeight-0.60" to base.copy(corpusWordNgramWeight = 0.60),
            "corpusWordNgramWeight-0.65" to base.copy(corpusWordNgramWeight = 0.65),
            "corpusWordNgramWeight-0.7" to base.copy(corpusWordNgramWeight = 0.7),
            "dictionaryWordNgramWeight-0.2" to base.copy(dictionaryWordNgramWeight = 0.2),
            "dictionaryWordNgramWeight-0.6" to base.copy(dictionaryWordNgramWeight = 0.6),
            "dictionaryWordNgramWeight-0.8" to base.copy(dictionaryWordNgramWeight = 0.8),
            "sentenceCorpusWeight-0.05" to base.copy(sentenceCorpusWeight = 0.05),
            "sentenceCorpusWeight-0.10" to base.copy(sentenceCorpusWeight = 0.10),
            "sentenceCorpusWeight-0.20" to base.copy(sentenceCorpusWeight = 0.20),
            "sentenceDictionaryWeight-0.05" to base.copy(sentenceDictionaryWeight = 0.05),
            "sentenceDictionaryWeight-0.10" to base.copy(sentenceDictionaryWeight = 0.10),
        )
        val builder = StringBuilder()
        builder.appendLine("variant\tn\ttop1\ttop5\trankCost\toracle\tfullCoverage\tp50\tp95")
        variants.forEach { (name, tuning) ->
            val rows = cases.map { evaluate(engine.withTuning(tuning), it, diagnoseFailures = false) }
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
        write(root, "build/d05/ranking-ablation.txt", builder.toString())
        println(builder)
    }

    @Test
    fun evaluateRankingMechanism() {
        val root = findRepoRoot()
        val devFile = File(root, "eval/d05/dev/dev.tsv")
        assertEquals("D05 dev hash", DEV_SHA256, sha256(devFile))
        val all = loadCases(devFile)
        val natural = all.filter { it.category.startsWith("natural_") && rankable(it) }
        val engineScored = all.filter { !isInteraction(it) && rankable(it) }
        val engine = dictionaryEngine(root)

        val off = PinyinEngine.EngineTuning(corpusWordNgramWeight = 0.0)
        val baseline = PinyinEngine.EngineTuning(corpusWordNgramWeight = 0.35)
        val after = PinyinEngine.EngineTuning() // production default: corpusWordNgramWeight=0.50

        val builder = StringBuilder()
        builder.appendLine("# D05 ranking ablation: baseline / after (0.50) / off (0.0)")
        builder.appendLine("d05DevSha256=" + DEV_SHA256)
        builder.appendLine("naturalTotal=" + all.count { it.category.startsWith("natural_") })
        builder.appendLine("naturalRankable=" + natural.size)
        builder.appendLine("naturalInputBudgetExcluded=" + all.count { it.category.startsWith("natural_") && !rankable(it) })
        builder.appendLine("engineScoredRankable=" + engineScored.size)
        builder.appendLine("baselineCorpusWordNgramWeight=0.35")
        builder.appendLine("afterCorpusWordNgramWeight=0.50")
        builder.appendLine("offCorpusWordNgramWeight=0.0")
        builder.appendLine("note=inputBudget cases are pinyin length >64 and excluded from ranking metrics")
        builder.appendLine()
        appendTriplet(builder, "d05.naturalRankable", engine, off, baseline, after, natural)
        builder.appendLine()
        appendTriplet(builder, "d05.engineScoredRankable", engine, off, baseline, after, engineScored)
        builder.appendLine()
        builder.appendLine("[d05.byCategory]")
        categoryOrder.forEach { category ->
            val rows = engineScored.filter { it.category == category }
            appendTriplet(builder, category, engine, off, baseline, after, rows, indent = "  ")
        }
        builder.appendLine()
        builder.appendLine("[d05.failureClasses]")
        listOf("off" to off, "baseline" to baseline, "after" to after).forEach { (name, tuning) ->
            builder.appendLine(name + "=" + failureDistribution(engine.withTuning(tuning), natural))
        }
        builder.appendLine()
        appendV1(builder, root, "v1dev", File(root, "eval/pinyin_cases_dev.tsv"), engine, off, baseline, after)
        appendV1(builder, root, "v1holdout", File(root, "eval/pinyin_cases_holdout.tsv"), engine, off, baseline, after)
        write(root, "build/d05/ranking-mechanism.txt", builder.toString())
        println(builder)
    }

    private fun appendTriplet(
        builder: StringBuilder,
        label: String,
        engine: PinyinEngine,
        off: PinyinEngine.EngineTuning,
        baseline: PinyinEngine.EngineTuning,
        after: PinyinEngine.EngineTuning,
        cases: List<EvalCase>,
        indent: String = "",
    ) {
        listOf("off" to off, "baseline" to baseline, "after" to after).forEach { (name, tuning) ->
            val rows = cases.map { evaluate(engine.withTuning(tuning), it, diagnoseFailures = true) }
            builder.appendLine(indent + label + "." + name + " " + summaryText(summarize(rows)))
        }
    }

    private fun appendV1(
        builder: StringBuilder,
        root: File,
        label: String,
        file: File,
        engine: PinyinEngine,
        off: PinyinEngine.EngineTuning,
        baseline: PinyinEngine.EngineTuning,
        after: PinyinEngine.EngineTuning,
    ) {
        val cases = loadV1Cases(file).filter { !isInteraction(it) }
        listOf("off" to off, "baseline" to baseline, "after" to after).forEach { (name, tuning) ->
            val rows = cases.map { evaluate(engine.withTuning(tuning), it, diagnoseFailures = false) }
            builder.appendLine(label + "." + name + " " + summaryText(summarize(rows)))
        }
    }

    private fun evaluate(engine: PinyinEngine, case: EvalCase, diagnoseFailures: Boolean): Row {
        val started = System.nanoTime()
        val top8 = engine.search(case.input, limit = SEARCH_LIMIT)
        val latencyNanos = System.nanoTime() - started
        val pool = engine.search(case.input, limit = FULL_LIMIT)
        val coverage = requiredCoverage(case.input)
        val rank8 = top8.indexOfFirst { matches(it, case) }
        val oracle = pool.any { matches(it, case) }
        val fullCoverage = pool.any { it.consumedInputLength >= coverage }
        var failureClass: String? = null
        if (diagnoseFailures && rank8 !in 0..4) {
            val debug = engine.debugSearch(case.input)
            val finalRank = debug.finalCandidates.indexOfFirst { matches(it, case) }
            val latticeHas = debug.latticeFullTexts.any { matchesText(it, case) }
            val collectedHas = debug.collected.any { candidate ->
                matchesText(candidate.word, case) && candidate.consumed >= coverage
            }
            failureClass = when {
                finalRank >= 8 -> "rankedLow"
                finalRank in 0..7 -> "displayLoss"
                latticeHas || collectedHas -> "pruningLoss"
                else -> "noRecall"
            }
        }
        return Row(case, rank8, latencyNanos, oracle, fullCoverage, failureClass)
    }

    private fun failureDistribution(engine: PinyinEngine, cases: List<EvalCase>): String {
        val rows = cases.map { evaluate(engine, it, diagnoseFailures = true) }
        val order = listOf("noRecall", "pruningLoss", "rankedLow", "displayLoss")
        return order.joinToString(" ") { name -> name + "=" + rows.count { it.failureClass == name } }
    }

    private fun summarize(rows: List<Row>): Summary {
        val values = rows.map { it.latencyNanos / 1_000_000.0 }.sorted()
        return Summary(
            n = rows.size,
            top1 = rows.count { it.top1 },
            top5 = rows.count { it.top5 },
            rankCost = if (rows.isEmpty()) 0.0 else rows.sumOf { it.rankCost }.toDouble() / rows.size,
            oracle = rows.count { it.oracle },
            fullCoverage = rows.count { it.fullCoverage },
            p50 = if (values.isEmpty()) 0.0 else values[(values.size - 1) * 50 / 100],
            p95 = if (values.isEmpty()) 0.0 else values[(values.size - 1) * 95 / 100],
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

    private fun rankable(case: EvalCase): Boolean = requiredCoverage(case.input) <= 64

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
        private const val DEV_SHA256 = "63983c5f9a490040592d6740e2e747e2b5a574d0a068accb44f25179153110ad"
        private const val SEARCH_LIMIT = 8
        private const val FULL_LIMIT = 64
        private const val MISS_RANK_COST = 9
    }
}
