package com.bitap.keyboard.core

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D05 batch 1B: single-mechanism ablation of the optional backward completion
 * estimate as an auxiliary forward beam ordering key.
 *
 * The mechanism is production-disabled by default
 * (backwardCompletionEnabled=false, backwardCompletionLongInputOnly=true). This
 * test compares the production default against backward-enabled variants and a
 * plain beam-width reference, over the D05 dev natural cases:
 *  - natural_long with queryLength<=64 (n=90, the long-input ranking denominator)
 *  - natural rankable (natural_* cases with baseline full coverage, n=667)
 *  - natural_short (n=300) and natural_medium (n=277) top1/top5 changes
 *
 * Output: build/d05/backward-completion-ablation.txt
 */
class PinyinD05BackwardCompletionAblationTest {

    private data class EvalCase(
        val id: String,
        val category: String,
        val input: String,
        val expected: List<String>,
        val expectedCjk: String,
        val coverage: Int,
    )

    private data class CaseOutcome(
        val case: EvalCase,
        val rank8: Int,
        val rank64: Int,
        val oracle64: Boolean,
        val fullCoverage: Boolean,
        val latencyNanos: Long,
    ) {
        val top1: Boolean get() = rank8 == 0
        val top5: Boolean get() = rank8 in 0..4
        val rankCost: Int get() = if (rank8 in 0..4) rank8 + 1 else MISS_RANK_COST
    }

    private data class Metrics(
        val n: Int,
        val top1: Int,
        val top5: Int,
        val rankCostTotal: Int,
        val oracle: Int,
        val fullCoverage: Int,
        val latenciesMillis: List<Double>,
    ) {
        val rankCostAvg: Double get() = if (n == 0) 0.0 else rankCostTotal.toDouble() / n
    }

    private data class Variant(
        val name: String,
        val tuning: PinyinEngine.EngineTuning,
        val note: String,
    )

    private data class TransitionCounts(
        val top1Gain: Int,
        val top1Loss: Int,
        val top5Gain: Int,
        val top5Loss: Int,
        val oracleGain: Int,
        val oracleLoss: Int,
    )

