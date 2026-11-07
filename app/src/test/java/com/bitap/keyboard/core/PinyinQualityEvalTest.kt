package com.bitap.keyboard.core

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Independent pinyin input-quality evaluation.
 *
 * Reads eval/pinyin_cases_dev.tsv and eval/pinyin_cases_holdout.tsv, runs
 * [PinyinEngine.search] for every case and writes a deterministic text report
 * under build/. The holdout section is aggregate-only on purpose: its concrete
 * sentences must never be handed to the engine implementation as tuning data.
 *
 * Run from either the main checkout or the baseline worktree:
 *
 *   .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinQualityEvalTest" --rerun-tasks
 *
 * Set PINYIN_EVAL_STAGE=after to write build/pinyin-quality-eval-after.txt
 * (default: baseline).
 */
class PinyinQualityEvalTest {

    private data class EvalCase(
        val id: String,
        val category: String,
        val input: String,
        val expected: List<String>,
        val note: String,
    )

    private data class CaseResult(
        val case: EvalCase,
        val rank: Int,
        val candidates: List<String>,
        val latencyNanos: Long,
        val scored: Boolean,
        val failureReason: String?,
    ) {
        val top1: Boolean get() = rank == 0
        val top5: Boolean get() = rank in 0..4
        val taps: Int get() = if (rank >= 0) rank + 1 else MISS_TAPS
    }

    private data class Stats(
        val label: String,
        val results: List<CaseResult>,
    ) {
        val n: Int get() = results.size
        val top1: Int get() = results.count { it.top1 }
        val top5: Int get() = results.count { it.top5 }
        val tapTotal: Int get() = results.sumOf { it.taps }
        val misses: Int get() = results.count { !it.top5 }
    }

    private val categoryOrder = listOf(
        "daily_chat",
        "time_place",
        "work_comm",
        "unseen_sentence",
        "homophone",
        "long_segment",
        "mixed_en",
        "number_punct",
        "shorthand",
        "mixed_pinyin",
        "typo",
    )

    private val categoryLabels = mapOf(
        "daily_chat" to "日常聊天",
        "time_place" to "时间地点",
        "work_comm" to "工作沟通",
        "unseen_sentence" to "未收录整句/多种表达",
        "homophone" to "同音词歧义",
        "long_segment" to "长句和分段选词",
        "mixed_en" to "中英文混输",
        "number_punct" to "数字与标点",
        "shorthand" to "简拼",
        "mixed_pinyin" to "混拼",
        "typo" to "常见错拼",
    )

    @Test
    fun evaluateIndependentPinyinCorpus() {
        val root = findRepoRoot()
        val dictionaryFile = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assumeTrue("bundled dictionary not found: " + dictionaryFile.absolutePath, dictionaryFile.isFile)
        val devFile = File(root, "eval/pinyin_cases_dev.tsv")
        val holdoutFile = File(root, "eval/pinyin_cases_holdout.tsv")
        assumeTrue("dev corpus not found: " + devFile.absolutePath, devFile.isFile)
        assumeTrue("holdout corpus not found: " + holdoutFile.absolutePath, holdoutFile.isFile)

        val devCases = loadCases(devFile)
        val holdoutCases = loadCases(holdoutFile)
        assertTrue("dev corpus needs >=120 cases but was " + devCases.size, devCases.size >= 120)
        assertTrue("holdout corpus needs >=120 cases but was " + holdoutCases.size, holdoutCases.size >= 120)
        assertTrue("total corpus needs >=240 cases", devCases.size + holdoutCases.size >= 240)

        val categories = (devCases + holdoutCases).map { it.category }.toSet()
        categoryOrder.forEach { category ->
            assertTrue("corpus misses category " + category, categories.contains(category))
        }
        val duplicateInputs = (devCases + holdoutCases).groupBy { it.input }.filterValues { it.size > 1 }
        assertTrue("duplicate inputs across corpus: " + duplicateInputs.keys, duplicateInputs.isEmpty())

        val dictionaryWords = loadDictionaryWords(dictionaryFile)
        val engine = dictionaryFile.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue("engine failed to load dictionary", engine.isReady)

        // Warm up JIT so the measured pass reflects steady-state JVM behavior.
        (devCases.take(24) + holdoutCases.take(24)).forEach { engine.search(it.input, limit = SEARCH_LIMIT) }

        val devResults = devCases.map { runCase(engine, it, dictionaryWords) }
        val holdoutResults = holdoutCases.map { runCase(engine, it, dictionaryWords) }

        val stage = (System.getenv("PINYIN_EVAL_STAGE") ?: "baseline")
            .lowercase(Locale.ROOT)
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
            .ifEmpty { "baseline" }
        val outputFile = File(root, "build/pinyin-quality-eval-" + stage + ".txt")
        outputFile.parentFile?.mkdirs()
        val report = buildReport(root, stage, engine, dictionaryWords, devResults, holdoutResults)
        outputFile.writeText(report, Charsets.UTF_8)
        println(report)
        println("wrote " + outputFile.absolutePath)
    }

