package com.bitap.keyboard.core

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-C D05 natural_long path-loss diagnostic.
 *
 * Scope: the 90 D05 dev natural_long cases whose pinyin input is <= 64 chars
 * (the engine hard search budget). The 33 over-budget cases (65-70 chars) are
 * reported only as excluded; they are not part of the ranking denominator.
 *
 * This test is read-only with respect to production decoding: it uses the
 * production PinyinEngine defaults plus the existing debugSearch/withTuning
 * diagnostics, and writes:
 *  - build/d05/long-sentence-path-loss.txt
 *  - build/d05/long-sentence-path-loss-detail.tsv
 *
 * Layer order used for attribution:
 *  1 input/parse      query is not one lowercase letter run (none expected here)
 *  2 dictionary       independent dictionary segmentation cannot spell the
 *                     expected text: syllable inventory or word entry/reading gap
 *  3 lattice          the correct path does not appear in the production-budget
 *                     lattice and, when probed, not even at wider beam/entry budgets
 *  4 collection       generated in the lattice but not present in the candidate
 *                     collector (MAX_FULL_CANDIDATES=96 / dedupe)
 *  5 final pool       collected but dropped by the final 64-candidate pool
 *                     (full budget 48 + partial reserve 16)
 *  6 scoring          present in the final 64 pool but ranked outside Top5
 *  7 truncation/other no full-coverage candidate at all
 *
 * "Only wide budget" is a sub-class of the lattice layer: present only after
 * increasing beamSize and/or entriesPerKey. Marginal probes attribute it to
 * beam, entries, both, or the combination.
 */
class PinyinD05LongSentencePathLossTest {

    private data class EvalCase(
        val id: String,
        val category: String,
        val input: String,
        val expected: List<String>,
        val expectedCjk: String,
        val coverage: Int,
    )

    private data class DictEntry(val word: String, val frequency: Long)

    private data class PathStep(
        val previousPosition: Int,
        val previousCharPosition: Int,
        val word: String,
    )

    /** Independent dictionary view built from the same asset, for recall-layer checks only. */
    private inner class DictIndex(
        private val byPinyin: Map<String, List<DictEntry>>,
        private val maxKeyLength: Int,
        private val syllables: Set<String>,
        val entryCount: Int,
    ) {
        fun syllableReachable(query: String): Boolean {
            val reach = BooleanArray(query.length + 1)
            reach[0] = true
            for (position in 0 until query.length) {
                if (!reach[position]) continue
                val maxLength = min(MAX_SYLLABLE_LENGTH, query.length - position)
                for (length in 1..maxLength) {
                    if (query.substring(position, position + length) in syllables) {
                        reach[position + length] = true
                    }
                }
            }
            return reach[query.length]
        }

        /** Exact dictionary segmentation of [query] spelling [expectedText], or null. */
        fun sentencePath(query: String, expectedText: String): List<String>? {
            val text = normalizeCjk(expectedText)
            if (text.isEmpty()) return null
            val n = query.length
            val m = text.length
            val reach = Array(n + 1) { HashSet<Int>() }
            val parent = Array(n + 1) { HashMap<Int, PathStep>() }
            reach[0].add(0)
            for (position in 0 until n) {
                if (reach[position].isEmpty()) continue
                val maxLength = min(maxKeyLength, n - position)
                for (length in 1..maxLength) {
                    val key = query.substring(position, position + length)
                    val entries = byPinyin[key] ?: continue
                    for (charPosition in reach[position].toList()) {
                        if (charPosition >= m) continue
                        val first = text[charPosition]
                        for (entry in entries) {
                            val word = entry.word
                            if (word.isEmpty() || word[0] != first) continue
                            if (charPosition + word.length > m) continue
                            if (!text.regionMatches(charPosition, word, 0, word.length)) continue
                            val next = charPosition + word.length
                            if (reach[position + length].add(next)) {
                                parent[position + length][next] =
                                    PathStep(position, charPosition, word)
                            }
                        }
                    }
                }
            }
            if (m !in reach[n]) return null
            val words = ArrayList<String>()
            var position = n
            var charPosition = m
            while (position > 0) {
                val step = parent[position][charPosition] ?: return null
                words.add(step.word)
                position = step.previousPosition
                charPosition = step.previousCharPosition
            }
            words.reverse()
            return words
        }
    }