    @Test
    fun runBackwardCompletionAblation() {
        val root = findRepoRoot()
        val devFile = File(root, "eval/d05/dev/dev.tsv")
        val dictFile = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assertTrue("D05 dev corpus missing: " + devFile.absolutePath, devFile.isFile)
        assertTrue("dictionary missing: " + dictFile.absolutePath, dictFile.isFile)
        assertEquals("D05 dev hash", DEV_SHA256, sha256(devFile))

        val allCases = loadCases(devFile)
        val naturalCases = allCases.filter { it.category.startsWith("natural_") }
        val shortCases = naturalCases.filter { it.category == "natural_short" }
        val mediumCases = naturalCases.filter { it.category == "natural_medium" }
        val longAllCases = naturalCases.filter { it.category == "natural_long" }
        val longCases = longAllCases.filter { it.input.length <= MAX_SEARCH_INPUT_LENGTH }
        assertEquals("natural_short", 300, shortCases.size)
        assertEquals("natural_medium", 277, mediumCases.size)
        assertEquals("natural_long total", 123, longAllCases.size)
        assertEquals("natural_long <=64", 90, longCases.size)

        val baseEngine = dictFile.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue("engine failed to load dictionary", baseEngine.isReady)
        baseEngine.setLearningEnabled(false)

        // Default tuning flags are the production contract.
        val defaults = PinyinEngine.EngineTuning()
        assertFalse("backwardCompletionEnabled default", defaults.backwardCompletionEnabled)
        assertTrue("backwardCompletionLongInputOnly default", defaults.backwardCompletionLongInputOnly)
        val defaultView = baseEngine.withTuning(PinyinEngine.EngineTuning())
        // Cover short, medium and long inputs so the longInput branch of the tuned
        // view is exercised too; the previous take(30) sampled only natural_short.
        val defaultEquivalenceCases = naturalCases.filter { it.category == "natural_short" }.take(10) +
            naturalCases.filter { it.category == "natural_medium" }.take(10) +
            naturalCases.filter { it.category == "natural_long" && it.input.length <= MAX_SEARCH_INPUT_LENGTH }.take(10)
        assertEquals("default equivalence sample size", 30, defaultEquivalenceCases.size)
        defaultEquivalenceCases.forEach { case ->
            assertEquals("default view search8 must match base: " + case.id, baseEngine.search(case.input, limit = 8), defaultView.search(case.input, limit = 8))
            assertEquals("default view search64 must match base: " + case.id, baseEngine.search(case.input, limit = 64), defaultView.search(case.input, limit = 64))
        }

        val variants = listOf(
            Variant("baseline", PinyinEngine.EngineTuning(), "production defaults"),
            Variant(
                "backward@beam64-long",
                PinyinEngine.EngineTuning(backwardCompletionEnabled = true),
                "backward completion as beam sort key, beam64, queries >= longInputThreshold",
            ),
            Variant(
                "backward@beam64-all",
                PinyinEngine.EngineTuning(backwardCompletionEnabled = true, backwardCompletionLongInputOnly = false),
                "backward completion on every query length",
            ),
            Variant(
                "backward@beam48-long",
                PinyinEngine.EngineTuning(beamSize = 48, backwardCompletionEnabled = true),
                "backward completion with a narrower beam48",
            ),
            Variant("beam96", PinyinEngine.EngineTuning(beamSize = 96), "plain beam widening reference"),
            Variant("beam128", PinyinEngine.EngineTuning(beamSize = 128), "plain beam widening reference"),
        )
        assertTrue("variant count", variants.size >= 6)

        val outcomesByVariant = LinkedHashMap<String, List<CaseOutcome>>()
        val cappedByVariant = LinkedHashMap<String, Int>()
        variants.forEach { variant ->
            val engine = if (variant.name == "baseline") baseEngine else baseEngine.withTuning(variant.tuning)
            naturalCases.take(12).forEach { case ->
                engine.search(case.input, limit = 8)
                engine.search(case.input, limit = 64)
            }
            val outcomes = evaluateCases(engine, naturalCases)
            outcomesByVariant[variant.name] = outcomes
            cappedByVariant[variant.name] = engine.backwardCompletionCappedQueryCount()
        }

        val baseline = outcomesByVariant.getValue("baseline")
        val baselineRankableIds = baseline
            .filter { it.case.category.startsWith("natural_") && it.fullCoverage }
            .map { it.case.id }
            .toSet()
        assertEquals("natural rankable (baseline fullCoverage) denominator", 667, baselineRankableIds.size)

        fun subset(name: String, predicate: (EvalCase) -> Boolean): List<CaseOutcome> =
            outcomesByVariant.getValue(name).filter { predicate(it.case) }

        fun metricsFor(name: String, predicate: (EvalCase) -> Boolean): Metrics =
            metrics(subset(name, predicate))

        val targetReasons = loadTargetReasons(root)
        val report = buildReport(
            root = root,
            devFile = devFile,
            dictFile = dictFile,
            engine = baseEngine,
            naturalCases = naturalCases,
            shortCases = shortCases,
            mediumCases = mediumCases,
            longCases = longCases,
            variants = variants,
            outcomesByVariant = outcomesByVariant,
            cappedByVariant = cappedByVariant,
            baselineRankableIds = baselineRankableIds,
            targetReasons = targetReasons,
        )
        val reportFile = File(root, REPORT_PATH)
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report, Charsets.UTF_8)

        assertTrue("report has variant table", report.contains("[variantTable]"))
        assertTrue("report has transitions", report.contains("[transitionsVsBaseline]"))
        assertTrue("report has latency", report.contains("[latency]"))
        assertTrue("report has conclusion", report.contains("[conclusion]"))
        assertTrue("report has api notes", report.contains("[apiNotes]"))
        assertTrue("report long denominator", report.contains("longDenominator=90"))
        assertTrue("report natural denominator", report.contains("naturalRankableDenominator=667"))
        assertTrue("report variant count", variants.size >= 6)

        // Pin the measured outcomes, not just report headings: a mechanism that
        // silently degrades to a no-op would make backward long oracle stay 22.
        fun longMetrics(name: String) = metricsFor(name) { case ->
            case.category == "natural_long" && case.input.length <= MAX_SEARCH_INPUT_LENGTH
        }
        val baselineLong = longMetrics("baseline")
        val backwardLong = longMetrics("backward@beam64-long")
        assertEquals("baseline long top1", 3, baselineLong.top1)
        assertEquals("baseline long top5", 14, baselineLong.top5)
        assertEquals("baseline long oracle64", 22, baselineLong.oracle)
        assertEquals("backward long top1 must stay at baseline", baselineLong.top1, backwardLong.top1)
        assertEquals("backward long top5 must stay at baseline", baselineLong.top5, backwardLong.top5)
        assertEquals("backward long oracle64 must improve by exactly one", 23, backwardLong.oracle)
        assertEquals("no backward completion query may hit its cap on dev", 0, cappedByVariant.values.sum())