    private fun runCase(engine: PinyinEngine, case: EvalCase, dictionaryWords: Set<String>): CaseResult {
        val started = System.nanoTime()
        val candidates = engine.search(case.input, limit = SEARCH_LIMIT)
        val elapsed = System.nanoTime() - started
        val coverage = requiredCoverage(case.input)
        val allowPartial = (case.category == "long_segment" && case.note.contains("分段选词")) ||
            case.category == "mixed_en"
        var bestRank = -1
        candidates.forEachIndexed { index, candidate ->
            if (bestRank >= 0) return@forEachIndexed
            val hit = case.expected.any { alt ->
                matches(candidate, alt, case.category, allowPartial, coverage)
            }
            if (hit) bestRank = index
        }
        val scored = case.category != "mixed_en" && case.input.none { it.isDigit() }
        val failureReason = if (bestRank in 0..4) null else {
            classifyFailure(case, bestRank, candidates.map { it.word }, dictionaryWords)
        }
        return CaseResult(case, bestRank, candidates.map { it.word }, elapsed, scored, failureReason)
    }

    /**
     * Candidate matching for one accepted answer. Whole-sentence expectations
     * must cover all typed characters; prefix-selection cases (分段选词) and
     * mixed Chinese/English cases may match a shorter Chinese candidate whose
     * tail is committed as raw text.
     */
    private fun matches(
        candidate: Candidate,
        expectedAlt: String,
        category: String,
        allowPartial: Boolean,
        coverage: Int,
    ): Boolean {
        val candidateText = normalizeForMatch(candidate.word)
        val expectedText = normalizeForMatch(expectedAlt)
        if (candidateText.isEmpty() || expectedText.isEmpty()) return false
        if (category == "mixed_en") {
            // Mixed CN/EN cases are interaction items: only the leading Chinese
            // prefix is expected to be produced by one pinyin selection, and the
            // English tail is committed raw afterwards. The candidate must equal
            // that whole Chinese prefix (>=2 chars); a single-character partial
            // match ("我") must not count as the expected "我要买iPhone" prefix.
            val cjkPrefix = buildString {
                for (char in expectedAlt) {
                    if (!isCjk(char)) break
                    append(char)
                }
            }
            val prefixText = normalizeForMatch(cjkPrefix)
            return prefixText.length >= 2 && candidateText == prefixText
        }
        if (candidateText == expectedText) {
            return allowPartial || candidate.consumedInputLength >= coverage
        }
        return false
    }

