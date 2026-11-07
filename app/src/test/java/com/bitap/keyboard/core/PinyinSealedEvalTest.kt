package com.bitap.keyboard.core

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Sealed pinyin quality evaluation.
 *
 * The corpus lives outside the tracked eval directory in
 * .tmp/sealed/pinyin_cases_sealed.tsv and is only used once, on the frozen
 * candidate version. This test is skipped unless PINYIN_SEALED_RUN=1 so that
 * ordinary test-suite runs cannot consume the sealed set accidentally.
 *
 * Output is aggregate-only: no case ids, inputs, expected strings or per-case
 * candidates are written anywhere. Run with:
 *
 *   $env:PINYIN_SEALED_RUN='1'; $env:PINYIN_SEALED_STAGE='final'
 *   .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinSealedEvalTest" --rerun-tasks
 */
class PinyinSealedEvalTest {

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

    private val categoryOrder = listOf(
        "long_natural",
        "shorthand_ambiguity",
        "mixed_pinyin",
        "typo_correction",
        "english_abbrev",
        "number_punct",
    )

    private val categoryLabels = mapOf(
        "long_natural" to "自然长句",
        "shorthand_ambiguity" to "简拼歧义",
        "mixed_pinyin" to "混拼",
        "typo_correction" to "纠错",
        "english_abbrev" to "英文缩写",
        "number_punct" to "数字与标点",
    )

    @Test
    fun evaluateSealedCorpusOnce() {
        assumeTrue(
            "sealed evaluation runs only when PINYIN_SEALED_RUN=1 (frozen candidate only)",
            System.getenv("PINYIN_SEALED_RUN") == "1",
        )
        val root = findRepoRoot()
        val corpusFile = File(root, ".tmp/sealed/pinyin_cases_sealed.tsv")
        val dictionaryFile = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assumeTrue("sealed corpus not found: " + corpusFile.absolutePath, corpusFile.isFile)
        assumeTrue("dictionary not found: " + dictionaryFile.absolutePath, dictionaryFile.isFile)
        val cases = loadCases(corpusFile)
        assertTrue("sealed corpus needs >=120 cases but was " + cases.size, cases.size >= 120)
        categoryOrder.forEach { category ->
            assertTrue("sealed corpus misses category " + category, cases.any { it.category == category })
        }
        assertTrue("sealed corpus must not duplicate inputs", cases.map { it.input }.toSet().size == cases.size)

        val dictionaryWords = loadDictionaryWords(dictionaryFile)
        val engine = dictionaryFile.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue("engine failed to load dictionary", engine.isReady)

        // Warm up JIT on a fixed subset without writing any per-case output.
        cases.take(24).forEach { engine.search(it.input, limit = SEARCH_LIMIT) }

        val results = cases.map { runCase(engine, it, dictionaryWords) }
        val stage = (System.getenv("PINYIN_SEALED_STAGE") ?: "sealed")
            .lowercase(Locale.ROOT)
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
            .ifEmpty { "sealed" }
        val outputFile = File(root, "build/pinyin-sealed-eval-" + stage + ".txt")
        outputFile.parentFile?.mkdirs()
        val report = buildReport(stage, engine, results)
        outputFile.writeText(report, Charsets.UTF_8)
        println(report)
        println("wrote " + outputFile.absolutePath)
    }

    private fun runCase(engine: PinyinEngine, case: EvalCase, dictionaryWords: Set<String>): CaseResult {
        val started = System.nanoTime()
        val candidates = engine.search(case.input, limit = SEARCH_LIMIT)
        val elapsed = System.nanoTime() - started
        val coverage = requiredCoverage(case.input)
        val allowPartial = case.category == "english_abbrev" ||
            (case.category == "number_punct" && case.input.any { it.isDigit() })
        var bestRank = -1
        candidates.forEachIndexed { index, candidate ->
            if (bestRank >= 0) return@forEachIndexed
            if (case.expected.any { alt -> matches(candidate, alt, case.category, allowPartial, coverage) }) {
                bestRank = index
            }
        }
        val scored = case.category != "english_abbrev" && case.input.none { it.isDigit() }
        val failureReason = if (bestRank in 0..4) null else {
            classifyFailure(case, bestRank, candidates.map { it.word }, dictionaryWords)
        }
        return CaseResult(case, bestRank, candidates.map { it.word }, elapsed, scored, failureReason)
    }

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
        if (category == "english_abbrev") {
            val prefix = buildString {
                for (char in expectedAlt) {
                    if (!isCjk(char)) break
                    append(char)
                }
            }
            val prefixText = normalizeForMatch(prefix)
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
        if (case.category in setOf("typo_correction", "shorthand_ambiguity", "mixed_pinyin")) return "容错"
        if (case.category == "english_abbrev" || case.input.any { it.isDigit() }) return "交互"
        if (bestRank >= 5) return "排序"
        if (topCandidates.isEmpty()) return "切分"
        val alternatives = case.expected.map { normalizeForMatch(it) }.filter { it.isNotEmpty() }
        if (alternatives.any { dictionaryWords.contains(it) }) return "排序"
        val target = alternatives.firstOrNull() ?: return "排序"
        if (!isComposable(target, dictionaryWords)) return "缺词"
        val sameSkeleton = topCandidates.any { candidate ->
            candidate.length == target.length && commonPrefixLength(target, candidate) >= 2
        }
        if (sameSkeleton) return "排序"
        val maxOverlap = topCandidates.maxOfOrNull { candidateOverlap(target, it) } ?: 0.0
        if (maxOverlap >= 0.5) return "切分"
        return "排序"
    }