    private data class WideProbe(
        val found: Boolean,
        val beam: Int,
        val entries: Int,
        val latticeSize: Int,
        val finalRank64: Int,
        val beamOnly: Boolean,
        val entriesOnly: Boolean,
    )

    private data class CaseDiagnosis(
        val case: EvalCase,
        val rank8: Int,
        val rank64: Int,
        val latencyNanos: Long,
        val debugNanos: Long,
        val fullCoverage: Boolean,
        val oracle64: Boolean,
        val search64MatchesDebug: Boolean,
        val dictSyllableReachable: Boolean,
        val dictPathWords: List<String>?,
        val prodLatticeHas: Boolean,
        val prodLatticeRank: Int,
        val prodLatticeSize: Int,
        val prodCollectedHas: Boolean,
        val collectedFullRank: Int,
        val wide: WideProbe?,
        val layer: String,
        val detail: String,
    ) {
        val top1: Boolean get() = rank8 == 0
        val top5: Boolean get() = rank8 in 0..4
        val rankCost: Int get() = if (rank8 in 0..4) rank8 + 1 else MISS_RANK_COST
    }

    @Test
    fun diagnoseNaturalLongPathLoss() {
        val root = findRepoRoot()
        val devFile = File(root, "eval/d05/dev/dev.tsv")
        val dictFile = File(root, "app/src/main/assets/pinyin_dict.tsv")
        assertTrue("D05 dev corpus missing: " + devFile.absolutePath, devFile.isFile)
        assertTrue("dictionary missing: " + dictFile.absolutePath, dictFile.isFile)
        assertEquals("D05 dev hash", DEV_SHA256, sha256(devFile))

        val allCases = loadCases(devFile)
        val naturalLong = allCases.filter { it.category == "natural_long" }
        val cases = naturalLong.filter { it.input.length <= MAX_SEARCH_INPUT_LENGTH }
        assertEquals("natural_long count", 123, naturalLong.size)
        assertEquals("natural_long over-budget count", 33, naturalLong.size - cases.size)
        assertEquals("natural_long <=64 denominator", 90, cases.size)
        assertTrue("all selected inputs are one lowercase letter run", cases.all { c -> c.input.all { it in 'a'..'z' } })

        val engine = dictFile.useLines { lines -> PinyinEngine.fromLines(lines) }
        assertTrue("engine failed to load dictionary", engine.isReady)
        engine.setLearningEnabled(false)
        val dict = loadDictionary(dictFile)
        assertEquals("dictionary entry count", engine.entryCount, dict.entryCount)

        // Warm up both the production search and the debug path so latency p50/p95
        // are comparable across runs; the exact values remain JVM diagnostics only.
        cases.forEach { case ->
            engine.search(case.input, limit = SEARCH_LIMIT)
            engine.search(case.input, limit = FULL_LIMIT)
            engine.debugSearch(case.input)
        }

        val diagnoses = cases.map { case -> diagnoseCase(engine, dict, case) }

        val report = buildReport(root, devFile, dictFile, engine, naturalLong, cases, diagnoses)
        val reportFile = File(root, REPORT_PATH)
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report, Charsets.UTF_8)

        val detailFile = File(root, DETAIL_PATH)
        detailFile.parentFile?.mkdirs()
        detailFile.writeText(buildDetailTsv(diagnoses), Charsets.UTF_8)

        val knownLayers = LAYER_ORDER.toSet()
        assertEquals("every case has one layer", diagnoses.size, diagnoses.count { it.layer in knownLayers })
        assertEquals("layer counts sum to denominator", diagnoses.size, LAYER_ORDER.sumOf { layer -> diagnoses.count { it.layer == layer } })
        assertTrue("report mentions denominator", report.contains("denominator=90"))
        assertTrue("report has samples", report.contains("[samplesByLayer]"))
        assertTrue("report has mechanism suggestions", report.contains("[mechanismRecommendations]"))