    private fun classifyFailure(
        case: EvalCase,
        bestRank: Int,
        topCandidates: List<String>,
        dictionaryWords: Set<String>,
    ): String {
        if (case.category == "typo" || case.category == "shorthand" || case.category == "mixed_pinyin") {
            return "容错"
        }
        if (case.category == "mixed_en" || case.input.any { it.isDigit() }) {
            return "交互"
        }
        // Target existed in the returned page but below the top five: pure ranking.
        if (bestRank >= 5) return "排序"
        if (topCandidates.isEmpty()) return "切分"
        val alternatives = case.expected.map { normalizeForMatch(it) }.filter { it.isNotEmpty() }
        if (alternatives.any { dictionaryWords.contains(it) }) return "排序"
        val target = alternatives.firstOrNull() ?: return "排序"
        if (!isComposable(target, dictionaryWords)) return "缺词"
        // Same-length candidate sharing the first two characters: the skeleton was
        // found but homophone/wording choice differs, which is a ranking problem.
        val sameSkeleton = topCandidates.any { candidate ->
            candidate.length == target.length && commonPrefixLength(target, candidate) >= 2
        }
        if (sameSkeleton) return "排序"
        // Otherwise a candidate that shares at least half of the target characters
        // means the decoder reached the right region but chose another syllable path.
        val maxOverlap = topCandidates.maxOfOrNull { candidateOverlap(target, it) } ?: 0.0
        if (maxOverlap >= 0.5) return "切分"
        return "排序"
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        var index = 0
        while (index < left.length && index < right.length && left[index] == right[index]) index++
        return index
    }

    /** Character-multiset overlap ratio, used only to separate segmentation failures from ranking. */
    private fun candidateOverlap(expected: String, candidate: String): Double {
        if (expected.isEmpty() || candidate.isEmpty()) return 0.0
        val remaining = HashMap<Char, Int>()
        expected.forEach { remaining[it] = (remaining[it] ?: 0) + 1 }
        var common = 0
        candidate.forEach { char ->
            val count = remaining[char] ?: 0
            if (count > 0) {
                common++
                remaining[char] = count - 1
            }
        }
        return common.toDouble() / expected.length
    }

    private fun isComposable(text: String, words: Set<String>): Boolean {
        var position = 0
        while (position < text.length) {
            var matched = 0
            val maxLength = minOf(10, text.length - position)
            for (length in maxLength downTo 1) {
                if (words.contains(text.substring(position, position + length))) {
                    matched = length
                    break
                }
            }
            if (matched == 0) return false
            position += matched
        }
        return true
    }

    private fun buildReport(
        root: File,
        stage: String,
        engine: PinyinEngine,
        dictionaryWords: Set<String>,
        devResults: List<CaseResult>,
        holdoutResults: List<CaseResult>,
    ): String {
        val builder = StringBuilder()
        builder.appendLine("# Bitap pinyin input-quality evaluation")
        builder.appendLine("stage=" + stage)
        builder.appendLine("generatedAt=" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        builder.appendLine("hostOs=" + System.getProperty("os.name") + " " + System.getProperty("os.version"))
        builder.appendLine("jvm=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"))
        builder.appendLine("deviceReference=22021211RC / Android 16 (P0 device; JVM latency is reference only)")
        builder.appendLine("repo=" + root.absolutePath)
        builder.appendLine("dictionaryEntries=" + engine.entryCount)
        builder.appendLine(
            "corpus.dev=" + devResults.size + " corpus.holdout=" + holdoutResults.size +
                " corpus.total=" + (devResults.size + holdoutResults.size),
        )
        builder.appendLine("searchLimit=" + SEARCH_LIMIT)
        builder.appendLine()

        appendSetSummary(builder, "dev", devResults, true)
        builder.appendLine()
        appendSetSummary(builder, "holdout", holdoutResults, false)
        builder.appendLine()

        val devScored = devResults.filter { it.scored }
        val holdScored = holdoutResults.filter { it.scored }
        builder.appendLine("[summary]")
        builder.appendLine(
            "SUMMARY stage=" + stage +
                " dev.n=" + devResults.size +
                " dev.top1=" + ratio(devResults.count { it.top1 }, devResults.size) +
                " dev.top5=" + ratio(devResults.count { it.top5 }, devResults.size) +
                " dev.avgTaps=" + averageTaps(devResults) +
                " holdout.n=" + holdoutResults.size +
                " holdout.top1=" + ratio(holdoutResults.count { it.top1 }, holdoutResults.size) +
                " holdout.top5=" + ratio(holdoutResults.count { it.top5 }, holdoutResults.size) +
                " holdout.avgTaps=" + averageTaps(holdoutResults),
        )
        builder.appendLine(
            "SUMMARY_ENGINE stage=" + stage +
                " dev.n=" + devScored.size +
                " dev.top1=" + ratio(devScored.count { it.top1 }, devScored.size) +
                " dev.top5=" + ratio(devScored.count { it.top5 }, devScored.size) +
                " dev.avgTaps=" + averageTaps(devScored) +
                " holdout.n=" + holdScored.size +
                " holdout.top1=" + ratio(holdScored.count { it.top1 }, holdScored.size) +
                " holdout.top5=" + ratio(holdScored.count { it.top5 }, holdScored.size) +
                " holdout.avgTaps=" + averageTaps(holdScored),
        )
        builder.appendLine(
            "SUMMARY_STAGE stage=" + stage +
                " devTop1Pct=" + percent(devResults.count { it.top1 }, devResults.size) +
                " devTop5Pct=" + percent(devResults.count { it.top5 }, devResults.size) +
                " holdoutTop1Pct=" + percent(holdoutResults.count { it.top1 }, holdoutResults.size) +
                " holdoutTop5Pct=" + percent(holdoutResults.count { it.top5 }, holdoutResults.size),
        )
        return builder.toString()
    }

