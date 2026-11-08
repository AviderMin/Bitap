package com.bitap.keyboard.core

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D05 dev diagnostic for the long-input segmentation batch.
 *
 * Compares the current fixed-64-character query prefix with two boundary-aware
 * policies:
 *  - SYLLABLE: back off to the last reachable pinyin syllable boundary <= 64
 *  - WORD: back off to the last reachable dictionary-word boundary <= 64
 *
 * For each 65-70 character natural-long case this report records:
 *  - prefix length and whether the first returned candidate is a correct CJK
 *    prefix of the expected sentence ("prefix accuracy")
 *  - the unconsumed suffix after the first candidate (remaining-input consistency)
 *  - greedy first-candidate completion: exact final text and candidate taps
 *
 * This is a dev diagnostic, not acceptance evidence: the D05 set is explicitly
 * inspectable and the 33 over-budget cases remain outside ranking denominators.
 */
class PinyinLongInputBoundaryEvalTest {

    private data class EvalCase(
        val id: String,
        val category: String,
        val input: String,
        val expected: List<String>,
    )

    private data class FirstStep(
        val prefixLength: Int,
        val candidateWord: String,
        val candidateConsumed: Int,
        val prefixHit: Boolean,
        val bestPrefixRank: Int,
        val bestPrefixRank64: Int,
        val bestPrefixWord: String,
        val bestPrefixConsumed: Int,
        val remainingConsistent: Boolean,
    )

    private data class Completion(
        val exact: Boolean,
        val steps: Int,
        val finalText: String,
    ) {
        val zeroStepFailure: Boolean get() = !exact && steps == 0
    }

    private data class CaseResult(
        val case: EvalCase,
        val mode: LongInputBoundaryMode,
        val first: FirstStep,
        val completion: Completion,
        val oracle: Completion,
    )

    private val modes = listOf(
        LongInputBoundaryMode.FIXED,
        LongInputBoundaryMode.SYLLABLE,
        LongInputBoundaryMode.WORD,
    )

