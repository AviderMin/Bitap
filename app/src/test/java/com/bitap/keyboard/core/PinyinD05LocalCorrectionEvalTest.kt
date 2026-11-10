package com.bitap.keyboard.core

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-E D05 local correction window evaluation (development diagnostic; NOT acceptance).
 *
 * Mechanism: default-off EngineTuning.localCorrectionWindowEnabled. The enabled
 * view only adds bounded tail-window edit-distance-1 partial correction
 * candidates; production defaults stay false/0.
 *
 * Reports:
 *  - D05 typo 100: baseline vs enabled Top1/Top5/rankCost/oracle/fullCoverage and latency.
 *  - D05 natural 667 (short+medium+long): normal-input false-correction rate,
 *    extra isCorrection candidates in top8/top64, Top1/Top5 changes and latency.
 *  - Proper-noun / English / numeric fallback samples.
 */
class PinyinD05LocalCorrectionEvalTest {

    private data class EvalCase(
        val id: String,
        val category: String,
        val input: String,
        val expected: List<String>,
        val expectedCjk: String,
        val coverage: Int,
    )

    private data class Row(
        val case: EvalCase,
        val rank8: Int,
        val rank64: Int,
        val latencyNanos: Long,
        val fullCoverage: Boolean,
        val top1Word: String,
        val top1IsCorrection: Boolean,
        val top8Corrections: Set<String>,
        val top64Corrections: Set<String>,
    ) {
        val top1: Boolean get() = rank8 == 0
        val top5: Boolean get() = rank8 in 0..4
        val rankCost: Int get() = if (rank8 in 0..4) rank8 + 1 else MISS_RANK_COST
    }

    private data class Metrics(
        val n: Int,
        val top1: Int,
        val top5: Int,
        val rankCost: Double,
        val oracle: Int,
        val fullCoverage: Int,
        val p50: Double,
        val p95: Double,
    )

    private data class CorrectionDelta(
        val top8ExtraCases: Int,
        val top64ExtraCases: Int,
        val extraTop8Words: Int,
        val extraTop64Words: Int,
        val top1IsCorrection: Int,
        val top1Changed: Int,
        val top5Gain: Int,
        val top5Loss: Int,
        val top1Gain: Int,
        val top1Loss: Int,
        val oracleGain: Int,
        val oracleLoss: Int,
        val rankCostDelta: Double,
    )

    @Test
    fun evaluateLocalCorrectionWindow() {
        val root = findRepoRoot()
        val devFile = File(root, "eval/d05/dev/dev.tsv")
        val dictFile = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assertTrue("D05 dev missing", devFile.isFile)
        assertTrue("dictionary missing", dictFile.isFile)
        assertEquals("D05 dev hash", DEV_SHA256, sha256(devFile))

        val all = loadCases(devFile)
        val natural = all.filter { it.category.startsWith("natural_") && it.coverage <= MAX_SEARCH_INPUT_LENGTH }
        val typo = all.filter { it.category == "typo" }
        assertEquals("natural 667", 667, natural.size)
        assertEquals("typo 100", 100, typo.size)

        val engine = dictFile.useLines { PinyinEngine.fromLines(it) }
        assertTrue(engine.isReady)
        engine.setLearningEnabled(false)
        val tuning = PinyinEngine.EngineTuning()
        assertEquals("production default local correction off", false, tuning.localCorrectionWindowEnabled)
        assertEquals("production default tail limit", 12, tuning.localCorrectionTailLimit)
        assertEquals("production default max window", 12, tuning.localCorrectionMaxWindow)
        assertEquals("production default budget", 8, tuning.localCorrectionBudget)
        val enabledTuning = tuning.copy(
            localCorrectionWindowEnabled = true,
            localCorrectionTailLimit = 12,
            localCorrectionMaxWindow = 12,
            localCorrectionBudget = 32,
        )
        val enabled = engine.withTuning(enabledTuning)

        // Production default view must be byte-for-byte identical to the default engine.
        natural.take(40).forEach { case ->
            assertEquals(
                "production view unchanged: " + case.id,
                engine.search(case.input, SEARCH_LIMIT).map { it.word },
                engine.withTuning(PinyinEngine.EngineTuning()).search(case.input, SEARCH_LIMIT).map { it.word },
            )
        }

        val naturalBaseline = measure(engine, natural)
        val naturalEnabled = measure(enabled, natural)
        val typoBaseline = measure(engine, typo)
        val typoEnabled = measure(enabled, typo)

        val report = buildReport(root, devFile, dictFile, engine, tuning, enabledTuning, natural, typo, naturalBaseline, naturalEnabled, typoBaseline, typoEnabled)
        val reportFile = File(root, REPORT_PATH)
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report, Charsets.UTF_8)