    private fun appendSetSummary(builder: StringBuilder, label: String, results: List<CaseResult>, detail: Boolean) {
        val scored = results.filter { it.scored }
        val all = Stats("all", results)
        val engine = Stats("engine", scored)
        builder.appendLine("[" + label + "]")
        builder.appendLine("allCases=" + all.n + " engineScoredCases=" + engine.n + " interactionExcluded=" + (all.n - engine.n))
        builder.appendLine("all.top1=" + all.top1 + "/" + all.n + " (" + percent(all.top1, all.n) + "%)")
        builder.appendLine("all.top5=" + all.top5 + "/" + all.n + " (" + percent(all.top5, all.n) + "%)")
        builder.appendLine("all.avgTaps=" + averageTaps(results) + " all.totalTaps=" + all.tapTotal)
        builder.appendLine("engine.top1=" + engine.top1 + "/" + engine.n + " (" + percent(engine.top1, engine.n) + "%)")
        builder.appendLine("engine.top5=" + engine.top5 + "/" + engine.n + " (" + percent(engine.top5, engine.n) + "%)")
        builder.appendLine("engine.avgTaps=" + averageTaps(scored) + " engine.totalTaps=" + engine.tapTotal)
        appendLatency(builder, results)
        builder.appendLine("byCategory:")
        categoryOrder.forEach { category ->
            val categoryResults = results.filter { it.case.category == category }
            if (categoryResults.isEmpty()) return@forEach
            val categoryScored = categoryResults.filter { it.scored }
            val line = StringBuilder()
            line.append("  ").append(category).append(" (").append(categoryLabels[category] ?: category).append("): ")
            line.append("n=").append(categoryResults.size)
            line.append(" top1=").append(categoryResults.count { it.top1 }).append("/").append(categoryResults.size)
            line.append(" top5=").append(categoryResults.count { it.top5 }).append("/").append(categoryResults.size)
            line.append(" avgTaps=").append(averageTaps(categoryResults))
            if (categoryScored.size != categoryResults.size) {
                line.append(" engineTop1=").append(categoryScored.count { it.top1 }).append("/").append(categoryScored.size)
                line.append(" engineTop5=").append(categoryScored.count { it.top5 }).append("/").append(categoryScored.size)
            }
            builder.appendLine(line.toString())
        }
        val reasons = LinkedHashMap<String, Int>()
        results.filter { !it.top5 }.forEach { result ->
            val reason = result.failureReason ?: "排序"
            reasons[reason] = (reasons[reason] ?: 0) + 1
        }
        builder.appendLine("failureReasons=" + if (reasons.isEmpty()) "none" else reasons.entries.joinToString(" ") { it.key + "=" + it.value })
        if (!detail) return

        builder.appendLine("casesDetail=")
        results.forEach { result ->
            val status = when {
                result.rank == 0 -> "TOP1"
                result.rank in 1..4 -> "TOP" + (result.rank + 1)
                result.rank >= 5 -> "RANK" + (result.rank + 1)
                else -> "MISS"
            }
            builder.appendLine(
                "  " + result.case.id +
                    " category=" + result.case.category +
                    " result=" + status +
                    " rank=" + result.rank +
                    " taps=" + result.taps +
                    " scored=" + result.scored +
                    " reason=" + (result.failureReason ?: "-") +
                    " input=" + result.case.input +
                    " expected=" + result.case.expected.joinToString("|") +
                    " got=" + result.candidates.take(5).joinToString("|"),
            )
        }
    }