        println(buildConsoleSummary(variants, outcomesByVariant, baselineRankableIds, reportFile))
    }

    private fun evaluateCases(engine: PinyinEngine, cases: List<EvalCase>): List<CaseOutcome> = cases.map { case ->
        val started = System.nanoTime()
        val top8 = engine.search(case.input, limit = SEARCH_LIMIT)
        val latencyNanos = System.nanoTime() - started
        val search64 = engine.search(case.input, limit = FULL_LIMIT)
        CaseOutcome(
            case = case,
            rank8 = top8.indexOfFirst { matchesCandidate(it, case) },
            rank64 = search64.indexOfFirst { matchesCandidate(it, case) },
            oracle64 = search64.any { matchesCandidate(it, case) },
            fullCoverage = search64.any { it.consumedInputLength >= case.coverage },
            latencyNanos = latencyNanos,
        )
    }

    private fun metrics(outcomes: List<CaseOutcome>): Metrics {
        val latencies = outcomes.map { it.latencyNanos / 1_000_000.0 }.sorted()
        return Metrics(
            n = outcomes.size,
            top1 = outcomes.count { it.top1 },
            top5 = outcomes.count { it.top5 },
            rankCostTotal = outcomes.sumOf { it.rankCost },
            oracle = outcomes.count { it.oracle64 },
            fullCoverage = outcomes.count { it.fullCoverage },
            latenciesMillis = latencies,
        )
    }

    private fun transitions(baseline: List<CaseOutcome>, variant: List<CaseOutcome>): TransitionCounts {
        require(baseline.size == variant.size)
        var top1Gain = 0
        var top1Loss = 0
        var top5Gain = 0
        var top5Loss = 0
        var oracleGain = 0
        var oracleLoss = 0
        baseline.indices.forEach { index ->
            val before = baseline[index]
            val after = variant[index]
            if (!before.top1 && after.top1) top1Gain++
            if (before.top1 && !after.top1) top1Loss++
            if (!before.top5 && after.top5) top5Gain++
            if (before.top5 && !after.top5) top5Loss++
            if (!before.oracle64 && after.oracle64) oracleGain++
            if (before.oracle64 && !after.oracle64) oracleLoss++
        }
        return TransitionCounts(top1Gain, top1Loss, top5Gain, top5Loss, oracleGain, oracleLoss)
    }

    // ------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------

    private fun buildReport(
        root: File,
        devFile: File,
        dictFile: File,
        engine: PinyinEngine,
        naturalCases: List<EvalCase>,
        shortCases: List<EvalCase>,
        mediumCases: List<EvalCase>,
        longCases: List<EvalCase>,
        variants: List<Variant>,
        outcomesByVariant: Map<String, List<CaseOutcome>>,
        cappedByVariant: Map<String, Int>,
        baselineRankableIds: Set<String>,
        targetReasons: Map<String, String>?,
    ): String {
        val baseline = outcomesByVariant.getValue("baseline")
        val builder = StringBuilder()
        builder.appendLine("# Bitap D05 backward-completion beam ablation (development diagnostic; NOT acceptance)")
        builder.appendLine("generatedBy=PinyinD05BackwardCompletionAblationTest")
        builder.appendLine("corpus=" + relativePath(root, devFile))
        builder.appendLine("corpusSha256=" + sha256(devFile))
        builder.appendLine("dictionary=" + relativePath(root, dictFile))
        builder.appendLine("dictionarySha256=" + sha256(dictFile))
        builder.appendLine("dictionaryEntries=" + engine.entryCount)
        builder.appendLine("casePool=natural_short+natural_medium+natural_long (mixed_en excluded)")
        builder.appendLine("naturalCases=" + naturalCases.size)
        builder.appendLine("naturalShort=300")
        builder.appendLine("naturalMedium=277")
        builder.appendLine("longDenominator=" + longCases.size)
        builder.appendLine("naturalRankableDenominator=" + baselineRankableIds.size)
        builder.appendLine("rankableDefinition=natural_* case with baseline search(limit=64) full coverage; fixed across variants")
        builder.appendLine("searchLimit=8 oracleLimit=64")
        builder.appendLine("rankCostMiss=9")
        builder.appendLine("note=JVM single-process diagnostic; latency is not Android/device input latency")
        builder.appendLine()

        builder.appendLine("[variantTable]")
        builder.appendLine(
            "variant\tlongTop1\tlongTop5\tlongRankCost\tlongOracle\tlongFullCoverage\t" +
                "natRankTop1\tnatRankTop5\tnatRankOracle\tshortTop1\tshortTop5\tmediumTop1\tmediumTop5\tcappedQueries",
        )
        variants.forEach { variant ->
            val all = outcomesByVariant.getValue(variant.name)
            val long = all.filter { it.case in longCases }
            val rankable = all.filter { it.case.id in baselineRankableIds }
            val short = all.filter { it.case.category == "natural_short" }
            val medium = all.filter { it.case.category == "natural_medium" }
            val lm = metrics(long)
            val nm = metrics(rankable)
            val sm = metrics(short)
            val mm = metrics(medium)
            builder.appendLine(
                listOf(
                    variant.name,
                    ratio(lm.top1, lm.n),
                    ratio(lm.top5, lm.n),
                    fmt(lm.rankCostAvg),
                    ratio(lm.oracle, lm.n),
                    ratio(lm.fullCoverage, lm.n),
                    ratio(nm.top1, nm.n),
                    ratio(nm.top5, nm.n),
                    ratio(nm.oracle, nm.n),
                    ratio(sm.top1, sm.n),
                    ratio(sm.top5, sm.n),
                    ratio(mm.top1, mm.n),
                    ratio(mm.top5, mm.n),
                    cappedByVariant[variant.name].toString(),
                ).joinToString("\t"),
            )
        }
        builder.appendLine()
        builder.appendLine("legend=ratios are count/denominator(percent); long=natural_long queryLength<=64; natRank=fixed " + baselineRankableIds.size + " baseline-rankable natural cases")
        builder.appendLine("variantNotes=" + variants.joinToString(" | ") { it.name + ":" + it.note })
        builder.appendLine()

        builder.appendLine("[transitionsVsBaseline]")
        builder.appendLine("variant\tlongTop1Gain\tlongTop1Loss\tlongTop5Gain\tlongTop5Loss\tlongOracleGain\tlongOracleLoss\tnatTop1Gain\tnatTop1Loss\tnatTop5Gain\tnatTop5Loss\tshortTop5Gain\tshortTop5Loss\tmediumTop5Gain\tmediumTop5Loss")
        variants.filter { it.name != "baseline" }.forEach { variant ->
            val all = outcomesByVariant.getValue(variant.name)
            val longBase = baseline.filter { it.case in longCases }
            val longVar = all.filter { it.case in longCases }
            val natBase = baseline.filter { it.case.id in baselineRankableIds }
            val natVar = all.filter { it.case.id in baselineRankableIds }
            val shortBase = baseline.filter { it.case.category == "natural_short" }
            val shortVar = all.filter { it.case.category == "natural_short" }
            val mediumBase = baseline.filter { it.case.category == "natural_medium" }
            val mediumVar = all.filter { it.case.category == "natural_medium" }
            val lt = transitions(longBase, longVar)
            val nt = transitions(natBase, natVar)
            val st = transitions(shortBase, shortVar)
            val mt = transitions(mediumBase, mediumVar)
            builder.appendLine(
                listOf(
                    variant.name,
                    lt.top1Gain, lt.top1Loss, lt.top5Gain, lt.top5Loss, lt.oracleGain, lt.oracleLoss,
                    nt.top1Gain, nt.top1Loss, nt.top5Gain, nt.top5Loss,
                    st.top5Gain, st.top5Loss, mt.top5Gain, mt.top5Loss,
                ).joinToString("\t"),
            )
        }
        builder.appendLine("definition=gain: baseline miss -> variant hit; loss: baseline hit -> variant miss; oracle uses search(limit=64)")
        builder.appendLine()

        builder.appendLine("[latency]")
        builder.appendLine("variant\tlongP50ms\tlongP95ms\tlongMaxMs\tnaturalP50ms\tnaturalP95ms\tnaturalMaxMs")
        variants.forEach { variant ->
            val all = outcomesByVariant.getValue(variant.name)
            val long = all.filter { it.case in longCases }
            val lm = metrics(long)
            val nm = metrics(all)
            builder.appendLine(
                listOf(
                    variant.name,
                    millis(percentile(lm.latenciesMillis, 50)),
                    millis(percentile(lm.latenciesMillis, 95)),
                    millis(lm.latenciesMillis.lastOrNull() ?: 0.0),
                    millis(percentile(nm.latenciesMillis, 50)),
                    millis(percentile(nm.latenciesMillis, 95)),
                    millis(nm.latenciesMillis.lastOrNull() ?: 0.0),
                ).joinToString("\t"),
            )
        }
        builder.appendLine()
        appendMechanismEffectByTargetLayer(builder, variants, outcomesByVariant, longCases, targetReasons)
        builder.appendLine()
        appendConclusion(builder, variants, outcomesByVariant, baselineRankableIds, longCases, cappedByVariant, targetReasons)
        builder.appendLine()
        appendApiNotes(builder)
        return builder.toString()
    }

    private fun appendMechanismEffectByTargetLayer(
        builder: StringBuilder,
        variants: List<Variant>,
        outcomesByVariant: Map<String, List<CaseOutcome>>,
        longCases: List<EvalCase>,
        targetReasons: Map<String, String>?,
    ) {
        builder.appendLine("[mechanismEffectByTargetLayer]")
        if (targetReasons == null) {
            builder.appendLine("targetTraceDetail=unavailable (run PinyinD05LongSentenceTargetTraceTest first)")
            return
        }
        val baseline = outcomesByVariant.getValue("baseline").filter { it.case in longCases }
        builder.appendLine("targetLayer\ttotalLong\tvariant\toracleGain\toracleLoss\ttop5Gain\ttop5Loss\ttop1Gain\ttop1Loss")
        val order = listOf("NONE", "BEAM_PRUNED", "ENTRY_TRUNCATED", "NO_DICTIONARY_ENTRY", "TRUNCATED_UNKNOWN")
        order.forEach { reason ->
            val ids = longCases.filter { targetReasons[it.id] == reason }.map { it.id }.toSet()
            if (ids.isEmpty()) return@forEach
            val baseGroup = baseline.filter { it.case.id in ids }
            variants.filter { it.name != "baseline" }.forEach { variant ->
                val variantGroup = outcomesByVariant.getValue(variant.name).filter { it.case.id in ids }
                val t = transitions(baseGroup, variantGroup)
                builder.appendLine(
                    listOf(
                        reason, ids.size.toString(), variant.name,
                        t.oracleGain.toString(), t.oracleLoss.toString(),
                        t.top5Gain.toString(), t.top5Loss.toString(),
                        t.top1Gain.toString(), t.top1Loss.toString(),
                    ).joinToString("\t"),
                )
            }
        }
        builder.appendLine("definition=per long case target-layer from task-2 detail TSV; gain/loss vs baseline on that fixed subset")
    }

    private fun appendConclusion(
        builder: StringBuilder,
        variants: List<Variant>,
        outcomesByVariant: Map<String, List<CaseOutcome>>,
        baselineRankableIds: Set<String>,
        longCases: List<EvalCase>,
        cappedByVariant: Map<String, Int>,
        targetReasons: Map<String, String>?,
    ) {
        val baseline = outcomesByVariant.getValue("baseline")
        val baselineLong = metrics(baseline.filter { it.case in longCases })
        val longTarget = 24
        var bestName = "baseline"
        var bestTop5 = baselineLong.top5
        var bestOracle = baselineLong.oracle
        variants.forEach { variant ->
            val lm = metrics(outcomesByVariant.getValue(variant.name).filter { it.case in longCases })
            if (lm.top5 > bestTop5 || (lm.top5 == bestTop5 && lm.oracle > bestOracle)) {
                bestTop5 = lm.top5
                bestOracle = lm.oracle
                bestName = variant.name
            }
        }
        val bestVariantMetrics = metrics(outcomesByVariant.getValue(bestName).filter { it.case in longCases })
        builder.appendLine("[conclusion]")
        builder.appendLine("reviewTarget=long Top5>=24/90 (review §4A)")
        builder.appendLine("baselineLongTop1=" + baselineLong.top1 + "/" + baselineLong.n)
        builder.appendLine("baselineLongTop5=" + baselineLong.top5 + "/" + baselineLong.n)
        builder.appendLine("baselineLongOracle64=" + baselineLong.oracle + "/" + baselineLong.n)
        builder.appendLine("bestVariant=" + bestName + " longTop5=" + bestTop5 + "/" + baselineLong.n + " longTop1=" + bestVariantMetrics.top1 + " longOracle64=" + bestVariantMetrics.oracle)
        builder.appendLine("targetMet=" + (bestTop5 >= longTarget))
        if (bestTop5 >= longTarget) {
            builder.appendLine("mechanismRecommendation=candidate for follow-up promotion review; default remains false in this batch")
        } else {
            builder.appendLine("mechanismRecommendation=keep production default false; backward completion alone did not reach review §4A long Top5 target")
        }
        builder.appendLine()
        builder.appendLine("layerContextFromTask2TargetTrace (build/d05/long-sentence-target-trace.txt, denominator=90):")
        builder.appendLine("- NONE=24: full target path already exists in production lattice bucket[n]; backward completion cannot add it, only collection/ranking caps decide its final rank.")
        builder.appendLine("- BEAM_PRUNED=43: the only layer backward completion can address directly, because target paths are dropped by the forward beam before reaching bucket[n].")
        builder.appendLine("- ENTRY_TRUNCATED=22: identical score landscape but target continuations are beyond entriesPerKey; beam reordering cannot recover them (needs entriesPerKey or dictionary/segmentation work).")
        builder.appendLine("- NO_DICTIONARY_ENTRY=1, TRUNCATED_UNKNOWN=0.")
        builder.appendLine("upperBoundReasoning=even if backward completion perfectly recovered all 43 BEAM_PRUNED cases, long Top5 is still capped by the 22 ENTRY_TRUNCATED cases and by final collection/scoring of the 24 already-generated targets; theoretical ceiling is 90-22=68/90 only under perfect beam and perfect ranking, so this single mechanism cannot be expected to reach 24/90 by itself if beam recovery is partial.")
        val cappedTotal = cappedByVariant.filterKeys { it != "baseline" }.values.sum()
        builder.appendLine("backwardCompletionCappedQueries=" + cappedByVariant.entries.joinToString(",") { it.key + ":" + it.value } + " (totalNonBaseline=" + cappedTotal + ")")
        builder.appendLine("interpretation=if cappedQueries>0 on long inputs, the backward DP exceeded the " + "5,000,000-op / 50ms cap for those queries and silently fell back to the plain forward score; those cases are effectively baseline behaviour.")
        if (targetReasons != null) {
            val longIds = longCases.map { it.id }.toSet()
            val order = listOf("NONE", "BEAM_PRUNED", "ENTRY_TRUNCATED", "NO_DICTIONARY_ENTRY", "TRUNCATED_UNKNOWN")
            builder.appendLine("targetTraceLayerCountsLong=" + order.joinToString(",") { reason ->
                reason + "=" + targetReasons.count { (id, r) -> id in longIds && r == reason }
            })
            val beamPrunedIds = longCases.filter { targetReasons[it.id] == "BEAM_PRUNED" }.map { it.id }.toSet()
            val baseBeam = baseline.filter { it.case.id in beamPrunedIds }
            val bestBeam = outcomesByVariant.getValue(bestName).filter { it.case.id in beamPrunedIds }
            val bt = transitions(baseBeam, bestBeam)
            builder.appendLine("bestVariantBeamPrunedEffect=oracleGain=" + bt.oracleGain + " oracleLoss=" + bt.oracleLoss + " top5Gain=" + bt.top5Gain + " top5Loss=" + bt.top5Loss + " top1Gain=" + bt.top1Gain + " top1Loss=" + bt.top1Loss)
            builder.appendLine("explanation=backward completion only affects beam membership for BEAM_PRUNED cases; the observed effect on that layer is reported above and is partial, not a full recovery of the 43 cases.")
        } else {
            builder.appendLine("targetTraceLayerCountsLong=unavailable")
        }
    }

    private fun appendApiNotes(builder: StringBuilder) {
        builder.appendLine("[apiNotes]")
        builder.appendLine("1) EngineTuning.backwardCompletionEnabled=false and backwardCompletionLongInputOnly=true are production defaults. With the switch off, collectLattice takes the original score-only sort path; PinyinEngineTest/D05DevEval/PathLoss/TargetTrace regressions pin that behaviour.")
        builder.appendLine("2) With the switch on, completion[position][prevWord] is a backward DP over the same dictionary entries, effectiveEntries and maxKeyLength as the forward pass: best achievable total from position to n including wordScore, numeral bonus, boundaryBonus(prevWord,nextWord) and the rest of the path; completion[n][*]=0.")
        builder.appendLine("3) Only distinct words that can end at a position under unpruned forward reachability become DP states. The DP is capped at 5,000,000 inner operations and a 50ms wall-clock budget per query; when exceeded it returns null, the query falls back to plain forward score for that call, and the engine's diagnostic counter increments.")
        builder.appendLine("4) The beam ordering key becomes path.score + completion[position][path.word] (missing completion adds 0.0). Bucket scores, candidate scores, sentenceRerankBonus, collection caps and final ranking are unchanged; only beam membership can differ.")
        builder.appendLine("5) No gold target/expected text is used by the mechanism; it is a pure query-side completion estimate. The ablation only reads the dev corpus to compute metrics.")
        builder.appendLine("6) Variants: baseline (production), backward@beam64-long, backward@beam64-all, backward@beam48-long, beam96, beam128. The last two are plain beam-widening references, not the backward mechanism.")
    }

    private fun buildConsoleSummary(
        variants: List<Variant>,
        outcomesByVariant: Map<String, List<CaseOutcome>>,
        baselineRankableIds: Set<String>,
        reportFile: File,
    ): String {
        val baseline = outcomesByVariant.getValue("baseline")
        val longIds = baseline.filter { it.case.category == "natural_long" && it.case.input.length <= 64 }.map { it.case.id }.toSet()
        fun longMetrics(name: String): Metrics = metrics(outcomesByVariant.getValue(name).filter { it.case.id in longIds })
        fun rankMetrics(name: String): Metrics = metrics(outcomesByVariant.getValue(name).filter { it.case.id in baselineRankableIds })
        val rows = variants.joinToString("\n") { variant ->
            val lm = longMetrics(variant.name)
            val nm = rankMetrics(variant.name)
            variant.name + ": longTop1=" + lm.top1 + "/" + lm.n + " longTop5=" + lm.top5 + "/" + lm.n +
                " longOracle=" + lm.oracle + "/" + lm.n + " natTop5=" + nm.top5 + "/" + nm.n
        }
        return "D05 backward completion ablation: " + variants.size + " variants\n" + rows +
            "\nwrote " + reportFile.absolutePath
    }

    // ------------------------------------------------------------------
    // Loading / matching / formatting
    // ------------------------------------------------------------------

    /** Optional task-2 layer map; the ablation report degrades gracefully when it is absent. */
    private fun loadTargetReasons(root: File): Map<String, String>? {
        val file = File(root, "build/d05/long-sentence-target-trace-detail.tsv")
        if (!file.isFile) return null
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val header = lines.first().split('\t')
        val idIndex = header.indexOf("id")
        val reasonIndex = header.indexOf("firstLossReason")
        if (idIndex < 0 || reasonIndex < 0) return null
        return lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size <= idIndex || parts.size <= reasonIndex) return@mapNotNull null
            parts[idIndex] to parts[reasonIndex]
        }.toMap()
    }

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

    private fun matchesCandidate(candidate: Candidate, case: EvalCase): Boolean {
        val normalized = normalizeText(candidate.word)
        if (normalized.isEmpty()) return false
        return candidate.consumedInputLength >= case.coverage &&
            case.expected.any { normalizeText(it) == normalized }
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

    private fun ratio(value: Int, total: Int): String =
        value.toString() + "/" + total + "(" + pct(value, total) + "%)"

    private fun pct(value: Int, total: Int): String =
        if (total == 0) "0.0" else String.format(Locale.ROOT, "%.1f", value * 100.0 / total)

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun millis(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

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

    private companion object {
        private const val SEARCH_LIMIT = 8
        private const val FULL_LIMIT = 64
        private const val MAX_SEARCH_INPUT_LENGTH = 64
        private const val MISS_RANK_COST = 9
        private const val DEV_SHA256 = "63983c5f9a490040592d6740e2e747e2b5a574d0a068accb44f25179153110ad"
        private const val REPORT_PATH = "build/d05/backward-completion-ablation.txt"
    }
}