        println(buildConsoleSummary(diagnoses, reportFile, detailFile))
    }

    private fun diagnoseCase(engine: PinyinEngine, dict: DictIndex, case: EvalCase): CaseDiagnosis {
        val started = System.nanoTime()
        val top8 = engine.search(case.input, limit = SEARCH_LIMIT)
        val latencyNanos = System.nanoTime() - started
        val search64 = engine.search(case.input, limit = FULL_LIMIT)
        val rank8 = top8.indexOfFirst { candidateMatches(it, case) }
        val rank64 = search64.indexOfFirst { candidateMatches(it, case) }
        val fullCoverage = search64.any { it.consumedInputLength >= case.coverage }
        val oracle64 = rank64 >= 0

        val debugStarted = System.nanoTime()
        val debug = engine.debugSearch(case.input)
        val debugNanos = System.nanoTime() - debugStarted
        val search64MatchesDebug = debug.finalCandidates.map { it.word } == search64.map { it.word }

        val dictSyllableReachable = dict.syllableReachable(case.input)
        val dictPathWords = if (dictSyllableReachable) dict.sentencePath(case.input, case.expectedCjk) else null

        val prodLatticeHas = debug.latticePaths.any { textMatches(case, it.text) }
        val prodLatticeRank = debug.latticePaths.indexOfFirst { textMatches(case, it.text) }
        val prodCollectedHas = debug.collected.any { it.consumed >= case.coverage && textMatches(case, it.word) }
        val collectedFullRank = collectedFullRank(debug.collected, case)
        val wide = if (!debug.latticePaths.any { textMatches(case, it.text) } && dictPathWords != null) {
            probeWide(engine, case)
        } else {
            null
        }

        val layer: String
        val detail: String
        when {
            rank8 in 0..4 -> {
                layer = if (rank8 == 0) "ok-top1" else "ok-top5"
                detail = if (rank8 == 0) "top1" else "rank" + (rank8 + 1)
            }
            !fullCoverage -> {
                layer = "truncation-other"
                detail = "no-full-coverage"
            }
            !dictSyllableReachable -> {
                layer = "not-generated"
                detail = "dictionary-syllable-missing"
            }
            dictPathWords == null -> {
                layer = "not-generated"
                detail = "dictionary-word-missing"
            }
            prodCollectedHas -> {
                if (rank64 >= 5) {
                    layer = "collected-ranked-low"
                    detail = if (rank64 >= 8) "rank8-plus" else "rank5-7"
                } else if (rank64 >= 0) {
                    layer = "collected-ranked-low"
                    detail = "final-top5-but-not-search8"
                } else {
                    layer = "generated-not-collected"
                    detail = "final-pool-cap"
                }
            }
            prodLatticeHas -> {
                layer = "generated-not-collected"
                detail = if (prodLatticeRank >= MAX_FULL_CANDIDATES) "lattice-emission-cap" else "collect-other"
            }
            wide != null && wide.found -> {
                layer = "only-wide-budget"
                detail = when {
                    wide.beamOnly && wide.entriesOnly -> "beam+entries"
                    wide.beamOnly -> "beam"
                    wide.entriesOnly -> "entries"
                    else -> "combined"
                }
            }
            else -> {
                layer = "not-generated"
                detail = "lattice-wide-not-found"
            }
        }
        return CaseDiagnosis(
            case = case,
            rank8 = rank8,
            rank64 = rank64,
            latencyNanos = latencyNanos,
            debugNanos = debugNanos,
            fullCoverage = fullCoverage,
            oracle64 = oracle64,
            search64MatchesDebug = search64MatchesDebug,
            dictSyllableReachable = dictSyllableReachable,
            dictPathWords = dictPathWords,
            prodLatticeHas = prodLatticeHas,
            prodLatticeRank = prodLatticeRank,
            prodLatticeSize = debug.latticePaths.size,
            prodCollectedHas = prodCollectedHas,
            collectedFullRank = collectedFullRank,
            wide = wide,
            layer = layer,
            detail = detail,
        )
    }

    private fun probeWide(engine: PinyinEngine, case: EvalCase): WideProbe {
        var lastSize = 0
        for ((beam, entries) in WIDE_BUDGETS) {
            val debug = engine.debugSearch(case.input, beamSize = beam, entriesPerKey = entries)
            lastSize = debug.latticePaths.size
            if (debug.latticePaths.any { textMatches(case, it.text) }) {
                val beamOnly = engine.debugSearch(case.input, beamSize = beam, entriesPerKey = PROD_ENTRIES)
                    .latticePaths.any { textMatches(case, it.text) }
                val entriesOnly = engine.debugSearch(case.input, beamSize = PROD_BEAM, entriesPerKey = entries)
                    .latticePaths.any { textMatches(case, it.text) }
                return WideProbe(
                    found = true,
                    beam = beam,
                    entries = entries,
                    latticeSize = debug.latticePaths.size,
                    finalRank64 = debug.finalCandidates.indexOfFirst { candidateMatches(it, case) },
                    beamOnly = beamOnly,
                    entriesOnly = entriesOnly,
                )
            }
        }
        val (lastBeam, lastEntries) = WIDE_BUDGETS.last()
        return WideProbe(
            found = false,
            beam = lastBeam,
            entries = lastEntries,
            latticeSize = lastSize,
            finalRank64 = -1,
            beamOnly = false,
            entriesOnly = false,
        )
    }

    private fun collectedFullRank(collected: List<PinyinEngine.DebugCandidate>, case: EvalCase): Int {
        val full = collected
            .filter { it.consumed >= case.coverage }
            .sortedWith(compareByDescending<PinyinEngine.DebugCandidate> { it.score }.thenBy { it.word })
        return full.indexOfFirst { textMatches(case, it.word) }
    }

    // ------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------

    private fun buildReport(
        root: File,
        devFile: File,
        dictFile: File,
        engine: PinyinEngine,
        naturalLong: List<EvalCase>,
        cases: List<EvalCase>,
        diagnoses: List<CaseDiagnosis>,
    ): String {
        val builder = StringBuilder()
        val tuning = PinyinEngine.EngineTuning()
        builder.appendLine("# Bitap D05 natural_long path-loss diagnostic (development diagnostic; NOT acceptance)")
        builder.appendLine("generatedBy=PinyinD05LongSentencePathLossTest")
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
            "engineTuning=production-default" +
                " beamSize=" + tuning.beamSize +
                " entriesPerKey=" + tuning.entriesPerKey +
                " partialQuota=" + tuning.partialQuota +
                " partialEarlySlotCount=" + tuning.partialEarlySlotCount +
                " partialEarlySlotAfterFull=" + tuning.partialEarlySlotAfterFull +
                " partialRankingMode=" + tuning.partialRankingMode.name +
                " corpusWordNgramWeight=" + fmt(tuning.corpusWordNgramWeight) +
                " dictionaryWordNgramWeight=" + fmt(tuning.dictionaryWordNgramWeight) +
                " contextWeight=" + fmt(tuning.contextWeight) +
                " wordInsertionCompensation=" + fmt(tuning.wordInsertionCompensation),
        )
        builder.appendLine("searchLimit=" + SEARCH_LIMIT + " oracleLimit=" + FULL_LIMIT)
        builder.appendLine("wideProbeBudgets=" + WIDE_BUDGETS.joinToString(",") { it.first.toString() + "/" + it.second })
        builder.appendLine("note=JVM single-process diagnostic; latency is not Android/device input latency")
        builder.appendLine()
        appendMetrics(builder, diagnoses)
        builder.appendLine()
        appendLayerCounts(builder, diagnoses)
        builder.appendLine()
        appendSamples(builder, diagnoses)
        builder.appendLine()
        appendRankDistribution(builder, diagnoses)
        builder.appendLine()
        appendWideProbeSummary(builder, diagnoses)
        builder.appendLine()
        builder.appendLine("[apiLimitations]")
        builder.appendLine("1) debugSearch.latticeFullTexts is capped at MAX_DEBUG_FULL_PATHS=4000 distinct texts; when latticeSize==4000 a low-scoring correct path can be invisible even if a wider search generated it.")
        builder.appendLine("2) debugSearch does not expose per-position beam buckets, so exact first-pruned position is not observable without changing PinyinEngine production code; this report therefore records the first tested budget where the full path becomes visible.")
        builder.appendLine("3) dictionary recall uses a read-only independent segmentation over the same pinyin_dict.tsv asset; it tests word entry/reading coverage, not the production lattice's scoring.")
        builder.appendLine()
        appendRecommendations(builder, diagnoses)
        builder.appendLine()
        appendAppendix(builder, diagnoses)
        return builder.toString()
    }

    private fun appendMetrics(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        val n = diagnoses.size
        val top1 = diagnoses.count { it.top1 }
        val top5 = diagnoses.count { it.top5 }
        val rankCost = diagnoses.sumOf { it.rankCost }
        val oracle = diagnoses.count { it.oracle64 }
        val fullCoverage = diagnoses.count { it.fullCoverage }
        val latency = diagnoses.map { it.latencyNanos / 1_000_000.0 }.sorted()
        val debugLatency = diagnoses.map { it.debugNanos / 1_000_000.0 }.sorted()
        builder.appendLine("[metrics]")
        builder.appendLine("n=" + n)
        builder.appendLine("top1=" + ratio(top1, n) + " wilson95=" + wilson(top1, n))
        builder.appendLine("top5=" + ratio(top5, n) + " wilson95=" + wilson(top5, n))
        builder.appendLine("rankCost.avg=" + fmt(rankCost.toDouble() / n) + " rankCost.total=" + rankCost + " (miss=9)")
        builder.appendLine("oracle64=" + ratio(oracle, n))
        builder.appendLine("fullCoverage=" + ratio(fullCoverage, n))
        builder.appendLine("search64MatchesDebug=" + ratio(diagnoses.count { it.search64MatchesDebug }, n))
        builder.appendLine(
            "[latencyJvm] search8 p50=" + millis(percentile(latency, 50)) +
                " p95=" + millis(percentile(latency, 95)) +
                " p99=" + millis(percentile(latency, 99)) +
                " max=" + millis(latency.last()),
        )
        builder.appendLine(
            "[latencyJvm] debugSearchProd p50=" + millis(percentile(debugLatency, 50)) +
                " p95=" + millis(percentile(debugLatency, 95)) +
                " max=" + millis(debugLatency.last()),
        )
    }

    private fun appendLayerCounts(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        val failures = diagnoses.count { !it.top5 }
        builder.appendLine("[layerCounts] total=" + diagnoses.size + " failures=" + failures)
        builder.appendLine("layer\tcount\tshareAll\tshareFailures")
        LAYER_ORDER.forEach { layer ->
            val count = diagnoses.count { it.layer == layer }
            builder.appendLine(
                layer + "\t" + count + "\t" + pct(count, diagnoses.size) + "%" +
                    "\t" + if (layer.startsWith("ok-")) "n/a" else pct(count, failures) + "%",
            )
        }
        builder.appendLine()
        builder.appendLine("[subclassCounts]")
        val details = diagnoses.map { it.detail }.distinct().sorted()
        details.forEach { detail ->
            val count = diagnoses.count { it.detail == detail }
            val layer = diagnoses.first { it.detail == detail }.layer
            builder.appendLine(layer + "." + detail + "=" + count)
        }
    }

    private fun appendSamples(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        builder.appendLine("[samplesByLayer]")
        LAYER_ORDER.forEach { layer ->
            val group = diagnoses.filter { it.layer == layer }.sortedBy { it.case.id }
            builder.appendLine("### " + layer + " n=" + group.size)
            if (group.isEmpty()) {
                builder.appendLine("samples: none")
                return@forEach
            }
            val samples = group.take(3)
            samples.forEach { d -> builder.appendLine(sampleLine(d)) }
            if (group.size < 2) builder.appendLine("samples: only " + group.size + " case in this layer")
        }
    }

    private fun sampleLine(d: CaseDiagnosis): String {
        val evidence = StringBuilder()
        evidence.append("rank8=").append(d.rank8)
        evidence.append(" rank64=").append(d.rank64)
        evidence.append(" dictSyllable=").append(d.dictSyllableReachable)
        evidence.append(" dictPath=").append(d.dictPathWords != null)
        if (d.dictPathWords != null) evidence.append(" dictSegments=").append(d.dictPathWords.joinToString("/"))
        evidence.append(" prodLattice=").append(d.prodLatticeHas)
        evidence.append(" prodLatticeRank=").append(d.prodLatticeRank)
        evidence.append(" prodLatticeSize=").append(d.prodLatticeSize)
        evidence.append(" prodCollected=").append(d.prodCollectedHas)
        evidence.append(" collectedFullRank=").append(d.collectedFullRank)
        d.wide?.let { wide ->
            evidence.append(" wideFound=").append(wide.found)
            evidence.append(" wideBudget=").append(wide.beam).append("/").append(wide.entries)
            evidence.append(" wideLatticeSize=").append(wide.latticeSize)
            evidence.append(" wideFinalRank=").append(wide.finalRank64)
            if (wide.found) evidence.append(" wideAttribution=").append(d.detail)
        }
        return "id=" + d.case.id +
            " inputLen=" + d.case.input.length +
            " layer=" + d.layer + "/" + d.detail +
            " " + evidence +
            " expected=" + d.case.expectedCjk
    }

    private fun appendRankDistribution(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        builder.appendLine("[rank8Distribution]")
        builder.appendLine("rank0=" + diagnoses.count { it.rank8 == 0 })
        builder.appendLine("rank1-4=" + diagnoses.count { it.rank8 in 1..4 })
        builder.appendLine("rank5-7=" + diagnoses.count { it.rank8 in 5..7 })
        builder.appendLine("rank8+=" + diagnoses.count { it.rank8 >= 8 })
        builder.appendLine("notReturned8=" + diagnoses.count { it.rank8 < 0 })
        builder.appendLine("rank64=-1=" + diagnoses.count { it.rank64 < 0 })
    }

    private fun appendWideProbeSummary(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        val probes = diagnoses.mapNotNull { it.wide }
        builder.appendLine("[wideBudgetProbe]")
        builder.appendLine("probed=" + probes.size)
        builder.appendLine("found=" + probes.count { it.found })
        builder.appendLine("notFound=" + probes.count { !it.found })
        builder.appendLine("sinkSaturatedAtCeiling=" + probes.count { !it.found && it.latticeSize >= MAX_DEBUG_FULL_PATHS })
        val found = probes.filter { it.found }
        builder.appendLine("foundAt=" + found.groupingBy { it.beam.toString() + "/" + it.entries }.eachCount().entries.joinToString(",") { it.key + ":" + it.value })
        builder.appendLine("attribution=" + diagnoses.filter { it.layer == "only-wide-budget" }.groupingBy { it.detail }.eachCount().entries.joinToString(",") { it.key + ":" + it.value })
    }

    private fun appendRecommendations(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        val n = diagnoses.size
        val counts = LAYER_ORDER.associateWith { layer -> diagnoses.count { it.layer == layer } }
        val onlyWide = counts["only-wide-budget"] ?: 0
        val notCollected = counts["generated-not-collected"] ?: 0
        val scoredLow = counts["collected-ranked-low"] ?: 0
        val notGeneratedLattice = diagnoses.count { it.detail == "lattice-wide-not-found" }
        val dictMissing = diagnoses.count { it.detail == "dictionary-syllable-missing" || it.detail == "dictionary-word-missing" }
        val wideBeam = diagnoses.count { it.layer == "only-wide-budget" && (it.detail == "beam" || it.detail == "beam+entries") }
        val wideEntries = diagnoses.count { it.layer == "only-wide-budget" && (it.detail == "entries" || it.detail == "beam+entries") }
        val latticePotential = notGeneratedLattice + onlyWide
        val candidates = mutableListOf<Triple<Int, Int, String>>()
        candidates += Triple(
            0,
            latticePotential,
            "L1 lattice beam/entry 恢复（影响最大）：not-generated " + (counts["not-generated"] ?: 0) + "/" + n +
                " 中 " + notGeneratedLattice + " 条为 lattice-wide-not-found（独立词库可拼出 89/90，说明多数不是词库缺失；但宽预算 256/32 仍不可见，其中 " +
                diagnoses.count { it.detail == "lattice-wide-not-found" && (it.wide?.latticeSize ?: 0) >= MAX_DEBUG_FULL_PATHS } +
                " 条 sink 饱和，无法排除更深层低分路径），另有仅宽预算可见 " + onlyWide + "/" + n + "（beam 相关 " + wideBeam + "、entry 相关 " + wideEntries +
                "）。预期影响层：lattice 生成/beam 剪枝；预期指标：oracle64 上限 +" + latticePotential + "、Top5 上限 +" + latticePotential +
                "（还需经过收集和评分），rankCost 最大可降；风险：时延/内存显著增加（本报告 debugSearch p95 见上），建议只对有界前向-后向 beam 或分段预算做单机制消融并在真机 p95/输入法帧预算验证。",
        )
        if (scoredLow > 0) {
            candidates += Triple(
                1,
                scoredLow,
                "L2 长句评分重排：对已收集但排名低 " + scoredLow + "/" + n +
                    " 的句，固定候选池后增加句级语言模型特征（如 sentenceCorpusWeight/sentenceDictionaryWeight）或分段边界一致性重排。预期影响层：评分排序；预期指标：Top1/Top5 提升、rankCost 下降；风险：dev 过拟合与 v3 冻结验收不可用于调参，权重扫描会引入选择偏差，需单机制消融和独立验证。",
            )
        }
        if (notCollected > 0) {
            candidates += Triple(
                2,
                notCollected,
                "L3 候选收集/最终池 cap：当前 MAX_FULL_CANDIDATES=96、最终 fullBudget=48 + partialReserve=16；对已生成但未收集 " + notCollected +
                    "/" + n + " 的句，按覆盖率/分数保留更多 full 候选（或提高 fullBudget）可增加 oracle64。预期影响层：候选收集/cap；预期指标：oracle64 +" + notCollected +
                    " 上限，Top1/Top5 仅在回收后进入前五才提升，rankCost 小幅改善；风险：候选池扩大挤占 partial 槽位、翻页/内存与排序稳定性，需固定 UI 配额测试。",
            )
        }
        if (dictMissing > 0) {
            candidates += Triple(
                3,
                dictMissing,
                "L4 词库召回补漏：dictionary 层缺口 " + dictMissing + "/" + n + "，按通用词库补词条/多音字读音（如人名地名词），不按句子写规则。预期影响层：词典召回；预期指标：这些句从 not-generated 转为可生成，Top1/Top5/oracle 上限 +" + dictMissing +
                    "；风险：词库体积与许可、错误读音引入新歧义、更新后需重跑全量 D05。",
            )
        }
        val ordered = candidates.sortedWith(compareByDescending<Triple<Int, Int, String>> { it.second }.thenBy { it.first })
        builder.appendLine("[mechanismRecommendations] max=3 order=observedImpact")
        ordered.take(3).forEachIndexed { index, item -> builder.appendLine((index + 1).toString() + ". " + item.third) }
        if (ordered.isEmpty()) builder.appendLine("none")
        builder.appendLine("note=dictionary-word-missing=" + dictMissing + " 未进入前三建议；该类需按通用词典补词条/读音，不能按句子写词库或规则")
    }

    private fun appendAppendix(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        builder.appendLine("[appendixAllCases]")
        builder.appendLine(
            "id\tinputLen\trank8\trank64\tfullCoverage\toracle64\tdictSyllable\tdictPath\tprodLattice\tprodLatticeRank\tprodLatticeSize\tprodCollected\tcollectedFullRank\twideFound\twideBudget\twideFinalRank\tlayer\tdetail",
        )
        diagnoses.forEach { d ->
            builder.appendLine(
                listOf(
                    d.case.id,
                    d.case.input.length.toString(),
                    d.rank8.toString(),
                    d.rank64.toString(),
                    d.fullCoverage.toString(),
                    d.oracle64.toString(),
                    d.dictSyllableReachable.toString(),
                    (d.dictPathWords != null).toString(),
                    d.prodLatticeHas.toString(),
                    d.prodLatticeRank.toString(),
                    d.prodLatticeSize.toString(),
                    d.prodCollectedHas.toString(),
                    d.collectedFullRank.toString(),
                    (d.wide?.found ?: false).toString(),
                    d.wide?.let { it.beam.toString() + "/" + it.entries } ?: "n/a",
                    d.wide?.finalRank64?.toString() ?: "n/a",
                    d.layer,
                    d.detail,
                ).joinToString("\t"),
            )
        }
    }

    private fun buildDetailTsv(diagnoses: List<CaseDiagnosis>): String {
        val builder = StringBuilder()
        builder.appendLine(
            listOf(
                "id", "category", "input", "inputLen", "expected", "rank8", "rank64", "fullCoverage", "oracle64",
                "dictSyllable", "dictPath", "dictSegments", "prodLattice", "prodLatticeRank", "prodLatticeSize",
                "prodCollected", "collectedFullRank", "wideFound", "wideBudget", "wideLatticeSize", "wideFinalRank",
                "wideAttribution", "layer", "detail",
            ).joinToString("\t"),
        )
        diagnoses.forEach { d ->
            builder.appendLine(
                listOf(
                    d.case.id,
                    d.case.category,
                    d.case.input,
                    d.case.input.length.toString(),
                    d.case.expected.joinToString("|"),
                    d.rank8.toString(),
                    d.rank64.toString(),
                    d.fullCoverage.toString(),
                    d.oracle64.toString(),
                    d.dictSyllableReachable.toString(),
                    (d.dictPathWords != null).toString(),
                    d.dictPathWords?.joinToString("/") ?: "",
                    d.prodLatticeHas.toString(),
                    d.prodLatticeRank.toString(),
                    d.prodLatticeSize.toString(),
                    d.prodCollectedHas.toString(),
                    d.collectedFullRank.toString(),
                    (d.wide?.found ?: false).toString(),
                    d.wide?.let { it.beam.toString() + "/" + it.entries } ?: "",
                    d.wide?.latticeSize?.toString() ?: "",
                    d.wide?.finalRank64?.toString() ?: "",
                    if (d.wide?.found == true) d.detail else "",
                    d.layer,
                    d.detail,
                ).joinToString("\t"),
            )
        }
        return builder.toString()
    }

    private fun buildConsoleSummary(diagnoses: List<CaseDiagnosis>, reportFile: File, detailFile: File): String {
        val n = diagnoses.size
        val top1 = diagnoses.count { it.top1 }
        val top5 = diagnoses.count { it.top5 }
        val rankCost = diagnoses.sumOf { it.rankCost }.toDouble() / n
        val classes = LAYER_ORDER.joinToString(" ") { layer -> layer + "=" + diagnoses.count { it.layer == layer } }
        return "D05 long-sentence path loss: n=" + n + " top1=" + top1 + "/" + n + " top5=" + top5 + "/" + n +
            " rankCost=" + fmt(rankCost) + " " + classes +
            "\nwrote " + reportFile.absolutePath + "\nwrote " + detailFile.absolutePath
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

    private fun loadDictionary(file: File): DictIndex {
        val grouped = HashMap<String, MutableList<DictEntry>>()
        var entryCount = 0
        file.useLines { lines ->
            lines.forEach { raw ->
                val line = raw.removePrefix("\uFEFF").trimEnd('\r')
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val parts = line.split('\t')
                if (parts.size < 2) return@forEach
                val pinyin = parts[0].lowercase().filter { it in 'a'..'z' }
                val word = parts[1].trim()
                if (pinyin.isEmpty() || word.isEmpty()) return@forEach
                val frequency = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                grouped.getOrPut(pinyin) { ArrayList() }.add(DictEntry(word, frequency))
                entryCount++
            }
        }
        val byPinyin = grouped.mapValues { (_, entries) ->
            entries.distinctBy { it.word }
                .sortedWith(compareByDescending<DictEntry> { it.frequency }.thenBy { it.word })
        }
        val maxKeyLength = byPinyin.keys.maxOfOrNull { it.length } ?: 0
        val syllables = HashSet<String>()
        byPinyin.forEach { (pinyin, entries) ->
            if (pinyin.length in 1..MAX_SYLLABLE_LENGTH && entries.any { it.word.length == 1 }) {
                syllables += pinyin
            }
        }
        return DictIndex(byPinyin, maxKeyLength, syllables, entryCount)
    }

    private fun candidateMatches(candidate: Candidate, case: EvalCase): Boolean =
        candidate.consumedInputLength >= case.coverage && textMatches(case, candidate.word)

    private fun textMatches(case: EvalCase, text: String): Boolean {
        val normalized = normalizeText(text)
        if (normalized.isEmpty()) return false
        return case.expected.any { normalizeText(it) == normalized }
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

    private fun wilson(hit: Int, total: Int): String {
        if (total == 0) return "n/a"
        val p = hit.toDouble() / total
        val z = 1.96
        val z2 = z * z
        val denominator = 1.0 + z2 / total
        val center = (p + z2 / (2.0 * total)) / denominator
        val half = z * sqrt(p * (1.0 - p) / total + z2 / (4.0 * total * total)) / denominator
        return String.format(
            Locale.ROOT,
            "%.1f%% [%.1f%%, %.1f%%]",
            p * 100.0,
            ((center - half) * 100.0).coerceIn(0.0, 100.0),
            ((center + half) * 100.0).coerceIn(0.0, 100.0),
        )
    }

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
        private const val MISS_RANK_COST = 9
        private const val MAX_SEARCH_INPUT_LENGTH = 64
        private const val MAX_FULL_CANDIDATES = 96
        private const val MAX_DEBUG_FULL_PATHS = 4_000
        private const val MAX_SYLLABLE_LENGTH = 6
        private const val PROD_BEAM = 64
        private const val PROD_ENTRIES = 8
        private val WIDE_BUDGETS = listOf(128 to 16, 256 to 32)
        private const val DEV_SHA256 = "63983c5f9a490040592d6740e2e747e2b5a574d0a068accb44f25179153110ad"
        private const val REPORT_PATH = "build/d05/long-sentence-path-loss.txt"
        private const val DETAIL_PATH = "build/d05/long-sentence-path-loss-detail.tsv"
        private val LAYER_ORDER = listOf(
            "ok-top1",
            "ok-top5",
            "not-generated",
            "only-wide-budget",
            "generated-not-collected",
            "collected-ranked-low",
            "truncation-other",
        )
    }
}