    private fun buildReport(stage: String, engine: PinyinEngine, results: List<CaseResult>): String {
        val scored = results.filter { it.scored }
        val builder = StringBuilder()
        builder.appendLine("# Bitap sealed pinyin evaluation (aggregate only)")
        builder.appendLine("stage=" + stage)
        builder.appendLine("generatedAt=" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        builder.appendLine("hostOs=" + System.getProperty("os.name") + " " + System.getProperty("os.version"))
        builder.appendLine("jvm=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"))
        builder.appendLine("deviceReference=22021211RC / Android 16 (P0 device; JVM latency is reference only)")
        builder.appendLine("dictionaryEntries=" + engine.entryCount)
        builder.appendLine("sealedCases=" + results.size + " engineScored=" + scored.size + " interactionExcluded=" + (results.size - scored.size))
        builder.appendLine("searchLimit=" + SEARCH_LIMIT)
        builder.appendLine()
        builder.appendLine("[all]")
        builder.appendLine("top1=" + ratio(results.count { it.top1 }, results.size))
        builder.appendLine("top5=" + ratio(results.count { it.top5 }, results.size))
        builder.appendLine("avgTaps=" + averageTaps(results) + " totalTaps=" + results.sumOf { it.taps })
        appendLatency(builder, results)
        builder.appendLine()
        builder.appendLine("[engineScored]")
        builder.appendLine("top1=" + ratio(scored.count { it.top1 }, scored.size))
        builder.appendLine("top5=" + ratio(scored.count { it.top5 }, scored.size))
        builder.appendLine("avgTaps=" + averageTaps(scored) + " totalTaps=" + scored.sumOf { it.taps })
        builder.appendLine()
        builder.appendLine("[byCategory]")
        categoryOrder.forEach { category ->
            val categoryResults = results.filter { it.case.category == category }
            val categoryScored = categoryResults.filter { it.scored }
            val line = StringBuilder()
            line.append(category).append(" (").append(categoryLabels[category] ?: category).append("): ")
            line.append("n=").append(categoryResults.size)
            line.append(" top1=").append(categoryResults.count { it.top1 }).append("/").append(categoryResults.size)
            line.append(" top5=").append(categoryResults.count { it.top5 }).append("/").append(categoryResults.size)
            line.append(" avgTaps=").append(averageTaps(categoryResults))
            if (categoryScored.size != categoryResults.size) {
                line.append(" engineTop1=").append(categoryScored.count { it.top1 }).append("/").append(categoryScored.size)
                line.append(" engineTop5=").append(categoryScored.count { it.top5 }).append("/").append(categoryScored.size)
                line.append(" engineAvgTaps=").append(averageTaps(categoryScored))
            }
            builder.appendLine(line.toString())
        }
        val reasons = LinkedHashMap<String, Int>()
        results.filter { !it.top5 }.forEach { result ->
            val reason = result.failureReason ?: "排序"
            reasons[reason] = (reasons[reason] ?: 0) + 1
        }
        builder.appendLine("failureReasons=" + if (reasons.isEmpty()) "none" else reasons.entries.joinToString(" ") { it.key + "=" + it.value })
        builder.appendLine()
        builder.appendLine(
            "SUMMARY_SEALED stage=" + stage +
                " n=" + results.size +
                " all.top1=" + ratio(results.count { it.top1 }, results.size) +
                " all.top5=" + ratio(results.count { it.top5 }, results.size) +
                " all.avgTaps=" + averageTaps(results) +
                " engine.top1=" + ratio(scored.count { it.top1 }, scored.size) +
                " engine.top5=" + ratio(scored.count { it.top5 }, scored.size) +
                " engine.avgTaps=" + averageTaps(scored),
        )
        return builder.toString()
    }

    private fun appendLatency(builder: StringBuilder, results: List<CaseResult>) {
        val values = results.map { it.latencyNanos }.sorted()
        if (values.isEmpty()) {
            builder.appendLine("latencyMs=n/a")
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
            require(parts.size >= 5) { "malformed sealed corpus line" }
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
            if (File(directory, ".tmp/sealed/pinyin_cases_sealed.tsv").isFile &&
                File(directory, "app/src/main/assets/pinyin_dict.tsv").isFile
            ) {
                return directory
            }
            directory = directory.parentFile
        }
        error("repository root not found from " + System.getProperty("user.dir"))
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

    private fun commonPrefixLength(left: String, right: String): Int {
        var index = 0
        while (index < left.length && index < right.length && left[index] == right[index]) index++
        return index
    }

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

    private fun requiredCoverage(input: String): Int = input.count { it.isLetterOrDigit() || it == '\'' }

    private fun normalizeForMatch(text: String): String = text.filter { it.isLetterOrDigit() || isCjk(it) }

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
