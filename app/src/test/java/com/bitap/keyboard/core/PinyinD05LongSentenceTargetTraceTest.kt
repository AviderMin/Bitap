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
 * D05 natural_long target-path trace diagnostic (P1-C follow-up to the
 * long-sentence path-loss report).
 *
 * The previous diagnostic used debugSearch, whose full-path sink is capped at
 * 4000 distinct texts. That cap makes 58/90 "not-generated" outcomes
 * unattributable: the correct path may exist in bucket[n] but below the sink
 * cutoff. This test calls the new read-only [PinyinEngine.debugTraceTarget],
 * which replays the same production lattice once and records per-position beam
 * state plus per-(position,key) entry truncation for the expected target text.
 *
 * Outputs:
 *  - build/d05/long-sentence-target-trace.txt
 *  - build/d05/long-sentence-target-trace-detail.tsv (90 data rows)
 *
 * The test also recomputes the previous report's layer classes locally with the
 * exact PinyinD05LongSentencePathLossTest rules (including wide-budget probes)
 * so the cross-tab does not depend on a stale build artifact.
 */
class PinyinD05LongSentenceTargetTraceTest {

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
        val prodHas: Boolean,
        val trace: PinyinEngine.DebugTargetTrace,
        val traceNanos: Long,
        val oldLayer: String,
        val oldDetail: String,
        val crossCheck: String,
    ) {
        val traceNone: Boolean get() = trace.firstLossReason == "NONE"
    }

    @Test
    fun traceNaturalLongTargetPaths() {
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

        cases.forEach { case ->
            engine.search(case.input, limit = SEARCH_LIMIT)
            engine.search(case.input, limit = FULL_LIMIT)
            engine.debugSearch(case.input)
            engine.debugTraceTarget(case.input, case.expectedCjk)
        }

        val diagnoses = cases.map { case -> diagnoseCase(engine, dict, case) }

        val report = buildReport(root, devFile, dictFile, engine, naturalLong, cases, diagnoses)
        val reportFile = File(root, REPORT_PATH)
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report, Charsets.UTF_8)

        val detail = buildDetailTsv(diagnoses)
        val detailFile = File(root, DETAIL_PATH)
        detailFile.parentFile?.mkdirs()
        detailFile.writeText(detail, Charsets.UTF_8)

        val allowedReasons = REASON_ORDER.toSet()
        assertEquals("every case has one trace", diagnoses.size, diagnoses.count { it.trace.buckets.isNotEmpty() })
        assertEquals("every case has a valid reason", diagnoses.size, diagnoses.count { it.trace.firstLossReason in allowedReasons })
        // Pin the measured distribution so a no-op/degenerate trace cannot pass
        // by returning a valid-looking reason for every case.
        val exactReasonCounts = mapOf(
            "NONE" to 24,
            "BEAM_PRUNED" to 43,
            "ENTRY_TRUNCATED" to 22,
            "NO_DICTIONARY_ENTRY" to 1,
            "TRUNCATED_UNKNOWN" to 0,
        )
        assertEquals(
            "reason distribution",
            exactReasonCounts,
            REASON_ORDER.associateWith { reason -> diagnoses.count { it.trace.firstLossReason == reason } },
        )
        assertEquals(
            "NONE trace must contain a full target path",
            0,
            diagnoses.count { it.traceNone && it.trace.fullTargetPaths <= 0 },
        )
        assertEquals(
            "lost trace must not claim full target paths",
            0,
            diagnoses.count { !it.traceNone && it.trace.fullTargetPaths > 0 },
        )
        assertEquals("every trace covers every input position", diagnoses.size, diagnoses.count { it.trace.buckets.size == it.case.input.length })
        assertEquals("every trace target matches expected CJK", diagnoses.size, diagnoses.count { it.trace.target == it.case.expectedCjk })
        assertEquals("no production-hit/trace-lost mismatch", 0, diagnoses.count { it.crossCheck == "MISMATCH:prod-has-trace-lost" })
        assertEquals(
            "trace NONE without final-pool explanation",
            0,
            diagnoses.count { d ->
                !d.prodHas && d.traceNone &&
                    d.trace.fullTargetDistinctTextRank >= 0 &&
                    d.trace.fullTargetDistinctTextRank < FINAL_FULL_BUDGET &&
                    d.trace.fullTargetBestRank >= 0 &&
                    d.trace.fullTargetBestRank < MAX_FULL_CANDIDATES
            },
        )
        assertTrue("report mentions denominator", report.contains("denominator=90"))
        assertTrue("report has metrics", report.contains("[metrics]"))
        assertTrue("report has reason counts", report.contains("[reasonCounts]"))
        assertTrue("report has cross tab", report.contains("[crossTabOldLayer]"))
        assertTrue("report has samples", report.contains("[samplesByReason]"))
        assertTrue("report has api notes", report.contains("[apiNotes]"))

        val detailLines = detail.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        assertEquals("detail header + 90 rows", 91, detailLines.size)
        val detailColumns = detailLines.first().split('\t').size
        assertTrue("every detail row has the same column count", detailLines.all { it.split('\t').size == detailColumns })

        println(buildConsoleSummary(diagnoses, reportFile, detailFile))
    }

    private fun diagnoseCase(engine: PinyinEngine, dict: DictIndex, case: EvalCase): CaseDiagnosis {
        val top8 = engine.search(case.input, limit = SEARCH_LIMIT)
        val search64 = engine.search(case.input, limit = FULL_LIMIT)
        val rank8 = top8.indexOfFirst { candidateMatches(it, case) }
        val rank64 = search64.indexOfFirst { candidateMatches(it, case) }
        val prodHas = rank64 >= 0
        val fullCoverage = search64.any { it.consumedInputLength >= case.coverage }

        val debug = engine.debugSearch(case.input)
        val dictSyllableReachable = dict.syllableReachable(case.input)
        val dictPathWords = if (dictSyllableReachable) dict.sentencePath(case.input, case.expectedCjk) else null
        val prodLatticeHas = debug.latticePaths.any { textMatches(case, it.text) }
        val prodCollectedHas = debug.collected.any { it.consumed >= case.coverage && textMatches(case, it.word) }
        val wide = if (!prodLatticeHas && dictPathWords != null) probeWide(engine, case) else null

        val oldLayer: String
        val oldDetail: String
        when {
            rank8 in 0..4 -> {
                oldLayer = if (rank8 == 0) "ok-top1" else "ok-top5"
                oldDetail = if (rank8 == 0) "top1" else "rank" + (rank8 + 1)
            }
            !fullCoverage -> {
                oldLayer = "truncation-other"
                oldDetail = "no-full-coverage"
            }
            !dictSyllableReachable -> {
                oldLayer = "not-generated"
                oldDetail = "dictionary-syllable-missing"
            }
            dictPathWords == null -> {
                oldLayer = "not-generated"
                oldDetail = "dictionary-word-missing"
            }
            prodCollectedHas -> {
                if (rank64 >= 5) {
                    oldLayer = "collected-ranked-low"
                    oldDetail = if (rank64 >= 8) "rank8-plus" else "rank5-7"
                } else if (rank64 >= 0) {
                    oldLayer = "collected-ranked-low"
                    oldDetail = "final-top5-but-not-search8"
                } else {
                    oldLayer = "generated-not-collected"
                    oldDetail = "final-pool-cap"
                }
            }
            prodLatticeHas -> {
                oldLayer = "generated-not-collected"
                oldDetail = if (debug.latticePaths.indexOfFirst { textMatches(case, it.text) } >= MAX_FULL_CANDIDATES) {
                    "lattice-emission-cap"
                } else {
                    "collect-other"
                }
            }
            wide != null && wide.found -> {
                oldLayer = "only-wide-budget"
                oldDetail = when {
                    wide.beamOnly && wide.entriesOnly -> "beam+entries"
                    wide.beamOnly -> "beam"
                    wide.entriesOnly -> "entries"
                    else -> "combined"
                }
            }
            else -> {
                oldLayer = "not-generated"
                oldDetail = "lattice-wide-not-found"
            }
        }

        val traceStarted = System.nanoTime()
        val trace = engine.debugTraceTarget(case.input, case.expectedCjk)
        val traceNanos = System.nanoTime() - traceStarted

        val crossCheck = when {
            prodHas && trace.traceNone() -> "match:prod-has-trace-none"
            !prodHas && !trace.traceNone() -> "match:prod-missing-trace-lost"
            prodHas -> "MISMATCH:prod-has-trace-lost"
            else -> "trace-none-production-filtered"
        }
        return CaseDiagnosis(
            case = case,
            rank8 = rank8,
            rank64 = rank64,
            prodHas = prodHas,
            trace = trace,
            traceNanos = traceNanos,
            oldLayer = oldLayer,
            oldDetail = oldDetail,
            crossCheck = crossCheck,
        )
    }

    private fun PinyinEngine.DebugTargetTrace.traceNone(): Boolean = firstLossReason == "NONE"

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
        builder.appendLine("# Bitap D05 natural_long target-path trace (development diagnostic; NOT acceptance)")
        builder.appendLine("generatedBy=PinyinD05LongSentenceTargetTraceTest")
        builder.appendLine("corpus=" + relativePath(root, devFile))
        builder.appendLine("corpusSha256=" + sha256(devFile))
        builder.appendLine("dictionary=" + relativePath(root, dictFile))
        builder.appendLine("dictionarySha256=" + sha256(dictFile))
        builder.appendLine("dictionaryEntries=" + engine.entryCount)
        builder.appendLine("selection=category==natural_long && queryLength<=64")
        builder.appendLine("naturalLongTotal=" + naturalLong.size)
        builder.appendLine("overBudgetExcluded=" + (naturalLong.size - cases.size) + " (queryLength 65-70; engine MAX_SEARCH_INPUT_LENGTH=64)")
        builder.appendLine("denominator=" + cases.size)
        builder.appendLine("traceApi=debugTraceTarget(input, targetText) -> DebugTargetTrace (read-only; default search untouched)")
        builder.appendLine("engineTuning=production-default beamSize=64 entriesPerKey=8 partialQuota=16")
        builder.appendLine("rankBase=0 (ranks are 0-based score-descending indices)")
        builder.appendLine("sinkContext=old debugSearch MAX_DEBUG_FULL_PATHS=4000 distinct texts; collection MAX_FULL_CANDIDATES=96; final full budget=48 (limit64 - partialQuota16)")
        builder.appendLine()
        appendMetrics(builder, diagnoses)
        builder.appendLine()
        appendReasonCounts(builder, diagnoses)
        builder.appendLine()
        appendCrossTab(builder, diagnoses)
        builder.appendLine()
        appendSamples(builder, diagnoses)
        builder.appendLine()
        appendApiNotes(builder)
        return builder.toString()
    }

    private fun appendMetrics(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        val n = diagnoses.size
        val latencies = diagnoses.map { it.traceNanos / 1_000_000.0 }.sorted()
        val reasonNone = diagnoses.count { it.traceNone }
        val prodHas = diagnoses.count { it.prodHas }
        val traceNoneProdMissing = diagnoses.count { !it.prodHas && it.traceNone }
        val traceLostProdMissing = diagnoses.count { !it.prodHas && !it.traceNone }
        val mismatches = diagnoses.count { it.crossCheck == "MISMATCH:prod-has-trace-lost" }
        val finalPartial = diagnoses.count { it.trace.notes.any { note -> note.startsWith("partial-target-at-final-position") } }
        val scanCapped = diagnoses.count { it.trace.notes.any { note -> note.startsWith("distinct-text-rank-scan-truncated") } }
        builder.appendLine("[metrics]")
        builder.appendLine("n=" + n)
        builder.appendLine("tracesWithBuckets=" + diagnoses.count { it.trace.buckets.isNotEmpty() } + "/" + n)
        builder.appendLine("reasonNone=" + ratio(reasonNone, n))
        builder.appendLine("prodRank64Has=" + ratio(prodHas, n))
        builder.appendLine("traceNoneAndProdMissing=" + traceNoneProdMissing + " (full target exists in bucket[n]; invisible to search due to collection/final-pool caps)")
        builder.appendLine("traceLostAndProdMissing=" + traceLostProdMissing)
        builder.appendLine("prodHasTraceLostMismatches=" + mismatches)
        builder.appendLine("partialTargetAtFinalPosition=" + finalPartial)
        builder.appendLine("distinctTextRankScanCapped=" + scanCapped)
        builder.appendLine(
            "[latencyJvm] debugTraceTarget p50=" + millis(percentile(latencies, 50)) +
                " p95=" + millis(percentile(latencies, 95)) +
                " p99=" + millis(percentile(latencies, 99)) +
                " max=" + millis(latencies.last()),
        )
        builder.appendLine("note=JVM single-process diagnostic; latency is not Android/device input latency")
    }

    private fun appendReasonCounts(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        builder.appendLine("[reasonCounts] total=" + diagnoses.size)
        builder.appendLine("reason\tcount\tshareAll")
        REASON_ORDER.forEach { reason ->
            val count = diagnoses.count { it.trace.firstLossReason == reason }
            builder.appendLine(reason + "\t" + count + "\t" + pct(count, diagnoses.size) + "%")
        }

        val oldNotGenerated = diagnoses.filter { it.oldLayer == "not-generated" }
        builder.appendLine()
        builder.appendLine("[reasonCounts.oldNotGenerated] oldNotGenerated=" + oldNotGenerated.size)
        builder.appendLine("reason\tcount\tshareOfOldNotGenerated")
        REASON_ORDER.forEach { reason ->
            val count = oldNotGenerated.count { it.trace.firstLossReason == reason }
            builder.appendLine(reason + "\t" + count + "\t" + pct(count, oldNotGenerated.size) + "%")
        }
        builder.appendLine("oldNotGeneratedDetails=" + oldNotGenerated.groupingBy { it.oldDetail }.eachCount().entries.joinToString(",") { it.key + ":" + it.value })

        val oldLatticeWide = diagnoses.filter { it.oldDetail == "lattice-wide-not-found" }
        builder.appendLine()
        builder.appendLine("[reasonCounts.oldLatticeWideNotFound] count=" + oldLatticeWide.size)
        builder.appendLine("reason\tcount\tshare")
        REASON_ORDER.forEach { reason ->
            val count = oldLatticeWide.count { it.trace.firstLossReason == reason }
            builder.appendLine(reason + "\t" + count + "\t" + pct(count, oldLatticeWide.size) + "%")
        }
    }

    private fun appendCrossTab(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        builder.appendLine("[crossTabOldLayer] rows=oldLayer(rows) columns=firstLossReason")
        builder.appendLine("oldLayer\t" + REASON_ORDER.joinToString("\t") + "\ttotal")
        LAYER_ORDER.forEach { layer ->
            val group = diagnoses.filter { it.oldLayer == layer }
            val cells = REASON_ORDER.joinToString("\t") { reason -> group.count { it.trace.firstLossReason == reason }.toString() }
            builder.appendLine(layer + "\t" + cells + "\t" + group.size)
        }
        builder.appendLine("total\t" + REASON_ORDER.joinToString("\t") { reason ->
            diagnoses.count { it.trace.firstLossReason == reason }.toString()
        } + "\t" + diagnoses.size)
    }

    private fun appendSamples(builder: StringBuilder, diagnoses: List<CaseDiagnosis>) {
        builder.appendLine("[samplesByReason]")
        REASON_ORDER.forEach { reason ->
            val group = diagnoses.filter { it.trace.firstLossReason == reason }.sortedBy { it.case.id }
            builder.appendLine("### " + reason + " n=" + group.size)
            if (group.isEmpty()) {
                builder.appendLine("samples: none")
            } else {
                group.take(3).forEach { d -> builder.appendLine(sampleLine(d)) }
                if (group.size < 2) builder.appendLine("samples: only " + group.size + " case in this reason")
            }
        }
        val mismatches = diagnoses.filter { it.crossCheck == "MISMATCH:prod-has-trace-lost" }
        builder.appendLine()
        builder.appendLine("[crossCheck]")
        builder.appendLine("matchProdHasTraceNone=" + diagnoses.count { it.crossCheck == "match:prod-has-trace-none" })
        builder.appendLine("matchProdMissingTraceLost=" + diagnoses.count { it.crossCheck == "match:prod-missing-trace-lost" })
        builder.appendLine("traceNoneProductionFiltered=" + diagnoses.count { it.crossCheck == "trace-none-production-filtered" })
        builder.appendLine("mismatches=" + mismatches.size)
        mismatches.forEach { d -> builder.appendLine("MISMATCH " + sampleLine(d)) }
    }

    private fun sampleLine(d: CaseDiagnosis): String {
        val trace = d.trace
        return "id=" + d.case.id +
            " inputLen=" + d.case.input.length +
            " targetLen=" + trace.targetTextLength +
            " oldLayer=" + d.oldLayer + "/" + d.oldDetail +
            " firstLossPosition=" + trace.firstLossPosition +
            " fullTargetPaths=" + trace.fullTargetPaths +
            " fullTargetRank=" + trace.fullTargetBestRank +
            " distinctTextRank=" + trace.fullTargetDistinctTextRank +
            " prodRank8=" + d.rank8 +
            " prodRank64=" + d.rank64 +
            " notes=" + trace.notes.joinToString(",") +
            " expected=" + d.case.expectedCjk
    }

    private fun appendApiNotes(builder: StringBuilder) {
        builder.appendLine("[apiNotes]")
        builder.appendLine("1) PinyinEngine.debugTraceTarget(input, targetText) replays one continuous query through collectLattice with production defaults (beamSize=64, entriesPerKey=8) and a nullable DebugTargetTraceBuilder; when the builder is null the lattice executes its original path with no target bookkeeping.")
        builder.appendLine("2) PathNode.targetPrefixLen is diagnostic-only: -1 by default, 0 for the root, and extended only when the child word matches target[parentPrefixLen..]. Production search never reads it and never allocates a non-default value.")
        builder.appendLine("3) Each DebugTargetBucket records pathsBefore, targetPathsBefore, targetBestRankBefore, targetBestScoreBefore, effectiveBeam, beamCutoffScore, targetPathsInBeam and targetBestRankInBeam for one position. Ranks are 0-based score-descending indices.")
        builder.appendLine("4) Each DebugTargetKeyProbe records entriesTotal, entriesKept and truncatedTargetExtensions for one (position, key). truncatedTargetExtensions counts target continuations present in entries but dropped by entries.take(effectiveEntries); missingTargetExtensions counts target continuations absent from the dictionary for that exact key.")
        builder.appendLine("5) firstLossReason: NONE when bucket[n] contains a targetPrefixLen==target.length path; BEAM_PRUNED when target nodes exist at a position but none survives in that position's beam and no later beam keeps the target alive; ENTRY_TRUNCATED when the last alive extension dropped a target continuation; NO_DICTIONARY_ENTRY when the last alive extension had no dictionary entry for any target continuation; TRUNCATED_UNKNOWN when the target only reaches a partial prefix at n.")
        builder.appendLine("6) fullTargetPaths counts bucket[n] nodes spelling the whole target; fullTargetBestRank is their first 0-based score rank; fullTargetDistinctTextRank scans distinct bucket[n] texts in score order up to MAX_TARGET_DISTINCT_SCAN=20000 nodes and returns -1 when capped.")
        builder.appendLine("7) This diagnostic is read-only: it does not modify eval/d05/dev/dev.tsv, pinyin_dict.tsv or production tuning defaults.")
        builder.appendLine("8) firstLossReason is a bucket-level existence attribution, not a per-path lineage proof: a shorter target prefix surviving in a later beam can keep firstLossPosition later than the exact position where the longest target prefix was pruned. fullTargetPaths/fullTargetDistinctTextRank and the production cross-check are the primary correctness evidence.")
    }

    private fun buildDetailTsv(diagnoses: List<CaseDiagnosis>): String {
        val builder = StringBuilder()
        builder.appendLine(
            listOf(
                "id", "input", "len", "target", "firstLossPosition", "firstLossReason",
                "fullTargetPaths", "fullTargetRank", "distinctTextRank", "prodRank64",
                "layerFromOldReport", "oldDetail", "prodRank8", "prodHas", "traceNone",
                "crossCheck", "notes",
            ).joinToString("\t"),
        )
        diagnoses.forEach { d ->
            val trace = d.trace
            builder.appendLine(
                listOf(
                    d.case.id,
                    d.case.input,
                    d.case.input.length.toString(),
                    d.case.expectedCjk,
                    trace.firstLossPosition.toString(),
                    trace.firstLossReason,
                    trace.fullTargetPaths.toString(),
                    trace.fullTargetBestRank.toString(),
                    trace.fullTargetDistinctTextRank.toString(),
                    d.rank64.toString(),
                    d.oldLayer,
                    d.oldDetail,
                    d.rank8.toString(),
                    d.prodHas.toString(),
                    d.traceNone.toString(),
                    d.crossCheck,
                    trace.notes.joinToString(","),
                ).joinToString("\t"),
            )
        }
        return builder.toString()
    }

    private fun buildConsoleSummary(diagnoses: List<CaseDiagnosis>, reportFile: File, detailFile: File): String {
        val n = diagnoses.size
        val reasons = REASON_ORDER.joinToString(" ") { reason ->
            reason + "=" + diagnoses.count { it.trace.firstLossReason == reason }
        }
        val oldNotGenerated = diagnoses.filter { it.oldLayer == "not-generated" }
        val oldReasons = REASON_ORDER.joinToString(" ") { reason ->
            reason + "=" + oldNotGenerated.count { it.trace.firstLossReason == reason }
        }
        return "D05 natural_long target trace: n=" + n + " " + reasons +
            "\noldNotGenerated=" + oldNotGenerated.size + " " + oldReasons +
            "\nwrote " + reportFile.absolutePath +
            "\nwrote " + detailFile.absolutePath
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
        private const val MAX_FULL_CANDIDATES = 96
        private const val MAX_DEBUG_FULL_PATHS = 4_000
        private const val MAX_SYLLABLE_LENGTH = 6
        private const val PROD_BEAM = 64
        private const val PROD_ENTRIES = 8
        private const val FINAL_FULL_BUDGET = 48
        private val WIDE_BUDGETS = listOf(128 to 16, 256 to 32)
        private const val DEV_SHA256 = "63983c5f9a490040592d6740e2e747e2b5a574d0a068accb44f25179153110ad"
        private const val REPORT_PATH = "build/d05/long-sentence-target-trace.txt"
        private const val DETAIL_PATH = "build/d05/long-sentence-target-trace-detail.tsv"
        private val REASON_ORDER = listOf(
            "NONE",
            "BEAM_PRUNED",
            "ENTRY_TRUNCATED",
            "NO_DICTIONARY_ENTRY",
            "TRUNCATED_UNKNOWN",
        )
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