        assertTrue("report has typo section", report.contains("[typo100]"))
        assertTrue("report has natural section", report.contains("[natural667]"))
        assertTrue("report has fallback section", report.contains("[fallbackSamples]"))
        assertTrue("report has conclusion", report.contains("[conclusion]"))
        assertTrue("report has parameters", report.contains("localCorrectionWindowEnabled=true"))
        println("D05 local correction diagnostic wrote " + reportFile.absolutePath)
    }

    private fun measure(engine: PinyinEngine, cases: List<EvalCase>): List<Row> {
        cases.take(24).forEach { case ->
            engine.search(case.input, limit = SEARCH_LIMIT)
            engine.search(case.input, limit = FULL_LIMIT)
        }
        return cases.map { case ->
            val started = System.nanoTime()
            val top8 = engine.search(case.input, limit = SEARCH_LIMIT)
            val latency = System.nanoTime() - started
            val pool = engine.search(case.input, limit = FULL_LIMIT)
            Row(
                case = case,
                rank8 = top8.indexOfFirst { candidateMatches(it, case) },
                rank64 = pool.indexOfFirst { candidateMatches(it, case) },
                latencyNanos = latency,
                fullCoverage = pool.any { it.consumedInputLength >= case.coverage },
                top1Word = top8.firstOrNull()?.word ?: "",
                top1IsCorrection = top8.firstOrNull()?.isCorrection ?: false,
                top8Corrections = top8.filter { it.isCorrection }.map { it.word }.toSet(),
                top64Corrections = pool.filter { it.isCorrection }.map { it.word }.toSet(),
            )
        }
    }

    private fun buildReport(
        root: File,
        devFile: File,
        dictFile: File,
        engine: PinyinEngine,
        defaultTuning: PinyinEngine.EngineTuning,
        enabledTuning: PinyinEngine.EngineTuning,
        natural: List<EvalCase>,
        typo: List<EvalCase>,
        naturalBaseline: List<Row>,
        naturalEnabled: List<Row>,
        typoBaseline: List<Row>,
        typoEnabled: List<Row>,
    ): String {
        val builder = StringBuilder()
        builder.appendLine("# Bitap D05 long-input local correction window diagnostic (development diagnostic; NOT acceptance)")
        builder.appendLine("generatedBy=PinyinD05LocalCorrectionEvalTest")
        builder.appendLine("corpus=" + relativePath(root, devFile))
        builder.appendLine("corpusSha256=" + sha256(devFile))
        builder.appendLine("dictionary=" + relativePath(root, dictFile))
        builder.appendLine("dictionarySha256=" + sha256(dictFile))
        builder.appendLine("dictionaryEntries=" + engine.entryCount)
        builder.appendLine("denominator.natural667=" + natural.size + " (natural_short+natural_medium+natural_long, <=64)")
        builder.appendLine("denominator.typo100=" + typo.size)
        builder.appendLine("productionDefault=localCorrectionWindowEnabled=false localCorrectionTailLimit=12 localCorrectionMaxWindow=12 localCorrectionBudget=8")
        builder.appendLine("enabledVariant=localCorrectionWindowEnabled=true localCorrectionTailLimit=" + enabledTuning.localCorrectionTailLimit +
            " localCorrectionMaxWindow=" + enabledTuning.localCorrectionMaxWindow + " localCorrectionBudget=" + enabledTuning.localCorrectionBudget)
        builder.appendLine("searchLimit=" + SEARCH_LIMIT + " oracleLimit=" + FULL_LIMIT + " note=JVM single-process latency, not device latency")
        builder.appendLine()
        appendScope(builder, "typo100", typo, typoBaseline, typoEnabled)
        builder.appendLine()
        appendScope(builder, "natural667", natural, naturalBaseline, naturalEnabled)
        builder.appendLine()
        appendCorrectionDelta(builder, "typo100", typoBaseline, typoEnabled)
        builder.appendLine()
        appendCorrectionDelta(builder, "natural667", naturalBaseline, naturalEnabled)
        builder.appendLine()
        appendFallbackSamples(builder, engine, engine.withTuning(enabledTuning))
        builder.appendLine()
        appendSyntheticReference(builder)
        builder.appendLine()
        appendConclusion(builder, typoBaseline, typoEnabled, naturalBaseline, naturalEnabled)
        return builder.toString()
    }

    private fun appendScope(builder: StringBuilder, scope: String, cases: List<EvalCase>, baseline: List<Row>, enabled: List<Row>) {
        val base = summarize(baseline)
        val after = summarize(enabled)
        builder.appendLine("[" + scope + "]")
        builder.appendLine("metric\tbaseline\tenabled\tdelta")
        appendMetricRow(builder, "n", base.n, after.n)
        appendMetricRow(builder, "top1", base.top1, after.top1)
        appendMetricRow(builder, "top5", base.top5, after.top5)
        appendMetricRow(builder, "rankCost", fmt(base.rankCost), fmt(after.rankCost))
        appendMetricRow(builder, "oracle64", base.oracle, after.oracle)
        appendMetricRow(builder, "fullCoverage", base.fullCoverage, after.fullCoverage)
        appendMetricRow(builder, "p50ms", fmt(base.p50), fmt(after.p50))
        appendMetricRow(builder, "p95ms", fmt(base.p95), fmt(after.p95))
        builder.appendLine("top1Pct=" + pct(base.top1, base.n) + "% -> " + pct(after.top1, after.n) + "%")
        builder.appendLine("top5Pct=" + pct(base.top5, base.n) + "% -> " + pct(after.top5, after.n) + "%")
        builder.appendLine("oraclePct=" + pct(base.oracle, base.n) + "% -> " + pct(after.oracle, after.n) + "%")
        builder.appendLine("note=" + scope + " cases=" + cases.size)
    }

    private fun appendMetricRow(builder: StringBuilder, metric: String, base: Any, enabled: Any) {
        val delta = if (base is Int && enabled is Int) signed(enabled - base) else "n/a"
        builder.appendLine(metric + "\t" + base + "\t" + enabled + "\t" + delta)
    }

    private fun appendCorrectionDelta(builder: StringBuilder, scope: String, baseline: List<Row>, enabled: List<Row>) {
        val delta = correctionDelta(baseline, enabled)
        builder.appendLine("[" + scope + ".correctionImpact]")
        builder.appendLine("top8ExtraCorrectionCases=" + delta.top8ExtraCases + "/" + baseline.size)
        builder.appendLine("top64ExtraCorrectionCases=" + delta.top64ExtraCases + "/" + baseline.size)
        builder.appendLine("extraCorrectionWordsTop8=" + delta.extraTop8Words)
        builder.appendLine("extraCorrectionWordsTop64=" + delta.extraTop64Words)
        builder.appendLine("top8ExtraCorrectionRate=" + delta.top8ExtraCases + "/" + baseline.size + "(" + pct(delta.top8ExtraCases, baseline.size) + "%)")
        builder.appendLine("top64ExtraCorrectionRate=" + delta.top64ExtraCases + "/" + baseline.size + "(" + pct(delta.top64ExtraCases, baseline.size) + "%)")
        builder.appendLine("top1IsCorrectionCases=" + delta.top1IsCorrection + "/" + baseline.size)
        builder.appendLine("top1ChangedCases=" + delta.top1Changed + "/" + baseline.size)
        builder.appendLine("top1Gain=" + delta.top1Gain + " top1Loss=" + delta.top1Loss)
        builder.appendLine("top5Gain=" + delta.top5Gain + " top5Loss=" + delta.top5Loss)
        builder.appendLine("oracleGain=" + delta.oracleGain + " oracleLoss=" + delta.oracleLoss)
        builder.appendLine("rankCostDelta=" + signedFmt(delta.rankCostDelta))
    }

    private fun correctionDelta(baseline: List<Row>, enabled: List<Row>): CorrectionDelta {
        val enabledById = enabled.associateBy { it.case.id }
        var top8ExtraCases = 0
        var top64ExtraCases = 0
        var extraTop8Words = 0
        var extraTop64Words = 0
        var top1IsCorrection = 0
        var top1Changed = 0
        var top5Gain = 0
        var top5Loss = 0
        var top1Gain = 0
        var top1Loss = 0
        var oracleGain = 0
        var oracleLoss = 0
        var rankCostDelta = 0.0
        baseline.forEach { base ->
            val after = enabledById[base.case.id] ?: return@forEach
            val extra8 = after.top8Corrections - base.top8Corrections
            val extra64 = after.top64Corrections - base.top64Corrections
            if (extra8.isNotEmpty()) top8ExtraCases++
            if (extra64.isNotEmpty()) top64ExtraCases++
            extraTop8Words += extra8.size
            extraTop64Words += extra64.size
            if (after.top1IsCorrection) top1IsCorrection++
            if (after.top1Word != base.top1Word) top1Changed++
            if (!base.top1 && after.top1) top1Gain++
            if (base.top1 && !after.top1) top1Loss++
            if (!base.top5 && after.top5) top5Gain++
            if (base.top5 && !after.top5) top5Loss++
            val baseOracle = base.rank64 >= 0
            val afterOracle = after.rank64 >= 0
            if (!baseOracle && afterOracle) oracleGain++
            if (baseOracle && !afterOracle) oracleLoss++
            rankCostDelta += (after.rankCost - base.rankCost).toDouble()
        }
        return CorrectionDelta(
            top8ExtraCases = top8ExtraCases,
            top64ExtraCases = top64ExtraCases,
            extraTop8Words = extraTop8Words,
            extraTop64Words = extraTop64Words,
            top1IsCorrection = top1IsCorrection,
            top1Changed = top1Changed,
            top5Gain = top5Gain,
            top5Loss = top5Loss,
            top1Gain = top1Gain,
            top1Loss = top1Loss,
            oracleGain = oracleGain,
            oracleLoss = oracleLoss,
            rankCostDelta = if (baseline.isEmpty()) 0.0 else rankCostDelta / baseline.size,
        )
    }

    private fun appendFallbackSamples(builder: StringBuilder, engine: PinyinEngine, enabled: PinyinEngine) {
        val samples = listOf(
            "zhangxiaoming" to "generic proper noun",
            "beijingdaxue" to "generic proper noun",
            "helloworldabc" to "long English",
            "microsoftwindows" to "long English",
            "openai2026technology" to "English+digits",
            "202611091230" to "pure digits",
        )
        builder.appendLine("[fallbackSamples]")
        builder.appendLine("id\tinput\tkind\tdefaultTop1\tenabledTop1\tenabledTop1IsCorrection\tcorrectionTop8\tcorrectionTop64\tbaselineTop64\tnote")
        samples.forEachIndexed { index, (input, kind) ->
            val base8 = engine.search(input, SEARCH_LIMIT)
            val base64 = engine.search(input, FULL_LIMIT)
            val enabled8 = enabled.search(input, SEARCH_LIMIT)
            val enabled64 = enabled.search(input, FULL_LIMIT)
            val correction8 = enabled8.filter { it.isCorrection }.map { it.word }
            val correction64 = enabled64.filter { it.isCorrection }.map { it.word }
            builder.appendLine(
                "fallback-" + (index + 1) + "\t" + input + "\t" + kind + "\t" +
                    (base8.firstOrNull()?.word ?: "") + "\t" +
                    (enabled8.firstOrNull()?.word ?: "") + "\t" +
                    (enabled8.firstOrNull()?.isCorrection ?: false) + "\t" +
                    correction8.joinToString("|") + "\t" + correction64.joinToString("|") + "\t" +
                    base64.size + "\t" +
                    if (correction8.isEmpty()) "no false correction in top8" else "FALSE CORRECTION IN TOP8",
            )
        }
    }

    private fun appendSyntheticReference(builder: StringBuilder) {
        builder.appendLine("[syntheticCorrectnessReference]")
        builder.appendLine("test=PinyinLocalCorrectionSyntheticTest")
        builder.appendLine("cases=5")
        builder.appendLine("scenarios=tail-extra-delete,tail-missing-insert,tail-adjacent-swap,tail-substitution,suffix-retained")
        builder.appendLine("note=see the synthetic test output/assertions for candidate word, consumed length, isCorrection, and unconsumed suffix")
    }

    private fun appendConclusion(
        builder: StringBuilder,
        typoBaseline: List<Row>,
        typoEnabled: List<Row>,
        naturalBaseline: List<Row>,
        naturalEnabled: List<Row>,
    ) {
        val typoBase = summarize(typoBaseline)
        val typoAfter = summarize(typoEnabled)
        val naturalBase = summarize(naturalBaseline)
        val naturalAfter = summarize(naturalEnabled)
        val naturalDelta = correctionDelta(naturalBaseline, naturalEnabled)
        val typoDelta = correctionDelta(typoBaseline, typoEnabled)
        builder.appendLine("[conclusion]")
        builder.appendLine("typo100.baseline: top1=" + typoBase.top1 + "/100 top5=" + typoBase.top5 + "/100 rankCost=" + fmt(typoBase.rankCost) +
            " oracle64=" + typoBase.oracle + "/100 p50=" + fmt(typoBase.p50) + " p95=" + fmt(typoBase.p95))
        builder.appendLine("typo100.enabled: top1=" + typoAfter.top1 + "/100 top5=" + typoAfter.top5 + "/100 rankCost=" + fmt(typoAfter.rankCost) +
            " oracle64=" + typoAfter.oracle + "/100 p50=" + fmt(typoAfter.p50) + " p95=" + fmt(typoAfter.p95) +
            " top5Gain=" + typoDelta.top5Gain + " top5Loss=" + typoDelta.top5Loss)
        builder.appendLine("natural667.baseline: top1=" + naturalBase.top1 + "/667 top5=" + naturalBase.top5 + "/667 rankCost=" + fmt(naturalBase.rankCost) +
            " oracle64=" + naturalBase.oracle + "/667 p50=" + fmt(naturalBase.p50) + " p95=" + fmt(naturalBase.p95))
        builder.appendLine("natural667.enabled: top1=" + naturalAfter.top1 + "/667 top5=" + naturalAfter.top5 + "/667 rankCost=" + fmt(naturalAfter.rankCost) +
            " oracle64=" + naturalAfter.oracle + "/667 p50=" + fmt(naturalAfter.p50) + " p95=" + fmt(naturalAfter.p95))
        builder.appendLine("falseCorrection.natural667: top8ExtraCases=" + naturalDelta.top8ExtraCases + "/667 top64ExtraCases=" + naturalDelta.top64ExtraCases +
            "/667 extraTop8Words=" + naturalDelta.extraTop8Words + " extraTop64Words=" + naturalDelta.extraTop64Words +
            " top1IsCorrectionCases=" + naturalDelta.top1IsCorrection +
            " top5Gain=" + naturalDelta.top5Gain + " top5Loss=" + naturalDelta.top5Loss +
            " rankCostDelta=" + signedFmt(naturalDelta.rankCostDelta))
        val worth = typoDelta.top5Gain > 0 && naturalDelta.top5Loss == 0 && naturalDelta.top5Gain == 0 && naturalDelta.top8ExtraCases == 0
        if (worth) {
            builder.appendLine("verdict=worth_further_experiment")
            builder.appendLine("reason=typo Top5 improves without natural Top5 change or extra top8 correction cases; still needs device validation")
        } else {
            builder.appendLine("verdict=not_worth_enabling_yet")
            val reasons = mutableListOf<String>()
            if (typoDelta.top5Gain <= 0) reasons += "typo100 Top5 gain 0（D05 typo 输入全部 <=12，现有整串纠错已覆盖，长窗口不触发）"
            if (naturalDelta.top8ExtraCases > 0) reasons += "natural667 有 " + naturalDelta.top8ExtraCases + " 条 top8 额外纠错候选"
            if (naturalDelta.top64ExtraCases > 0) reasons += "natural667 有 " + naturalDelta.top64ExtraCases + " 条 top64 额外纠错候选"
            if (naturalDelta.top5Loss > 0) reasons += "natural667 Top5 回退 " + naturalDelta.top5Loss + " 条"
            if (naturalDelta.rankCostDelta > 0) reasons += "natural rankCost +" + fmt(naturalDelta.rankCostDelta)
            builder.appendLine("reason=" + if (reasons.isEmpty()) "收益不足" else reasons.joinToString("；"))
        }
        builder.appendLine("recommendation=保留 localCorrectionWindowEnabled=false 生产默认；局部窗口仅作为默认关闭实验，若继续需先解决单字词条排除与候选去重冲突，并用 D06 真实错拼/专名/英文数据复测。")
    }

    private fun summarize(rows: List<Row>): Metrics {
        val latencies = rows.map { it.latencyNanos / 1_000_000.0 }.sorted()
        return Metrics(
            n = rows.size,
            top1 = rows.count { it.top1 },
            top5 = rows.count { it.top5 },
            rankCost = if (rows.isEmpty()) 0.0 else rows.sumOf { it.rankCost }.toDouble() / rows.size,
            oracle = rows.count { it.rank64 >= 0 },
            fullCoverage = rows.count { it.fullCoverage },
            p50 = percentile(latencies, 50),
            p95 = percentile(latencies, 95),
        )
    }

    // ------------------------------------------------------------------
    // Loading / matching / formatting
    // ------------------------------------------------------------------

    private fun loadCases(file: File): List<EvalCase> = file.useLines { lines ->
        lines.mapNotNull { raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("#")) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size != 6) return@mapNotNull null
            val input = parts[2].trim()
            val expected = parts[3].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            EvalCase(
                id = parts[0].trim(),
                category = parts[1].trim(),
                input = input,
                expected = expected,
                expectedCjk = normalizeCjk(expected.first()),
                coverage = requiredCoverage(input),
            )
        }.toList()
    }

    private fun candidateMatches(candidate: Candidate, case: EvalCase): Boolean =
        candidate.consumedInputLength >= case.coverage && textMatches(case, candidate.word)

    private fun textMatches(case: EvalCase, text: String): Boolean {
        val normalized = normalizeText(text)
        return normalized.isNotEmpty() && case.expected.any { normalizeText(it) == normalized }
    }

    private fun requiredCoverage(input: String): Int = input.count { it.isLetterOrDigit() || it == '\'' }
    private fun normalizeText(text: String): String = text.filter { it.isLetterOrDigit() || isCjk(it) }
    private fun normalizeCjk(text: String): String = text.filter { isCjk(it) }
    private fun isCjk(char: Char): Boolean =
        char.code in 0x3400..0x4DBF || char.code in 0x4E00..0x9FFF || char.code in 0xF900..0xFAFF

    private fun percentile(values: List<Double>, percent: Int): Double {
        if (values.isEmpty()) return 0.0
        val index = ((values.size - 1) * percent / 100).coerceIn(0, values.lastIndex)
        return values[index]
    }

    private fun pct(value: Int, total: Int): String =
        if (total == 0) "0.0" else String.format(Locale.ROOT, "%.1f", value * 100.0 / total)

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
    private fun signed(value: Int): String = if (value >= 0) "+" + value else value.toString()
    private fun signedFmt(value: Double): String = if (value >= 0) "+" + fmt(value) else fmt(value)

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

    private fun relativePath(root: File, file: File): String = file.relativeTo(root).path.replace('\\', '/')

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
        error("repository root not found")
    }

    private companion object {
        private const val SEARCH_LIMIT = 8
        private const val FULL_LIMIT = 64
        private const val MISS_RANK_COST = 9
        private const val MAX_SEARCH_INPUT_LENGTH = 64
        private const val DEV_SHA256 = "63983c5f9a490040592d6740e2e747e2b5a574d0a068accb44f25179153110ad"
        private const val REPORT_PATH = "build/d05/local-correction.txt"
    }
}
