package com.bitap.keyboard.core

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D05 dev partial-ranking experiment.
 *
 * The candidate pool, 64-character cut point, partial quota and early-partial
 * position are held fixed. Only the ordering among partial prefix candidates is
 * varied:
 *  - SCORE: current production ordering
 *  - LONGEST_FIRST: longer consumed prefixes first, then score
 *  - SHORTEST_FIRST: shorter consumed prefixes first, then score
 *
 * Reports both the over-budget prefix-quality view and a complete-sentence
 * quality regression over D05 dev natural cases. Dev diagnostic, not acceptance.
 */
class PinyinPartialRankingEvalTest {

    private data class EvalCase(
        val id: String,
        val category: String,
        val input: String,
        val expected: List<String>,
    )

    private data class Variant(
        val label: String,
        val tuning: PinyinEngine.EngineTuning,
    )

    @Test
    fun comparePartialRankingModes() {
        val root = findRepoRoot()
        val devFile = File(root, "eval/d05/dev/dev.tsv")
        val dictionaryFile = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assertTrue("D05 dev corpus missing", devFile.isFile)
        assertTrue("dictionary missing", dictionaryFile.isFile)

        val allCases = loadCases(devFile)
        val natural = allCases.filter {
            it.category.startsWith("natural_") && requiredCoverage(it.input) <= MAX_QUERY_LENGTH
        }
        val overBudget = allCases.filter {
            it.category.startsWith("natural_") && requiredCoverage(it.input) > MAX_QUERY_LENGTH
        }
        assertTrue("natural rankable cases missing", natural.size >= 500)
        assertTrue("over-budget cases missing", overBudget.size >= 30)

        val engine = dictionaryFile.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue("engine failed to load dictionary", engine.isReady)
        engine.setLearningEnabled(false)
        allCases.take(16).forEach { engine.search(it.input, limit = 8) }

        val baseline = PinyinEngine.EngineTuning()
        val variants = listOf(
            Variant("SCORE", baseline),
            Variant("LONGEST_FIRST", baseline.copy(partialRankingMode = PartialRankingMode.LONGEST_FIRST)),
            Variant("SHORTEST_FIRST", baseline.copy(partialRankingMode = PartialRankingMode.SHORTEST_FIRST)),
        )

        val report = StringBuilder()
        report.appendLine("# D05 dev partial-ranking diagnostic (not acceptance)")
        report.appendLine("corpus=" + relativePath(root, devFile))
        report.appendLine("naturalRankable=" + natural.size)
        report.appendLine("overBudgetCases=" + overBudget.size)
        report.appendLine("cut=64 fixed; quota=default; earlyPartialPosition=default; only partial ordering varies")
        report.appendLine()
        report.appendLine("variant\tnaturalTop1\tnaturalTop5\tnaturalRankCost\tprefixTop1\tprefixTop8\tprefixTop64\tavgBestPrefixRankOnHit\tavgBestPrefixConsumedOnHit\tcompletionSuccess\tavgStepsSuccess\tavgStepsBeforeFailure\tzeroStepFailures")
        val results = variants.map { variant ->
            val tuned = engine.withTuning(variant.tuning)
            val naturalRows = natural.map { evaluateNatural(tuned, it) }
            val budgetRows = overBudget.map { evaluatePrefix(tuned, it) }
            val completionRows = overBudget.map { evaluateCompletion(tuned, it) }
            val top1 = naturalRows.count { it.rankTop8 == 0 }
            val top5 = naturalRows.count { it.rankTop8 in 0..4 }
            val rankCost = naturalRows.map { if (it.rankTop8 in 0..4) it.rankTop8 + 1 else 9 }.average()
            val prefixTop1 = budgetRows.count { it.bestRank == 0 }
            val prefixTop8 = budgetRows.count { it.bestRank in 0..7 }
            val prefixTop64 = budgetRows.count { it.bestRank >= 0 }
            val hitRows = budgetRows.filter { it.bestRank >= 0 }
            val avgBestRankOnHit = if (hitRows.isEmpty()) 0.0 else hitRows.map { it.bestRank + 1 }.average()
            val avgBestConsumedOnHit = if (hitRows.isEmpty()) 0.0 else hitRows.map { it.bestConsumed }.average()
            val completionSuccess = completionRows.filter { it.exact }
            val completionFailure = completionRows.filter { !it.exact }
            val avgStepsSuccess = if (completionSuccess.isEmpty()) 0.0 else completionSuccess.map { it.steps }.average()
            val avgStepsBeforeFailure = if (completionFailure.isEmpty()) 0.0 else completionFailure.map { it.steps }.average()
            report.appendLine(
                variant.label + "\t" + top1 + "/" + natural.size + "\t" + top5 + "/" + natural.size +
                    "\t" + fmt(rankCost) + "\t" + prefixTop1 + "/" + overBudget.size +
                    "\t" + prefixTop8 + "/" + overBudget.size + "\t" + prefixTop64 + "/" + overBudget.size +
                    "\t" + fmt(avgBestRankOnHit) + "\t" + fmt(avgBestConsumedOnHit) +
                    "\t" + completionSuccess.size + "/" + overBudget.size +
                    "\t" + fmt(avgStepsSuccess) + "\t" + fmt(avgStepsBeforeFailure) +
                    "\t" + completionFailure.count { it.zeroStepFailure },
            )
            VariantResult(variant.label, naturalRows, budgetRows, completionRows)
        }

        // The experiment must be a pure display-order test: selected candidate
        // membership and consumed ranges are frozen across all modes.
        val membership = variants.associate { variant ->
            variant.label to overBudget.associate { case ->
                case.id to candidateSignature(engine.withTuning(variant.tuning), case.input)
            }
        }
        val baselineMembership = membership.getValue("SCORE")
        variants.drop(1).forEach { variant ->
            val other = membership.getValue(variant.label)
            overBudget.forEach { case ->
                assertEquals(
                    "partial membership changed for " + case.id + " in " + variant.label,
                    baselineMembership.getValue(case.id),
                    other.getValue(case.id),
                )
            }
        }
        val top64Counts = results.map { result -> result.budget.count { it.bestRank >= 0 } }.toSet()
        assertEquals("Top64 hit count must be invariant under partial display reorder", 1, top64Counts.size)
        report.appendLine()
        report.appendLine("[naturalByCategory]")
        val categoryOrder = listOf("natural_short", "natural_medium", "natural_long")
        for (variant in results) {
            categoryOrder.forEach { category ->
                val rows = variant.natural.filter { it.case.category == category }
                if (rows.isEmpty()) return@forEach
                val top1 = rows.count { it.rankTop8 == 0 }
                val top5 = rows.count { it.rankTop8 in 0..4 }
                report.appendLine(
                    variant.label + "\t" + category + "\tn=" + rows.size + "\ttop1=" + top1 +
                        "\ttop5=" + top5 + "\trankCost=" + fmt(
                            rows.map { if (it.rankTop8 in 0..4) it.rankTop8 + 1 else 9 }.average(),
                        ),
                )
            }
        }
        report.appendLine()
        report.appendLine("[transitionsVsScore]")
        val baselineResult = results.first()
        results.drop(1).forEach { variant ->
            val naturalBase = baselineResult.natural.associateBy { it.case.id }
            val naturalVar = variant.natural.associateBy { it.case.id }
            val naturalGain = natural.count { case ->
                (naturalBase[case.id]?.rankTop8 ?: 99) !in 0..4 &&
                    (naturalVar[case.id]?.rankTop8 ?: 99) in 0..4
            }
            val naturalLoss = natural.count { case ->
                (naturalBase[case.id]?.rankTop8 ?: 99) in 0..4 &&
                    (naturalVar[case.id]?.rankTop8 ?: 99) !in 0..4
            }
            val budgetBase = baselineResult.budget.associateBy { it.case.id }
            val budgetVar = variant.budget.associateBy { it.case.id }
            val budgetGain = overBudget.count { case ->
                (budgetBase[case.id]?.bestRank ?: -1) !in 0..7 &&
                    (budgetVar[case.id]?.bestRank ?: -1) in 0..7
            }
            val budgetLoss = overBudget.count { case ->
                (budgetBase[case.id]?.bestRank ?: -1) in 0..7 &&
                    (budgetVar[case.id]?.bestRank ?: -1) !in 0..7
            }
            report.appendLine(
                variant.label + ": naturalTop5Gain=" + naturalGain + " naturalTop5Loss=" + naturalLoss +
                    " prefixTop8Gain=" + budgetGain + " prefixTop8Loss=" + budgetLoss,
            )
        }
        report.appendLine()
        report.appendLine("[overBudgetDetails]")
        report.appendLine("id\tvariant\tprefixTop1\tprefixHitRank\tfirstWord\tfirstConsumed")
        results.forEach { variant ->
            variant.budget.forEach { row ->
                report.appendLine(
                    row.case.id + "\t" + variant.label + "\t" + (row.bestRank == 0) + "\t" +
                        row.bestRank + "\t" + clean(row.bestWord) + "\t" + row.bestConsumed,
                )
            }
        }

        val reportFile = File(root, "build/d05/partial-ranking.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report.toString(), Charsets.UTF_8)
        println(report)
        println("wrote " + reportFile.absolutePath)
        assertEquals("variants", 3, results.size)
    }

    private data class NaturalRow(val case: EvalCase, val rankTop8: Int)

    private data class PrefixRow(
        val case: EvalCase,
        val bestRank: Int,
        val bestWord: String,
        val bestConsumed: Int,
    )

    private data class CompletionRow(
        val case: EvalCase,
        val exact: Boolean,
        val steps: Int,
    ) {
        val zeroStepFailure: Boolean get() = !exact && steps == 0
    }

    private data class VariantResult(
        val label: String,
        val natural: List<NaturalRow>,
        val budget: List<PrefixRow>,
        val completion: List<CompletionRow>,
    )

    private fun evaluateNatural(engine: PinyinEngine, case: EvalCase): NaturalRow {
        val coverage = requiredCoverage(case.input)
        val candidates = engine.search(case.input, limit = 8)
        val rank = candidates.indexOfFirst { candidate ->
            candidate.consumedInputLength >= coverage &&
                case.expected.any { expected -> normalizeForMatch(expected) == normalizeForMatch(candidate.word) }
        }
        return NaturalRow(case, rank)
    }

    private fun evaluatePrefix(engine: PinyinEngine, case: EvalCase): PrefixRow {
        val prefix = case.input.substring(0, MAX_QUERY_LENGTH)
        val candidates = engine.search(prefix, limit = 64)
        val expectedCjk = case.expected.map(::normalizeCjk).filter { it.isNotEmpty() }
        val rank = candidates.indexOfFirst { candidate ->
            val word = normalizeCjk(candidate.word)
            word.isNotEmpty() && expectedCjk.any { it.startsWith(word) }
        }
        val best = candidates.getOrNull(rank)
        return PrefixRow(case, rank, best?.word.orEmpty(), best?.consumedInputLength ?: 0)
    }

    /** Greedy first-candidate completion; failures are separated from success steps below. */
    private fun evaluateCompletion(engine: PinyinEngine, case: EvalCase): CompletionRow {
        var current = case.input
        val committed = StringBuilder()
        var steps = 0
        var exact = false
        while (current.isNotEmpty() && steps < MAX_COMPLETION_STEPS) {
            val prefix = current.take(MAX_QUERY_LENGTH)
            val candidates = engine.search(prefix, limit = 8)
            val chosen = candidates.firstOrNull { it.consumedInputLength in 1..prefix.length } ?: break
            committed.append(chosen.word)
            current = current.substring(chosen.consumedInputLength)
            steps++
            if (current.isEmpty()) {
                exact = case.expected.any { normalizeForMatch(it) == normalizeForMatch(committed.toString()) }
                break
            }
        }
        return CompletionRow(case, exact, steps)
    }

    /** Stable signature of the candidate text and consumed range set for membership checks. */
    private fun candidateSignature(engine: PinyinEngine, input: String): List<String> =
        engine.search(input.take(MAX_QUERY_LENGTH), limit = 64)
            .map { it.word + ":" + it.consumedInputLength }
            .sorted()

    private fun loadCases(file: File): List<EvalCase> = file.useLines { lines ->
        lines.mapNotNull { raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("#")) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size < 6) return@mapNotNull null
            EvalCase(
                id = parts[0].trim(),
                category = parts[1].trim(),
                input = parts[2].trim(),
                expected = parts[3].split('|').map { it.trim() }.filter { it.isNotEmpty() },
            )
        }.toList()
    }

    private fun requiredCoverage(input: String): Int = input.count { it.isLetterOrDigit() || it == '\'' }

    private fun normalizeForMatch(text: String): String = text.filter { it.isLetterOrDigit() || isCjk(it) }

    private fun normalizeCjk(text: String): String = text.filter { isCjk(it) }

    private fun isCjk(char: Char): Boolean =
        char.code in 0x3400..0x4DBF || char.code in 0x4E00..0x9FFF || char.code in 0xF900..0xFAFF

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun clean(text: String): String = text.replace('\t', ' ').replace('\n', ' ')

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
        error("repository root with app/src/main/assets/pinyin_dict.tsv and eval/d05/dev/dev.tsv not found")
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 64
        const val MAX_COMPLETION_STEPS = 30
    }
}
