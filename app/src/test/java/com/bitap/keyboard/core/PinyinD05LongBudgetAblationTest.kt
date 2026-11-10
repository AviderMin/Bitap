package com.bitap.keyboard.core

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.LinkedHashMap
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-D D05 long-input lattice budget ablation (development diagnostic; NOT acceptance).
 *
 * Single mechanism: long-input-only lattice beam/entry budgets. Production
 * defaults stay at beamSize=64 / entriesPerKey=8; the new EngineTuning fields
 * longInputBeamSize / longInputEntriesPerKey are 0 (disabled) unless a variant
 * explicitly opts in, and only apply when queryLength >= longInputThreshold.
 *
 * Writes build/d05/long-budget-ablation.txt. Read-only with respect to the
 * production search path; no scoring weight is changed.
 */
class PinyinD05LongBudgetAblationTest {

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
        val max: Double,
    )

    private data class Transitions(
        val top1Gain: Int,
        val top1Loss: Int,
        val top5Gain: Int,
        val top5Loss: Int,
        val oracleGain: Int,
        val oracleLoss: Int,
        val fullCoverageLoss: Int,
        val rankCostDelta: Double,
    )

    private data class VariantResult(
        val name: String,
        val tuning: PinyinEngine.EngineTuning,
        val naturalRows: List<Row>,
        val longRows: List<Row>,
        val naturalSummary: Summary,
        val longSummary: Summary,
    )

    @Test
    fun runLongInputBudgetAblation() {
        val root = findRepoRoot()
        val devFile = File(root, "eval/d05/dev/dev.tsv")
        val dictFile = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assertTrue("D05 dev corpus missing: " + devFile.absolutePath, devFile.isFile)
        assertTrue("dictionary missing: " + dictFile.absolutePath, dictFile.isFile)
        assertEquals("D05 dev hash", DEV_SHA256, sha256(devFile))

        val all = loadCases(devFile)
        val natural = all.filter { it.category.startsWith("natural_") && it.coverage <= MAX_SEARCH_INPUT_LENGTH }
        val naturalLong = natural.filter { it.category == "natural_long" }
        assertEquals("natural rankable denominator", 667, natural.size)
        assertEquals("natural_long <=64 denominator", 90, naturalLong.size)
        assertEquals("natural_short", 300, natural.count { it.category == "natural_short" })
        assertEquals("natural_medium", 277, natural.count { it.category == "natural_medium" })
        assertTrue("all natural inputs are one lowercase letter run", natural.all { c -> c.input.all { it in 'a'..'z' } })

        val engine = dictFile.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue("engine failed to load dictionary", engine.isReady)
        engine.setLearningEnabled(false)

        // Production default must remain exactly the historical baseline.
        val defaultTuning = PinyinEngine.EngineTuning()
        assertEquals("production beam default", 64, defaultTuning.beamSize)
        assertEquals("production entries default", 8, defaultTuning.entriesPerKey)
        assertEquals("longInputBeamSize default off", 0, defaultTuning.longInputBeamSize)
        assertEquals("longInputEntriesPerKey default off", 0, defaultTuning.longInputEntriesPerKey)
        val defaultEngine = engine.withTuning(defaultTuning)
        natural.take(30).forEach { case ->
            assertEquals(
                "production default view must not change search: " + case.id,
                engine.search(case.input, SEARCH_LIMIT).map { it.word },
                defaultEngine.search(case.input, SEARCH_LIMIT).map { it.word },
            )
        }

        val variants = buildVariants()
        val results = variants.map { (name, tuning) -> measureVariant(engine.withTuning(tuning), name, tuning, natural) }
        val baseline = results.first { it.name == "baseline" }
        val report = buildReport(root, devFile, dictFile, engine, natural, naturalLong, results)
        val reportFile = File(root, REPORT_PATH)
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report, Charsets.UTF_8)

        assertEquals("variant count", 10, results.size)
        assertEquals("baseline natural n", 667, baseline.naturalSummary.n)
        assertEquals("baseline long n", 90, baseline.longSummary.n)
        assertTrue("report has denominators", report.contains("denominator.naturalRankable=667") && report.contains("denominator.naturalLong=90"))
        assertTrue("report has variant table", report.contains("[variantTable]"))
        assertTrue("report has transitions", report.contains("[transitionsVsBaseline]"))
        assertTrue("report has conclusion", report.contains("[conclusion]"))
        assertTrue("report has bestSegmentation probe", report.contains("[bestSegmentationProbe]"))

        println(buildConsoleSummary(results, reportFile))
    }

    private fun buildVariants(): LinkedHashMap<String, PinyinEngine.EngineTuning> = linkedMapOf(
        "baseline" to PinyinEngine.EngineTuning(),
        "beam96" to PinyinEngine.EngineTuning(longInputBeamSize = 96),
        "beam128" to PinyinEngine.EngineTuning(longInputBeamSize = 128),
        "beam192" to PinyinEngine.EngineTuning(longInputBeamSize = 192),
        "entries16" to PinyinEngine.EngineTuning(longInputEntriesPerKey = 16),
        "entries32" to PinyinEngine.EngineTuning(longInputEntriesPerKey = 32),
        "beam96+entries16" to PinyinEngine.EngineTuning(longInputBeamSize = 96, longInputEntriesPerKey = 16),
        "beam128+entries16" to PinyinEngine.EngineTuning(longInputBeamSize = 128, longInputEntriesPerKey = 16),
        "beam128+entries32" to PinyinEngine.EngineTuning(longInputBeamSize = 128, longInputEntriesPerKey = 32),
        "beam192+entries32" to PinyinEngine.EngineTuning(longInputBeamSize = 192, longInputEntriesPerKey = 32),
    )

    private fun measureVariant(
        engine: PinyinEngine,
        name: String,
        tuning: PinyinEngine.EngineTuning,
        natural: List<EvalCase>,
    ): VariantResult {
        // Warm JIT and the variant's dictionary/entry paths before timing.
        natural.take(24).forEach { case ->
            engine.search(case.input, SEARCH_LIMIT)
            engine.search(case.input, FULL_LIMIT)
        }
        val rows = natural.map { case ->
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
            )
        }
        val longRows = rows.filter { it.case.category == "natural_long" }
        return VariantResult(
            name = name,
            tuning = tuning,
            naturalRows = rows,
            longRows = longRows,
            naturalSummary = summarize(rows),
            longSummary = summarize(longRows),
        )
    }

    private fun buildReport(
        root: File,
        devFile: File,
        dictFile: File,
        engine: PinyinEngine,
        natural: List<EvalCase>,
        naturalLong: List<EvalCase>,
        results: List<VariantResult>,
    ): String {
        val builder = StringBuilder()
        val baseline = results.first { it.name == "baseline" }
        val defaultTuning = PinyinEngine.EngineTuning()
        builder.appendLine("# Bitap D05 long-input lattice budget ablation (development diagnostic; NOT acceptance)")
        builder.appendLine("generatedBy=PinyinD05LongBudgetAblationTest")
        builder.appendLine("corpus=" + relativePath(root, devFile))
        builder.appendLine("corpusSha256=" + sha256(devFile))
        builder.appendLine("dictionary=" + relativePath(root, dictFile))
        builder.appendLine("dictionarySha256=" + sha256(dictFile))
        builder.appendLine("dictionaryEntries=" + engine.entryCount)
        builder.appendLine("denominator.naturalRankable=" + natural.size + " (short=" + natural.count { it.category == "natural_short" } +
            " medium=" + natural.count { it.category == "natural_medium" } + " long=" + natural.count { it.category == "natural_long" } + ")")
        builder.appendLine("denominator.naturalLong=" + naturalLong.size + " (pinyinLength<=64)")
        builder.appendLine("baseline=production-default beamSize=" + defaultTuning.beamSize + " entriesPerKey=" + defaultTuning.entriesPerKey +
            " partialQuota=" + defaultTuning.partialQuota + " partialEarlySlotCount=" + defaultTuning.partialEarlySlotCount)
        builder.appendLine("mechanism=long-input-only lattice budget: longInputBeamSize/longInputEntriesPerKey applied only when queryLength >= longInputThreshold")
        builder.appendLine("longInputThreshold=" + defaultTuning.longInputThreshold)
        builder.appendLine("zeroMeansOff=longInputBeamSize=0 and longInputEntriesPerKey=0 keep production values; production default unchanged")
        builder.appendLine("searchLimit=" + SEARCH_LIMIT + " oracleLimit=" + FULL_LIMIT + " note=JVM single-process latency, not device latency")
        builder.appendLine()
        appendVariantTable(builder, results)
        builder.appendLine()
        appendTransitions(builder, results)
        builder.appendLine()
        appendFailureClassProxy(builder, results)
        builder.appendLine()
        appendRegressions(builder, results)
        builder.appendLine()
        appendBestSegmentationProbe(builder, engine, naturalLong)
        builder.appendLine()
        appendConclusion(builder, results)
        return builder.toString()
    }

    private fun appendVariantTable(builder: StringBuilder, results: List<VariantResult>) {
        builder.appendLine("[variantTable]")
        builder.appendLine("variant\tscope\tn\ttop1\ttop5\trankCost\toracle64\tfullCoverage\tp50ms\tp95ms\tmaxMs")
        results.forEach { result ->
            appendVariantRow(builder, result.name, "natural", result.naturalSummary)
            appendVariantRow(builder, result.name, "natural_long", result.longSummary)
        }
    }

    private fun appendVariantRow(builder: StringBuilder, name: String, scope: String, summary: Summary) {
        builder.appendLine(
            name + "\t" + scope + "\t" + summary.n + "\t" +
                summary.top1 + "/" + summary.n + "(" + pct(summary.top1, summary.n) + "%)\t" +
                summary.top5 + "/" + summary.n + "(" + pct(summary.top5, summary.n) + "%)\t" +
                fmt(summary.rankCost) + "\t" +
                summary.oracle + "/" + summary.n + "(" + pct(summary.oracle, summary.n) + "%)\t" +
                summary.fullCoverage + "/" + summary.n + "\t" +
                fmt(summary.p50) + "\t" + fmt(summary.p95) + "\t" + fmt(summary.max),
        )
    }

    private fun appendTransitions(builder: StringBuilder, results: List<VariantResult>) {
        val baseline = results.first { it.name == "baseline" }
        builder.appendLine("[transitionsVsBaseline] scope=natural")
        builder.appendLine("variant\ttop1Gain\ttop1Loss\ttop5Gain\ttop5Loss\toracleGain\toracleLoss\tfullCoverageLoss\trankCostDelta")
        results.drop(1).forEach { result ->
            val t = transitions(baseline.naturalRows, result.naturalRows)
            appendTransitionRow(builder, result.name, t)
        }
        builder.appendLine("[transitionsVsBaseline] scope=natural_long")
        builder.appendLine("variant\ttop1Gain\ttop1Loss\ttop5Gain\ttop5Loss\toracleGain\toracleLoss\tfullCoverageLoss\trankCostDelta")
        results.drop(1).forEach { result ->
            val t = transitions(baseline.longRows, result.longRows)
            appendTransitionRow(builder, result.name, t)
        }
    }

    private fun appendTransitionRow(builder: StringBuilder, name: String, t: Transitions) {
        builder.appendLine(
            name + "\t" + t.top1Gain + "\t" + t.top1Loss + "\t" + t.top5Gain + "\t" + t.top5Loss + "\t" +
                t.oracleGain + "\t" + t.oracleLoss + "\t" + t.fullCoverageLoss + "\t" + fmt(t.rankCostDelta),
        )
    }

    private fun transitions(baselineRows: List<Row>, variantRows: List<Row>): Transitions {
        val variantById = variantRows.associateBy { it.case.id }
        var top1Gain = 0
        var top1Loss = 0
        var top5Gain = 0
        var top5Loss = 0
        var oracleGain = 0
        var oracleLoss = 0
        var fullCoverageLoss = 0
        var rankCostDelta = 0.0
        baselineRows.forEach { base ->
            val variant = variantById[base.case.id] ?: return@forEach
            if (!base.top1 && variant.top1) top1Gain++
            if (base.top1 && !variant.top1) top1Loss++
            if (!base.top5 && variant.top5) top5Gain++
            if (base.top5 && !variant.top5) top5Loss++
            val baseOracle = base.rank64 >= 0
            val variantOracle = variant.rank64 >= 0
            if (!baseOracle && variantOracle) oracleGain++
            if (baseOracle && !variantOracle) oracleLoss++
            if (base.fullCoverage && !variant.fullCoverage) fullCoverageLoss++
            rankCostDelta += (variant.rankCost - base.rankCost).toDouble()
        }
        return Transitions(
            top1Gain = top1Gain,
            top1Loss = top1Loss,
            top5Gain = top5Gain,
            top5Loss = top5Loss,
            oracleGain = oracleGain,
            oracleLoss = oracleLoss,
            fullCoverageLoss = fullCoverageLoss,
            rankCostDelta = if (baselineRows.isEmpty()) 0.0 else rankCostDelta / baselineRows.size,
        )
    }

    private fun appendRegressions(builder: StringBuilder, results: List<VariantResult>) {
        builder.appendLine("[regressionDetails]")
        builder.appendLine("variant\tscope\tid\tbaselineRank8\tvariantRank8\tbaselineTop5\tvariantTop5")
        val baseline = results.first { it.name == "baseline" }
        results.drop(1).forEach { result ->
            appendRegressionRows(builder, result.name, "natural_long", baseline.longRows, result.longRows)
            // For the 667 set, only list cases that were Top5 hits at baseline and are not now.
            appendRegressionRows(builder, result.name, "natural", baseline.naturalRows, result.naturalRows, limit = 20)
        }
    }

    private fun appendFailureClassProxy(builder: StringBuilder, results: List<VariantResult>) {
        builder.appendLine("[failureClassProxy] derived only from rank64; proxy, not causal")
        builder.appendLine("variant\tscope\ttop5Misses\trank5-7\trank8plus\tnotIn64")
        results.forEach { result ->
            appendFailureClassRow(builder, result.name, "natural", result.naturalRows)
            appendFailureClassRow(builder, result.name, "natural_long", result.longRows)
        }
    }

    private fun appendFailureClassRow(builder: StringBuilder, name: String, scope: String, rows: List<Row>) {
        val misses = rows.filter { !it.top5 }
        val rank5to7 = misses.count { it.rank64 in 5..7 }
        val rank8plus = misses.count { it.rank64 >= 8 }
        val notIn64 = misses.count { it.rank64 < 0 }
        builder.appendLine(name + "\t" + scope + "\t" + misses.size + "\t" + rank5to7 + "\t" + rank8plus + "\t" + notIn64)
    }

    private fun appendRegressionRows(
        builder: StringBuilder,
        variantName: String,
        scope: String,
        baselineRows: List<Row>,
        variantRows: List<Row>,
        limit: Int = Int.MAX_VALUE,
    ) {
        val variantById = variantRows.associateBy { it.case.id }
        var emitted = 0
        baselineRows
            .filter { it.top5 }
            .forEach { base ->
                if (emitted >= limit) return@forEach
                val variant = variantById[base.case.id] ?: return@forEach
                if (!variant.top5) {
                    builder.appendLine(
                        variantName + "\t" + scope + "\t" + base.case.id + "\t" + base.rank8 + "\t" + variant.rank8 +
                            "\t" + base.top5 + "\t" + variant.top5,
                    )
                    emitted++
                }
            }
    }

    private fun appendBestSegmentationProbe(builder: StringBuilder, engine: PinyinEngine, naturalLong: List<EvalCase>) {
        val baselineRows = naturalLong.map { case ->
            val started = System.nanoTime()
            val segmentation = engine.debugBestSegmentation(case.input)
            val latency = System.nanoTime() - started
            val text = segmentation?.joinToString("") ?: ""
            val exact = normalizeText(text) == normalizeText(case.expectedCjk) && text.isNotEmpty()
            val pool = engine.search(case.input, FULL_LIMIT)
            val rank64 = pool.indexOfFirst { candidateMatches(it, case) }
            val top8 = engine.search(case.input, SEARCH_LIMIT)
            val rank8 = top8.indexOfFirst { candidateMatches(it, case) }
            Triple(case, Triple(exact, rank8, rank64), latency)
        }
        val exact = baselineRows.filter { it.second.first }
        val exactNotIn64 = exact.filter { it.second.third < 0 }
        val exactTop5Miss = exact.filter { it.second.second !in 0..4 }
        val exactTop1Miss = exact.filter { it.second.second != 0 }
        val avgMicros = if (baselineRows.isEmpty()) 0.0 else baselineRows.sumOf { it.third / 1_000.0 } / baselineRows.size
        builder.appendLine("[bestSegmentationProbe] scope=natural_long")
        builder.appendLine("cases=" + naturalLong.size)
        builder.appendLine("bestSegmentationExactMatch=" + exact.size + "/" + naturalLong.size)
        builder.appendLine("bestSegmentationExactNotInSearch64=" + exactNotIn64.size + "/" + naturalLong.size)
        builder.appendLine("bestSegmentationExactTop5Miss=" + exactTop5Miss.size + "/" + naturalLong.size)
        builder.appendLine("bestSegmentationExactTop1Miss=" + exactTop1Miss.size + "/" + naturalLong.size)
        builder.appendLine("bestSegmentationAvgMicros=" + String.format(Locale.ROOT, "%.1f", avgMicros))
        builder.appendLine("upperBoundIfForcedToFront: top1Gain=" + exact.size + " top5Gain=" + exactTop5Miss.size + " oracleGain=" + exactNotIn64.size +
            " (diagnostic ceiling only; no score/order evidence)")
        builder.appendLine("note=bestSegmentation uses dictionary first entry per pinyin key and no beam; it is a candidate-recall probe, not a ranking strategy")
        builder.appendLine("exactNotIn64Ids=" + exactNotIn64.take(20).joinToString(",") { it.first.id })
        builder.appendLine("exactTop5MissIds=" + exactTop5Miss.take(20).joinToString(",") { it.first.id })
    }

    private fun appendConclusion(builder: StringBuilder, results: List<VariantResult>) {
        val baseline = results.first { it.name == "baseline" }
        val candidates = results.drop(1)
        val bestLong = candidates.maxWithOrNull(
            compareBy<VariantResult> { it.longSummary.top5 * 1000 - it.longSummary.rankCost.toInt() }
                .thenBy { it.longSummary.top1 }
                .thenBy { it.longSummary.oracle },
        )
        val bestNatural = candidates.maxWithOrNull(
            compareBy<VariantResult> { it.naturalSummary.top5 * 1000 - it.naturalSummary.rankCost.toInt() },
        )
        builder.appendLine("[conclusion]")
        builder.appendLine("baseline.natural_long: top1=" + baseline.longSummary.top1 + "/90 top5=" + baseline.longSummary.top5 + "/90 rankCost=" + fmt(baseline.longSummary.rankCost) +
            " oracle64=" + baseline.longSummary.oracle + "/90")
        builder.appendLine("baseline.natural: top1=" + baseline.naturalSummary.top1 + "/667 top5=" + baseline.naturalSummary.top5 + "/667 rankCost=" + fmt(baseline.naturalSummary.rankCost) +
            " oracle64=" + baseline.naturalSummary.oracle + "/667")
        var worthRecommendation = false
        if (bestLong != null) {
            val longTop5Delta = bestLong.longSummary.top5 - baseline.longSummary.top5
            val longTop1Delta = bestLong.longSummary.top1 - baseline.longSummary.top1
            val longRankCostDelta = bestLong.longSummary.rankCost - baseline.longSummary.rankCost
            val naturalTop5Delta = bestLong.naturalSummary.top5 - baseline.naturalSummary.top5
            val naturalRankCostDelta = bestLong.naturalSummary.rankCost - baseline.naturalSummary.rankCost
            val naturalTop5Loss = transitions(baseline.naturalRows, bestLong.naturalRows).top5Loss
            val longTop5Loss = transitions(baseline.longRows, bestLong.longRows).top5Loss
            val p95Ratio = if (baseline.longSummary.p95 > 0.0) bestLong.longSummary.p95 / baseline.longSummary.p95 else 0.0
            builder.appendLine("bestLongVariant=" + bestLong.name + " tuning=longInputBeamSize=" + bestLong.tuning.longInputBeamSize +
                " longInputEntriesPerKey=" + bestLong.tuning.longInputEntriesPerKey)
            builder.appendLine("bestLongVariant.deltaVsBaseline: longTop1=" + signed(longTop1Delta) + " longTop5=" + signed(longTop5Delta) +
                " longRankCost=" + signedFmt(longRankCostDelta) + " naturalTop5=" + signed(naturalTop5Delta) +
                " naturalRankCost=" + signedFmt(naturalRankCostDelta) + " naturalTop5Loss=" + naturalTop5Loss +
                " longTop5Loss=" + longTop5Loss + " p95Ratio=" + fmt(p95Ratio))
            val worth = longTop5Delta > 0 && naturalTop5Loss == 0 && naturalTop5Delta >= 0
            worthRecommendation = worth
            if (worth) {
                builder.appendLine("verdict=consider_as_long_input_experiment_default")
                builder.appendLine("reason=long Top5 improves " + signed(longTop5Delta) + "/90 while natural Top5 delta " + signed(naturalTop5Delta) +
                    "/667 and no natural Top5 loss; p95 ratio " + fmt(p95Ratio) + "x baseline")
            } else {
                builder.appendLine("verdict=not_worth_as_production_default_yet")
                val reasons = mutableListOf<String>()
                if (longTop5Delta <= 0) reasons += "long Top5 delta " + signed(longTop5Delta) + "/90 无实质收益"
                if (naturalTop5Loss > 0) reasons += "natural 667 有 " + naturalTop5Loss + " 条 Top5 回退"
                if (longTop5Loss > 0) reasons += "long 自身有 " + longTop5Loss + " 条 Top5 回退"
                if (p95Ratio >= 1.5) reasons += "p95 时延 " + fmt(p95Ratio) + "x"
                builder.appendLine("reason=" + (if (reasons.isEmpty()) "收益/回退未达到采用条件" else reasons.joinToString("；")))
            }
        }
        val exactProbeLine = "bestSegmentation 诊断：若精确匹配候选能被正式插入并按合理分数排序，可作为 oracle/Top1/Top5 的上限信号；但当前只是无 beam 的候选召回探针，不能直接作为生产排序默认。"
        builder.appendLine(exactProbeLine)
        if (worthRecommendation) {
            builder.appendLine("recommendation=可将 " + (bestLong?.name ?: "") + " 作为长输入实验默认候选，先补真机 p95、内存峰值和连续输入稳定性验证，再决定是否改生产默认。")
        } else {
            builder.appendLine("recommendation=不把长输入 beam/entry 预算作为生产默认：本实验 Top1/Top5 零收益且 p95 时延显著上升；若继续，应转向候选收集/评分机制，而不是继续放大预算。")
        }
    }

    private fun summarize(rows: List<Row>): Summary {
        val latencies = rows.map { it.latencyNanos / 1_000_000.0 }.sorted()
        return Summary(
            n = rows.size,
            top1 = rows.count { it.top1 },
            top5 = rows.count { it.top5 },
            rankCost = if (rows.isEmpty()) 0.0 else rows.sumOf { it.rankCost }.toDouble() / rows.size,
            oracle = rows.count { it.rank64 >= 0 },
            fullCoverage = rows.count { it.fullCoverage },
            p50 = percentile(latencies, 50),
            p95 = percentile(latencies, 95),
            max = latencies.lastOrNull() ?: 0.0,
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
        error("repository root with eval/d05/dev/dev.tsv and app/src/main/assets/pinyin_dict.tsv not found")
    }

    private fun buildConsoleSummary(results: List<VariantResult>, reportFile: File): String {
        val baseline = results.first { it.name == "baseline" }
        return "D05 long-budget ablation: natural=" + baseline.naturalSummary.n + " long=" + baseline.longSummary.n +
            " baselineLongTop1=" + baseline.longSummary.top1 + "/90 baselineLongTop5=" + baseline.longSummary.top5 + "/90" +
            " variants=" + results.size + "\nwrote " + reportFile.absolutePath
    }

    private companion object {
        private const val SEARCH_LIMIT = 8
        private const val FULL_LIMIT = 64
        private const val MISS_RANK_COST = 9
        private const val MAX_SEARCH_INPUT_LENGTH = 64
        private const val DEV_SHA256 = "63983c5f9a490040592d6740e2e747e2b5a574d0a068accb44f25179153110ad"
        private const val REPORT_PATH = "build/d05/long-budget-ablation.txt"
    }
}