    @Test
    fun compareLongInputBoundaryPolicies() {
        val root = findRepoRoot()
        val devFile = File(root, "eval/d05/dev/dev.tsv")
        val dictionaryFile = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assertTrue("D05 dev corpus missing", devFile.isFile)
        assertTrue("dictionary missing", dictionaryFile.isFile)

        val cases = loadCases(devFile).filter {
            it.category.startsWith("natural_") && requiredCoverage(it.input) > MAX_QUERY_LENGTH
        }
        assertTrue("expected >=30 over-budget D05 natural cases, got " + cases.size, cases.size >= 30)
        assertEquals("duplicate over-budget ids", cases.size, cases.map { it.id }.toSet().size)

        val engine = dictionaryFile.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue("engine failed to load dictionary", engine.isReady)
        engine.setLearningEnabled(false)
        cases.take(8).forEach { engine.search(it.input, limit = 8) }

        val results = cases.flatMap { case ->
            modes.map { mode -> evaluate(engine, case, mode) }
        }
        val report = buildReport(root, devFile, engine, cases, results)
        val reportFile = File(root, "build/d05/long-input-boundary.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report, Charsets.UTF_8)
        println(report)
        println("wrote " + reportFile.absolutePath)

        val sweep = buildSweepReport(engine, cases)
        val sweepFile = File(root, "build/d05/long-input-boundary-sweep.txt")
        sweepFile.parentFile?.mkdirs()
        sweepFile.writeText(sweep, Charsets.UTF_8)
        println(sweep)
        println("wrote " + sweepFile.absolutePath)
    }

    /** Sweep shorter boundary targets to test the prefix-quality vs operation-cost tradeoff. */
    private fun buildSweepReport(engine: PinyinEngine, cases: List<EvalCase>): String {
        val builder = StringBuilder()
        builder.appendLine("# D05 dev long-input boundary target sweep (diagnostic, not acceptance)")
        builder.appendLine("overBudgetCases=" + cases.size)
        builder.appendLine("note=target is the maximum prefix length; boundary modes back off to the last syllable/multi-char-word boundary <= target")
        builder.appendLine("mode\ttarget\tavgPrefixLen\tprefixTop1\tprefixAnyTop8\tprefixAny64\tcompletionSuccess\tavgStepsSuccess\tavgStepsBeforeFailure\tzeroStepFailures\toracleSuccess\tavgOracleStepsSuccess\tavgOracleStepsBeforeFailure\toracleZeroStepFailures")
        val targets = listOf(24, 32, 40, 48, 56, 64)
        val modes = listOf(LongInputBoundaryMode.FIXED, LongInputBoundaryMode.SYLLABLE, LongInputBoundaryMode.WORD)
        for (mode in modes) {
            for (target in targets) {
                if (mode == LongInputBoundaryMode.FIXED && target != 64) continue
                val rows = cases.map { evaluate(engine, it, mode, target) }
                val success = rows.filter { it.completion.exact }
                val failure = rows.filter { !it.completion.exact }
                val oracleSuccess = rows.filter { it.oracle.exact }
                val oracleFailure = rows.filter { !it.oracle.exact }
                builder.appendLine(
                    mode.name + "\t" + target + "\t" + fmt(rows.map { it.first.prefixLength }.average()) +
                        "\t" + rows.count { it.first.prefixHit } +
                        "\t" + rows.count { it.first.bestPrefixRank >= 0 } +
                        "\t" + rows.count { it.first.bestPrefixRank64 >= 0 } +
                        "\t" + success.size +
                        "\t" + fmt(if (success.isEmpty()) 0.0 else success.map { it.completion.steps }.average()) +
                        "\t" + fmt(if (failure.isEmpty()) 0.0 else failure.map { it.completion.steps }.average()) +
                        "\t" + failure.count { it.completion.zeroStepFailure } +
                        "\t" + oracleSuccess.size +
                        "\t" + fmt(if (oracleSuccess.isEmpty()) 0.0 else oracleSuccess.map { it.oracle.steps }.average()) +
                        "\t" + fmt(if (oracleFailure.isEmpty()) 0.0 else oracleFailure.map { it.oracle.steps }.average()) +
                        "\t" + oracleFailure.count { it.oracle.zeroStepFailure },
                )
            }
        }
        return builder.toString()
    }

    private fun evaluate(
        engine: PinyinEngine,
        case: EvalCase,
        mode: LongInputBoundaryMode,
        maxLength: Int = MAX_QUERY_LENGTH,
    ): CaseResult {
        val prefix = engine.queryPrefixForBoundary(case.input, maxLength, mode)
        val firstCandidates = engine.search(prefix, limit = SEARCH_LIMIT)
        val first = firstCandidates.firstOrNull()
        val consumed = first?.consumedInputLength?.coerceIn(0, prefix.length) ?: 0
        val expectedCjk = case.expected.map(::normalizeCjk).filter { it.isNotEmpty() }
        fun isExpectedPrefix(candidate: Candidate): Boolean {
            val candidateCjk = normalizeCjk(candidate.word)
            return candidateCjk.isNotEmpty() && expectedCjk.any { it.startsWith(candidateCjk) }
        }
        val prefixHit = first?.let(::isExpectedPrefix) == true
        val bestPrefixRank = firstCandidates.indexOfFirst(::isExpectedPrefix)
        val allCandidates = engine.search(prefix, limit = 64)
        val bestPrefixRank64 = allCandidates.indexOfFirst(::isExpectedPrefix)
        val bestPrefix = allCandidates.getOrNull(bestPrefixRank64)
        val remaining = if (consumed > 0) case.input.substring(consumed) else case.input
        val firstStep = FirstStep(
            prefixLength = prefix.length,
            candidateWord = first?.word.orEmpty(),
            candidateConsumed = consumed,
            prefixHit = prefixHit,
            bestPrefixRank = bestPrefixRank,
            bestPrefixRank64 = bestPrefixRank64,
            bestPrefixWord = bestPrefix?.word.orEmpty(),
            bestPrefixConsumed = bestPrefix?.consumedInputLength?.coerceIn(0, prefix.length) ?: 0,
            remainingConsistent = remaining == case.input.substring(consumed),
        )

        var current = case.input
        val committed = StringBuilder()
        var steps = 0
        var exact = false
        while (current.isNotEmpty() && steps < MAX_COMPLETION_STEPS) {
            val currentPrefix = engine.queryPrefixForBoundary(current, maxLength, mode)
            val candidates = engine.search(currentPrefix, limit = SEARCH_LIMIT)
            val chosen = candidates.firstOrNull { it.consumedInputLength in 1..currentPrefix.length }
                ?: break
            committed.append(chosen.word)
            current = current.substring(chosen.consumedInputLength)
            steps++
            if (current.isEmpty()) {
                exact = case.expected.any { normalizeForMatch(it) == normalizeForMatch(committed.toString()) }
                break
            }
        }
        return CaseResult(
            case = case,
            mode = mode,
            first = firstStep,
            completion = Completion(exact = exact, steps = steps, finalText = committed.toString()),
            oracle = completeOracle(engine, case, mode, maxLength),
        )
    }

    /** Upper bound: choose the longest candidate whose CJK text is a prefix of the expected answer. */
    private fun completeOracle(
        engine: PinyinEngine,
        case: EvalCase,
        mode: LongInputBoundaryMode,
        maxLength: Int,
    ): Completion {
        var current = case.input
        var expected = case.expected.map(::normalizeCjk).firstOrNull { it.isNotEmpty() }.orEmpty()
        val committed = StringBuilder()
        var steps = 0
        while (current.isNotEmpty() && expected.isNotEmpty() && steps < MAX_COMPLETION_STEPS) {
            val prefix = engine.queryPrefixForBoundary(current, maxLength, mode)
            val candidates = engine.search(prefix, limit = 64)
            val chosen = candidates
                .filter { it.consumedInputLength in 1..prefix.length }
                .filter { expected.startsWith(normalizeCjk(it.word)) && normalizeCjk(it.word).isNotEmpty() }
                .maxByOrNull { it.consumedInputLength }
                ?: break
            val word = normalizeCjk(chosen.word)
            committed.append(chosen.word)
            expected = expected.removePrefix(word)
            current = current.substring(chosen.consumedInputLength)
            steps++
        }
        return Completion(
            exact = current.isEmpty() && expected.isEmpty(),
            steps = steps,
            finalText = committed.toString(),
        )
    }

    private fun buildReport(
        root: File,
        devFile: File,
        engine: PinyinEngine,
        cases: List<EvalCase>,
        results: List<CaseResult>,
    ): String {
        val builder = StringBuilder()
        builder.appendLine("# D05 dev long-input boundary diagnostic (not acceptance)")
        builder.appendLine("corpus=" + relativePath(root, devFile))
        builder.appendLine("overBudgetCases=" + cases.size)
        builder.appendLine("maxQueryLength=" + MAX_QUERY_LENGTH)
        builder.appendLine("searchLimit=" + SEARCH_LIMIT)
        builder.appendLine("note=prefixHit means first candidate word is a CJK prefix of an expected answer; completion uses greedy first candidate and is a diagnostic operation proxy, not device taps")
        builder.appendLine()
        builder.appendLine("mode\tn\tprefixTop1\tprefixTop1Pct\tprefixAnyTop8\tprefixAnyTop8Pct\tprefixAny64\tprefixAny64Pct\tavgBestPrefixRank\tavgBestPrefixRank64\tavgPrefixLen\tavgFirstConsumed\tavgBestPrefixConsumed\tremainingConsistent\tcompletionSuccess\tcompletionSuccessPct\tavgStepsSuccess\tavgStepsBeforeFailure\tzeroStepFailures\toracleSuccess\toracleSuccessPct\tavgOracleStepsSuccess\tavgOracleStepsBeforeFailure\toracleZeroStepFailures")
        modes.forEach { mode ->
            val rows = results.filter { it.mode == mode }
            val prefixHits = rows.count { it.first.prefixHit }
            val anyPrefixHits = rows.count { it.first.bestPrefixRank >= 0 }
            val anyPrefix64 = rows.count { it.first.bestPrefixRank64 >= 0 }
            val exact = rows.count { it.completion.exact }
            val avgPrefix = rows.map { it.first.prefixLength }.average()
            val avgConsumed = rows.map { it.first.candidateConsumed }.average()
            val avgBestRank = rows.map { if (it.first.bestPrefixRank >= 0) it.first.bestPrefixRank + 1 else 9 }.average()
            val avgBestRank64 = rows.map { if (it.first.bestPrefixRank64 >= 0) it.first.bestPrefixRank64 + 1 else 65 }.average()
            val avgBestConsumed = rows.map { it.first.bestPrefixConsumed }.average()
            val completionSuccess = rows.filter { it.completion.exact }
            val completionFailure = rows.filter { !it.completion.exact }
            val avgStepsSuccess = if (completionSuccess.isEmpty()) 0.0 else completionSuccess.map { it.completion.steps }.average()
            val avgStepsBeforeFailure = if (completionFailure.isEmpty()) 0.0 else completionFailure.map { it.completion.steps }.average()
            val zeroStepFailures = completionFailure.count { it.completion.zeroStepFailure }
            val oracleSuccess = rows.filter { it.oracle.exact }
            val oracleFailure = rows.filter { !it.oracle.exact }
            val avgOracleStepsSuccess = if (oracleSuccess.isEmpty()) 0.0 else oracleSuccess.map { it.oracle.steps }.average()
            val avgOracleStepsBeforeFailure = if (oracleFailure.isEmpty()) 0.0 else oracleFailure.map { it.oracle.steps }.average()
            val oracleZeroStepFailures = oracleFailure.count { it.oracle.zeroStepFailure }
            builder.appendLine(
                mode.name + "\t" + rows.size + "\t" + prefixHits + "\t" + pct(prefixHits, rows.size) +
                    "\t" + anyPrefixHits + "\t" + pct(anyPrefixHits, rows.size) +
                    "\t" + anyPrefix64 + "\t" + pct(anyPrefix64, rows.size) +
                    "\t" + fmt(avgBestRank) + "\t" + fmt(avgBestRank64) + "\t" + fmt(avgPrefix) + "\t" + fmt(avgConsumed) +
                    "\t" + fmt(avgBestConsumed) + "\t" +
                    rows.count { it.first.remainingConsistent } + "\t" + exact + "\t" +
                    pct(exact, rows.size) + "\t" + fmt(avgStepsSuccess) + "\t" + fmt(avgStepsBeforeFailure) +
                    "\t" + zeroStepFailures + "\t" + oracleSuccess.size + "\t" + pct(oracleSuccess.size, rows.size) +
                    "\t" + fmt(avgOracleStepsSuccess) + "\t" + fmt(avgOracleStepsBeforeFailure) +
                    "\t" + oracleZeroStepFailures,
            )
        }
        builder.appendLine()
        builder.appendLine("[fixed64SyllableBoundary]")
        val fixedNotBoundary = cases.count {
            it.input.length > MAX_QUERY_LENGTH &&
                engine.queryPrefixForBoundary(it.input, MAX_QUERY_LENGTH, LongInputBoundaryMode.SYLLABLE).length < MAX_QUERY_LENGTH
        }
        builder.appendLine("fixed64CutNotAtSyllableBoundary=" + fixedNotBoundary + "/" + cases.size)
        builder.appendLine()
        builder.appendLine("[firstStepTransitions]")
        val byCase = cases.associateWith { case -> results.filter { it.case == case }.associateBy { it.mode } }
        for ((label, pair) in listOf(
            "fixed->syllable" to (LongInputBoundaryMode.FIXED to LongInputBoundaryMode.SYLLABLE),
            "fixed->word" to (LongInputBoundaryMode.FIXED to LongInputBoundaryMode.WORD),
        )) {
            val from = pair.first
            val to = pair.second
            val gained = byCase.count { (_, modes) ->
                modes[from]?.first?.prefixHit != true && modes[to]?.first?.prefixHit == true
            }
            val lost = byCase.count { (_, modes) ->
                modes[from]?.first?.prefixHit == true && modes[to]?.first?.prefixHit != true
            }
            val both = byCase.count { (_, modes) ->
                modes[from]?.first?.prefixHit == true && modes[to]?.first?.prefixHit == true
            }
            val neither = byCase.count { (_, modes) ->
                modes[from]?.first?.prefixHit != true && modes[to]?.first?.prefixHit != true
            }
            builder.appendLine(label + ": gained=" + gained + " lost=" + lost + " both=" + both + " neither=" + neither)
        }
        builder.appendLine()
        builder.appendLine("[details]")
        builder.appendLine("id\tmode\tinputLen\tprefixLen\tprefixTop1\tbestPrefixRank\tbestPrefixRank64\tbestPrefixConsumed\tbestPrefixWord\tfirstConsumed\tfirstWord\tremainingConsistent\tcompletionExact\tsteps\tfinalText")
        results.forEach { row ->
            builder.appendLine(
                row.case.id + "\t" + row.mode.name + "\t" + row.case.input.length + "\t" +
                    row.first.prefixLength + "\t" + row.first.prefixHit + "\t" +
                    row.first.bestPrefixRank + "\t" + row.first.bestPrefixRank64 + "\t" + row.first.bestPrefixConsumed + "\t" +
                    clean(row.first.bestPrefixWord) + "\t" + row.first.candidateConsumed + "\t" +
                    clean(row.first.candidateWord) + "\t" +
                    row.first.remainingConsistent + "\t" + row.completion.exact + "\t" +
                    row.completion.steps + "\t" + clean(row.completion.finalText),
            )
        }
        return builder.toString()
    }

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

    private fun pct(value: Int, total: Int): String =
        if (total == 0) "0.0%" else String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total)

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
        const val SEARCH_LIMIT = 8
        const val MAX_COMPLETION_STEPS = 30
    }
}