    private fun appendLatency(builder: StringBuilder, results: List<CaseResult>) {
        val values = results.map { it.latencyNanos }.sorted()
        if (values.isEmpty()) {
            builder.appendLine("latencyMs: n/a")
            return
        }
        fun percentile(percent: Int): Double {
            val index = ((values.size - 1) * percent / 100).coerceIn(0, values.lastIndex)
            return values[index] / 1_000_000.0
        }
        builder.appendLine(
            "latencyMs: p50=" + formatMillis(percentile(50)) +
                " p95=" + formatMillis(percentile(95)) +
                " p99=" + formatMillis(percentile(99)) +
                " max=" + formatMillis(values.last() / 1_000_000.0),
        )
    }

    private fun loadCases(file: File): List<EvalCase> = file.useLines { lines ->
        lines.mapNotNull { raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("#")) return@mapNotNull null
            val parts = line.split('\t')
            require(parts.size >= 5) { "malformed corpus line: " + line }
            EvalCase(
                id = parts[0].trim(),
                category = parts[1].trim(),
                input = parts[2].trim(),
                expected = parts[3].split('|').map { it.trim() }.filter { it.isNotEmpty() },
                note = parts[4].trim(),
            )
        }.toList()
    }

    private fun loadDictionaryWords(file: File): Set<String> {
        val words = HashSet<String>()
        file.forEachLine(Charsets.UTF_8) { raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachLine
            val parts = line.split('\t')
            if (parts.size < 2) return@forEachLine
            val word = parts[1].trim()
            if (word.isNotEmpty()) words.add(word)
        }
        return words
    }

    private fun findRepoRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "eval/pinyin_cases_dev.tsv").isFile &&
                File(directory, "app/src/main/assets/pinyin_dict.tsv").isFile
            ) {
                return directory
            }
            directory = directory.parentFile
        }
        error("repository root not found from " + System.getProperty("user.dir"))
    }

    private fun requiredCoverage(input: String): Int =
        input.count { it.isLetterOrDigit() || it == '\'' }

    private fun normalizeForMatch(text: String): String =
        text.filter { it.isLetterOrDigit() || isCjk(it) }

    private fun isCjk(char: Char): Boolean =
        char.code in 0x3400..0x4DBF || char.code in 0x4E00..0x9FFF || char.code in 0xF900..0xFAFF

    private fun averageTaps(results: List<CaseResult>): String =
        if (results.isEmpty()) "0.00" else String.format(Locale.ROOT, "%.2f", results.sumOf { it.taps }.toDouble() / results.size)

    private fun ratio(value: Int, total: Int): String =
        value.toString() + "/" + total + "(" + percent(value, total) + "%)"

    private fun percent(value: Int, total: Int): String =
        if (total == 0) "0.0" else String.format(Locale.ROOT, "%.1f", value * 100.0 / total)

    private fun formatMillis(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    companion object {
        private const val SEARCH_LIMIT = 8
        private const val MISS_TAPS = 9
    }
}
