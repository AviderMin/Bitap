package com.bitap.keyboard.core

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-E D05 natural_long sentence-level rerank ablation (development diagnostic; NOT acceptance).
 *
 * Scope: the 90 D05 dev natural_long cases whose pinyin input is <= 64 chars
 * (the engine hard search budget). The 33 over-budget cases (65-70 chars) are
 * reported as excluded and are not part of the ranking denominator.
 *
 * Single mechanism under test: the long-input-only sentence rerank weights
 * [PinyinEngine.EngineTuning.longSentenceCorpusWeight] and
 * [PinyinEngine.EngineTuning.longSentenceDictionaryWeight]. Both default to
 * 0.0 in production and only contribute when queryLength >= longInputThreshold,
 * so the production default search path is unchanged. Every variant is a
 * [PinyinEngine.EngineTuning] view over one shared dictionary/index; no weight
 * is promoted to a production default and no corpus/dictionary file is touched.
 *
 * For every variant this test reports Top1, Top5, rankCost.avg, oracle64 and
 * fullCoverage plus deltas versus the production-default baseline, and lists
 * [negativeRegressions] (Top5 hit -> miss, rank drops, oracle losses) instead of
 * only the gains. Writes build/d05/long-sentence-rerank-ablation.txt.
 */
class PinyinD05LongSentenceRerankAblationTest {

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
        val top5LossIds: List<String>,
        val rankDropIds: List<String>,
        val oracleLossIds: List<String>,
    )

    private data class VariantSpec(
        val name: String,
        val mechanism: String,
        val tuning: PinyinEngine.EngineTuning,
    )

    private data class VariantResult(
        val name: String,
        val mechanism: String,
        val tuning: PinyinEngine.EngineTuning,
        val rows: List<Row>,
        val summary: Summary,
    )

    @Test
    fun runLongSentenceRerankAblation() {
        val root = findRepoRoot()
        val devFile = File(root, "eval/d05/dev/dev.tsv")
        val dictFile = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assertTrue("D05 dev corpus missing: " + devFile.absolutePath, devFile.isFile)
        assertTrue("dictionary missing: " + dictFile.absolutePath, dictFile.isFile)
        assertEquals("D05 dev hash", DEV_SHA256, sha256(devFile))

        val all = loadCases(devFile)
        val naturalLong = all.filter { it.category == "natural_long" }
        val cases = naturalLong.filter { it.input.length <= MAX_SEARCH_INPUT_LENGTH }
        assertEquals("natural_long total", 123, naturalLong.size)
        assertEquals("natural_long over-budget excluded", 33, naturalLong.size - cases.size)
        assertEquals("natural_long <=64 denominator", 90, cases.size)
        assertTrue("all selected inputs are one lowercase letter run", cases.all { c -> c.input.all { it in 'a'..'z' } })

        val engine = dictFile.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue("engine failed to load dictionary", engine.isReady)
        engine.setLearningEnabled(false)

        // Production default must stay exactly the historical baseline: both
        // long-sentence weights are off and only act above the long-input threshold.
        val defaultTuning = PinyinEngine.EngineTuning()
        assertEquals("production beam default", 64, defaultTuning.beamSize)
        assertEquals("production entries default", 8, defaultTuning.entriesPerKey)
        assertEquals("longInputThreshold default", 24, defaultTuning.longInputThreshold)
        assertEquals("longSentenceCorpusWeight default off", 0.0, defaultTuning.longSentenceCorpusWeight, 0.0)
        assertEquals("longSentenceDictionaryWeight default off", 0.0, defaultTuning.longSentenceDictionaryWeight, 0.0)
        assertTrue("all selected cases reach the long-input threshold", cases.all { it.input.length >= defaultTuning.longInputThreshold })
        val defaultEngine = engine.withTuning(defaultTuning)
        cases.take(20).forEach { case ->
            assertEquals(
                "production default view must not change search: " + case.id,
                engine.search(case.input, SEARCH_LIMIT).map { it.word },
                defaultEngine.search(case.input, SEARCH_LIMIT).map { it.word },
            )
        }

        val results = runVariants(engine, cases)
        val baseline = results.first { it.name == "baseline" }
        val report = buildReport(root, devFile, dictFile, engine, naturalLong, cases, results)
        val reportFile = File(root, REPORT_PATH)
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report, Charsets.UTF_8)

        // Sanity assertions: the baseline must reproduce the path-loss report
        // numbers and every variant must keep full candidate coverage.
        assertEquals("variant count", 10, results.size)
        assertEquals("baseline denominator", 90, baseline.summary.n)
        assertEquals("baseline Top1 reproduces long-sentence-path-loss", 3, baseline.summary.top1)
        assertEquals("baseline Top5 reproduces long-sentence-path-loss", 14, baseline.summary.top5)
        assertEquals("denominator", 90, cases.size)
        results.forEach { result ->
            assertEquals(
                "fullCoverage must stay 90/90 for " + result.name,
                90,
                result.summary.fullCoverage,
            )
        }
        assertTrue("report written", reportFile.isFile && reportFile.length() > 0L)
        assertTrue("report has denominator", report.contains("denominator=90"))
        assertTrue("report has over-budget exclusion", report.contains("overBudgetExcluded=33"))
        assertTrue("report has variant table", report.contains("[variantTable]"))
        assertTrue("report has transitions", report.contains("[transitionsVsBaseline]"))
        assertTrue("report has negativeRegressions", report.contains("[negativeRegressions]"))
        assertTrue("report has interpretation", report.contains("[interpretation]"))
        assertTrue("report has conclusion", report.contains("[conclusion]"))

        println(buildConsoleSummary(results, reportFile))
    }

    /**
     * Builds the sweep in task order: baseline first, then all single-weight
     * cells, and only afterwards one combination cell derived from the best
     * single corpus cell + best single dictionary cell. The combination is
     * labelled as a combination so it cannot be mistaken for a single mechanism.
     */
    private fun runVariants(engine: PinyinEngine, cases: List<EvalCase>): List<VariantResult> {
        val baselineTuning = PinyinEngine.EngineTuning()
        val singleSpecs = ArrayList<VariantSpec>()
        LONG_SENTENCE_CORPUS_WEIGHTS.forEach { weight ->
            singleSpecs += VariantSpec(
                name = "longSentenceCorpusWeight-" + weightText(weight),
                mechanism = "single:longSentenceCorpusWeight=" + fmt2(weight),
                tuning = baselineTuning.copy(longSentenceCorpusWeight = weight),
            )
        }
        LONG_SENTENCE_DICTIONARY_WEIGHTS.forEach { weight ->
            singleSpecs += VariantSpec(
                name = "longSentenceDictionaryWeight-" + weightText(weight),
                mechanism = "single:longSentenceDictionaryWeight=" + fmt2(weight),
                tuning = baselineTuning.copy(longSentenceDictionaryWeight = weight),
            )
        }
        val singleResults = singleSpecs.map { spec -> measureVariant(engine, spec, cases) }
        val bestCorpus = singleResults
            .filter { it.mechanism.startsWith("single:longSentenceCorpusWeight=") }
            .minWithOrNull(qualityComparator())
            ?: error("no corpus single variants")
        val bestDictionary = singleResults
            .filter { it.mechanism.startsWith("single:longSentenceDictionaryWeight=") }
            .minWithOrNull(qualityComparator())
            ?: error("no dictionary single variants")

        val combinationTuning = baselineTuning.copy(
            longSentenceCorpusWeight = bestCorpus.tuning.longSentenceCorpusWeight,
            longSentenceDictionaryWeight = bestDictionary.tuning.longSentenceDictionaryWeight,
        )
        val combination = VariantSpec(
            name = "combination(longSentenceCorpusWeight=" + fmt2(combinationTuning.longSentenceCorpusWeight) +
                ",longSentenceDictionaryWeight=" + fmt2(combinationTuning.longSentenceDictionaryWeight) + ")",
            mechanism = "combination:bestSingleCorpus+bestSingleDictionary (selected after singles; diagnostic only)",
            tuning = combinationTuning,
        )

        val results = ArrayList<VariantResult>()
        results += measureVariant(engine, VariantSpec("baseline", "production-default (long sentence weights off)", baselineTuning), cases)
        results += singleResults
        results += measureVariant(engine, combination, cases)
        return results
    }

    /** Lower is better for the single-cell selection used by the combination cell. */
    private fun qualityComparator(): Comparator<VariantResult> =
        compareByDescending<VariantResult> { it.summary.top5 }
            .thenByDescending { it.summary.top1 }
            .thenBy { it.summary.rankCost }
            .thenByDescending { it.summary.oracle }
            .thenBy { it.name }

    private fun measureVariant(engine: PinyinEngine, spec: VariantSpec, cases: List<EvalCase>): VariantResult {
        val view = engine.withTuning(spec.tuning)
        // Warm JIT and the variant's sentence-rerank path before timing.
        cases.take(12).forEach { case ->
            view.search(case.input, SEARCH_LIMIT)
            view.search(case.input, FULL_LIMIT)
        }
        val rows = cases.map { case ->
            val started = System.nanoTime()
            val top8 = view.search(case.input, limit = SEARCH_LIMIT)
            val latency = System.nanoTime() - started
            val pool = view.search(case.input, limit = FULL_LIMIT)
            Row(
                case = case,
                rank8 = top8.indexOfFirst { candidateMatches(it, case) },
                rank64 = pool.indexOfFirst { candidateMatches(it, case) },
                latencyNanos = latency,
                fullCoverage = pool.any { it.consumedInputLength >= case.coverage },
            )
        }
        return VariantResult(
            name = spec.name,
            mechanism = spec.mechanism,
            tuning = spec.tuning,
            rows = rows,
            summary = summarize(rows),
        )
    }

    private fun buildReport(
        root: File,
        devFile: File,
        dictFile: File,
        engine: PinyinEngine,
        naturalLong: List<EvalCase>,
        cases: List<EvalCase>,
        results: List<VariantResult>,
    ): String {
        val builder = StringBuilder()
        val baseline = results.first { it.name == "baseline" }
        val tuning = PinyinEngine.EngineTuning()
        builder.appendLine("# Bitap D05 natural_long sentence-rerank ablation (development diagnostic; NOT acceptance)")
        builder.appendLine("generatedBy=PinyinD05LongSentenceRerankAblationTest")
        builder.appendLine("corpus=" + relativePath(root, devFile))
        builder.appendLine("corpusSha256=" + sha256(devFile))
        builder.appendLine("dictionary=" + relativePath(root, dictFile))
        builder.appendLine("dictionarySha256=" + sha256(dictFile))
        builder.appendLine("dictionaryEntries=" + engine.entryCount)
        builder.appendLine("selection=category==natural_long && queryLength<=64")
        builder.appendLine("naturalLongTotal=" + naturalLong.size)
        builder.appendLine("overBudgetExcluded=" + (naturalLong.size - cases.size) + " (queryLength 65-70; engine MAX_SEARCH_INPUT_LENGTH=64)")
        builder.appendLine("denominator=" + cases.size)
        builder.appendLine(
            "engineTuning=production-default  beamSize=" + tuning.beamSize +
                " entriesPerKey=" + tuning.entriesPerKey +
                " corpusWordNgramWeight=" + fmt2(tuning.corpusWordNgramWeight) +
                " dictionaryWordNgramWeight=" + fmt2(tuning.dictionaryWordNgramWeight) +
                " contextWeight=" + fmt2(tuning.contextWeight) +
                " longInputThreshold=" + tuning.longInputThreshold +
                " longSentenceCorpusWeight=" + fmt2(tuning.longSentenceCorpusWeight) +
                " longSentenceDictionaryWeight=" + fmt2(tuning.longSentenceDictionaryWeight) +
                " sentenceCorpusWeight=" + fmt2(tuning.sentenceCorpusWeight) +
                " sentenceDictionaryWeight=" + fmt2(tuning.sentenceDictionaryWeight),
        )
        builder.appendLine("mechanism=long-sentence-only score rerank: longSentenceCorpusWeight/longSentenceDictionaryWeight apply only when queryLength>=longInputThreshold; both production defaults are 0.0 and remain unchanged")
        builder.appendLine("searchLimit=" + SEARCH_LIMIT + " oracleLimit=" + FULL_LIMIT + " note=JVM single-process diagnostic; latency is not Android/device input latency")
        builder.appendLine("rankCost=rank0..4 -> rank+1, Top5 miss -> " + MISS_RANK_COST + "; oracle64=correct full-coverage candidate present in the top-" + FULL_LIMIT + " pool; fullCoverage=pool contains any candidate consuming the whole input")
        builder.appendLine()
        builder.appendLine("[metrics]")
        builder.appendLine("n=" + cases.size)
        builder.appendLine("baseline.top1=" + ratio(baseline.summary.top1, baseline.summary.n))
        builder.appendLine("baseline.top5=" + ratio(baseline.summary.top5, baseline.summary.n))
        builder.appendLine("baseline.rankCost.avg=" + fmt(baseline.summary.rankCost))
        builder.appendLine("baseline.oracle64=" + ratio(baseline.summary.oracle, baseline.summary.n))
        builder.appendLine("baseline.fullCoverage=" + ratio(baseline.summary.fullCoverage, baseline.summary.n))
        builder.appendLine()
        appendVariantTable(builder, baseline, results)
        builder.appendLine()
        appendTransitions(builder, baseline, results)
        builder.appendLine()
        appendNegativeRegressions(builder, baseline, results)
        builder.appendLine()
        appendInterpretation(builder, baseline, results)
        builder.appendLine()
        appendConclusion(builder, baseline, results)
        return builder.toString()
    }

    /**
     * Structural ceiling of a fixed-pool sentence rerank: it can only reorder
     * candidates that already exist in the top-64 pool. This separates "rerank
     * has no effect" from "the correct candidate was never generated/collected".
     */
    private fun appendInterpretation(builder: StringBuilder, baseline: VariantResult, results: List<VariantResult>) {
        val oracleButNotTop5 = baseline.rows.filter { it.rank64 >= 0 && !it.top5 }
        val top5MissWithoutOracle = baseline.rows.filter { it.rank64 < 0 }
        val bestTop5Gain = results.drop(1).maxOfOrNull { it.summary.top5 - baseline.summary.top5 } ?: 0
        val worstTop5Loss = results.drop(1).maxOfOrNull { baseline.summary.top5 - it.summary.top5 } ?: 0
        val bestOracleGain = results.drop(1).maxOfOrNull { it.summary.oracle - baseline.summary.oracle } ?: 0
        builder.appendLine("[interpretation]")
        builder.appendLine("baseline.top5Hits=" + baseline.summary.top5 + "/90")
        builder.appendLine("baseline.oracle64ButTop5Miss=" + oracleButNotTop5.size + " ids=" + oracleButNotTop5.joinToString(",") { it.case.id })
        builder.appendLine("baseline.top5MissWithoutOracle64=" + top5MissWithoutOracle.size + " (correct full candidate absent from the top-64 pool; a fixed-pool rerank cannot recover these)")
        builder.appendLine("rerank.top5CeilingGain=" + oracleButNotTop5.size + " (upper bound only: 14->" + (baseline.summary.top5 + oracleButNotTop5.size) + " if every oracle64-but-outside-Top5 case were reordered into Top5)")
        builder.appendLine("observed.bestTop5Gain=" + signed(bestTop5Gain) + " observed.worstTop5Loss=" + worstTop5Loss + " observed.bestOracleGain=" + signed(bestOracleGain))
        builder.appendLine("note=sentence weights change the lattice emission/score ordering; they cannot create candidates the 64-budget lattice/collector never produced. The 68/90 pool misses are the dominant failure layer.")
    }

    private fun appendVariantTable(builder: StringBuilder, baseline: VariantResult, results: List<VariantResult>) {
        builder.appendLine("[variantTable]")
        builder.appendLine(
            "variant\tmechanism\tn\ttop1\ttop5\trankCost\toracle64\tfullCoverage\t" +
                "deltaTop1\tdeltaTop5\tdeltaRankCost\tdeltaOracle64\tdeltaFullCoverage\tp50ms\tp95ms\tmaxMs",
        )
        results.forEach { result ->
            val summary = result.summary
            builder.appendLine(
                result.name + "\t" + result.mechanism + "\t" + summary.n + "\t" +
                    summary.top1 + "/" + summary.n + "(" + pct(summary.top1, summary.n) + "%)\t" +
                    summary.top5 + "/" + summary.n + "(" + pct(summary.top5, summary.n) + "%)\t" +
                    fmt(summary.rankCost) + "\t" +
                    summary.oracle + "/" + summary.n + "(" + pct(summary.oracle, summary.n) + "%)\t" +
                    summary.fullCoverage + "/" + summary.n + "\t" +
                    signed(summary.top1 - baseline.summary.top1) + "\t" +
                    signed(summary.top5 - baseline.summary.top5) + "\t" +
                    signedFmt(summary.rankCost - baseline.summary.rankCost) + "\t" +
                    signed(summary.oracle - baseline.summary.oracle) + "\t" +
                    signed(summary.fullCoverage - baseline.summary.fullCoverage) + "\t" +
                    fmt(summary.p50) + "\t" + fmt(summary.p95) + "\t" + fmt(summary.max),
            )
        }
    }

    private fun appendTransitions(builder: StringBuilder, baseline: VariantResult, results: List<VariantResult>) {
        builder.appendLine("[transitionsVsBaseline]")
        builder.appendLine("variant\ttop1Gain\ttop1Loss\ttop5Gain\ttop5Loss\toracleGain\toracleLoss\tfullCoverageLoss\trankCostDelta")
        results.drop(1).forEach { result ->
            val t = transitions(baseline.rows, result.rows)
            builder.appendLine(
                result.name + "\t" + t.top1Gain + "\t" + t.top1Loss + "\t" + t.top5Gain + "\t" + t.top5Loss + "\t" +
                    t.oracleGain + "\t" + t.oracleLoss + "\t" + t.fullCoverageLoss + "\t" + signedFmt(t.rankCostDelta),
            )
        }
    }

    /**
     * The task explicitly requires regressions to be reported, not only gains:
     * one negativeRegressions= line per variant lists both directions of
     * movement and the identifiers behind them.
     */
    private fun appendNegativeRegressions(builder: StringBuilder, baseline: VariantResult, results: List<VariantResult>) {
        builder.appendLine("[negativeRegressions]")
        builder.appendLine("variant\ttop5Gain\ttop5Loss\ttop5LossIds\trankDropIds\toracleLossIds")
        results.drop(1).forEach { result ->
            val t = transitions(baseline.rows, result.rows)
            builder.appendLine(
                result.name + "\t" + t.top5Gain + "\t" + t.top5Loss + "\t" +
                    "[" + t.top5LossIds.joinToString(", ") + "]\t" +
                    "[" + t.rankDropIds.joinToString(", ") + "]\t" +
                    "[" + t.oracleLossIds.joinToString(", ") + "]",
            )
        }
        builder.appendLine()
        results.drop(1).forEach { result ->
            val t = transitions(baseline.rows, result.rows)
            builder.appendLine(
                "negativeRegressions=" + result.name +
                    " top5Gain=" + t.top5Gain +
                    " top5Loss=" + t.top5Loss +
                    " top5LossIds=[" + t.top5LossIds.joinToString(", ") + "]" +
                    " rankDrops=[" + t.rankDropIds.joinToString(", ") + "]" +
                    " oracleLossIds=[" + t.oracleLossIds.joinToString(", ") + "]",
            )
        }
    }

    private fun appendConclusion(builder: StringBuilder, baseline: VariantResult, results: List<VariantResult>) {
        val candidates = results.drop(1)
        // The verdict is driven by single-mechanism cells; the optional
        // combination cell is reported separately and never treated as a default.
        val best = candidates.filterNot { it.mechanism.startsWith("combination") }.minWithOrNull(qualityComparator())
        val combination = candidates.firstOrNull { it.mechanism.startsWith("combination") }
        builder.appendLine("[conclusion]")
        builder.appendLine(
            "baseline: top1=" + ratio(baseline.summary.top1, baseline.summary.n) +
                " top5=" + ratio(baseline.summary.top5, baseline.summary.n) +
                " rankCost=" + fmt(baseline.summary.rankCost) +
                " oracle64=" + ratio(baseline.summary.oracle, baseline.summary.n) +
                " fullCoverage=" + ratio(baseline.summary.fullCoverage, baseline.summary.n),
        )
        if (best != null) {
            val t = transitions(baseline.rows, best.rows)
            val top1Delta = best.summary.top1 - baseline.summary.top1
            val top5Delta = best.summary.top5 - baseline.summary.top5
            val rankCostDelta = best.summary.rankCost - baseline.summary.rankCost
            val oracleDelta = best.summary.oracle - baseline.summary.oracle
            builder.appendLine("bestVariant=" + best.name + " tuning=" + tuningText(best.tuning))
            builder.appendLine(
                "bestVariant.deltaVsBaseline: top1=" + signed(top1Delta) + " top5=" + signed(top5Delta) +
                    " rankCost=" + signedFmt(rankCostDelta) + " oracle64=" + signed(oracleDelta) +
                    " fullCoverage=" + signed(best.summary.fullCoverage - baseline.summary.fullCoverage),
            )
            builder.appendLine(
                "bestVariant.transitions: top5Gain=" + t.top5Gain + " top5Loss=" + t.top5Loss +
                    " top1Gain=" + t.top1Gain + " top1Loss=" + t.top1Loss +
                    " oracleGain=" + t.oracleGain + " oracleLoss=" + t.oracleLoss +
                    " rankDrops=" + t.rankDropIds.size + " fullCoverageLoss=" + t.fullCoverageLoss,
            )
            combination?.let { cell ->
                val cellTransition = transitions(baseline.rows, cell.rows)
                builder.appendLine(
                    "combinationCell=" + cell.name +
                        " deltaTop1=" + signed(cell.summary.top1 - baseline.summary.top1) +
                        " deltaTop5=" + signed(cell.summary.top5 - baseline.summary.top5) +
                        " deltaRankCost=" + signedFmt(cell.summary.rankCost - baseline.summary.rankCost) +
                        " top5Gain=" + cellTransition.top5Gain + " top5Loss=" + cellTransition.top5Loss +
                        " (diagnostic only; not a production default)",
                )
            }
            val bestTop5Gain = best.summary.top5 - baseline.summary.top5
            val bestTop1Gain = best.summary.top1 - baseline.summary.top1
            val bestOracleGain = best.summary.oracle - baseline.summary.oracle
            val worth = bestTop5Gain > 0 && t.top5Loss == 0 && bestTop1Gain >= 0 && t.oracleLoss == 0
            val verdict = when {
                worth -> "supports_sentence_rerank_as_next_experiment_candidate"
                bestTop5Gain > 0 || bestTop1Gain > 0 || bestOracleGain > 0 -> "mixed_evidence_not_supported_as_production_default"
                else -> "rejects_sentence_rerank_no_metric_gain"
            }
            builder.appendLine("verdict=" + verdict)
            if (worth) {
                builder.appendLine(
                    "recommendation=keep " + best.name + " as a diagnostic candidate only; validate on an independent set and device p95 before any production consideration. " +
                        "Production defaults stay longSentenceCorpusWeight=0.0 / longSentenceDictionaryWeight=0.0.",
                )
            } else {
                val reasons = mutableListOf<String>()
                if (bestTop5Gain <= 0) reasons += "no Top5 gain (" + signed(bestTop5Gain) + ")"
                if (t.top5Loss > 0) reasons += t.top5Loss.toString() + " Top5 regression(s)"
                if (bestTop1Gain < 0) reasons += "Top1 loss (" + signed(bestTop1Gain) + ")"
                if (t.oracleLoss > 0) reasons += t.oracleLoss.toString() + " oracle64 loss(es)"
                if (reasons.isEmpty()) reasons += "no measurable benefit"
                builder.appendLine("recommendation=reject sentence rerank as the next production candidate: " + reasons.joinToString("; ") + ". Production defaults stay longSentenceCorpusWeight=0.0 / longSentenceDictionaryWeight=0.0.")
            }
            builder.appendLine("note=single-mechanism weights only; the combination cell is derived after the singles and is diagnostic, not a production default. Corpus/dictionary files and PinyinEngine.kt are untouched.")
        }
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
        val top5LossIds = ArrayList<String>()
        val rankDropIds = ArrayList<String>()
        val oracleLossIds = ArrayList<String>()
        baselineRows.forEach { base ->
            val variant = variantById[base.case.id] ?: return@forEach
            if (!base.top1 && variant.top1) top1Gain++
            if (base.top1 && !variant.top1) top1Loss++
            if (!base.top5 && variant.top5) top5Gain++
            if (base.top5 && !variant.top5) {
                top5Loss++
                top5LossIds += base.case.id + "(" + rankText(base.rank8) + "->" + rankText(variant.rank8) + ")"
            }
            if (base.rank8 >= 0 && variant.rank8 > base.rank8) {
                rankDropIds += base.case.id + "(" + rankText(base.rank8) + "->" + rankText(variant.rank8) + ")"
            }
            val baseOracle = base.rank64 >= 0
            val variantOracle = variant.rank64 >= 0
            if (!baseOracle && variantOracle) oracleGain++
            if (baseOracle && !variantOracle) {
                oracleLoss++
                oracleLossIds += base.case.id
            }
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
            top5LossIds = top5LossIds,
            rankDropIds = rankDropIds,
            oracleLossIds = oracleLossIds,
        )
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

    private fun ratio(value: Int, total: Int): String = value.toString() + "/" + total + "(" + pct(value, total) + "%)"
    private fun pct(value: Int, total: Int): String =
        if (total == 0) "0.0" else String.format(Locale.ROOT, "%.1f", value * 100.0 / total)

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
    private fun fmt2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
    private fun weightText(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
    private fun signed(value: Int): String = if (value >= 0) "+" + value else value.toString()
    private fun signedFmt(value: Double): String = if (value >= 0) "+" + fmt(value) else fmt(value)
    private fun rankText(rank: Int): String = if (rank < 0) "miss" else "r" + rank

    private fun tuningText(tuning: PinyinEngine.EngineTuning): String =
        "longSentenceCorpusWeight=" + fmt2(tuning.longSentenceCorpusWeight) +
            " longSentenceDictionaryWeight=" + fmt2(tuning.longSentenceDictionaryWeight) +
            " (all other tuning fields production default)"

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
        val lines = StringBuilder()
        lines.appendLine(
            "D05 long-sentence rerank ablation: n=" + baseline.summary.n +
                " baselineTop1=" + baseline.summary.top1 + "/90 baselineTop5=" + baseline.summary.top5 + "/90" +
                " variants=" + results.size,
        )
        results.drop(1).forEach { result ->
            val t = transitions(baseline.rows, result.rows)
            lines.appendLine(
                "  " + result.name + ": top1=" + result.summary.top1 + "/90 top5=" + result.summary.top5 +
                    "/90 rankCost=" + fmt(result.summary.rankCost) +
                    " oracle64=" + result.summary.oracle + "/90 fullCoverage=" + result.summary.fullCoverage +
                    "/90 top5Gain=" + t.top5Gain + " top5Loss=" + t.top5Loss +
                    " rankDrops=" + t.rankDropIds.size + " oracleLoss=" + t.oracleLoss,
            )
        }
        lines.append("wrote " + reportFile.absolutePath)
        return lines.toString()
    }

    private companion object {
        private const val SEARCH_LIMIT = 8
        private const val FULL_LIMIT = 64
        private const val MISS_RANK_COST = 9
        private const val MAX_SEARCH_INPUT_LENGTH = 64
        private const val DEV_SHA256 = "63983c5f9a490040592d6740e2e747e2b5a574d0a068accb44f25179153110ad"
        private const val REPORT_PATH = "build/d05/long-sentence-rerank-ablation.txt"
        private val LONG_SENTENCE_CORPUS_WEIGHTS = listOf(0.05, 0.10, 0.20, 0.40)
        private val LONG_SENTENCE_DICTIONARY_WEIGHTS = listOf(0.05, 0.10, 0.20, 0.40)
    }
}
