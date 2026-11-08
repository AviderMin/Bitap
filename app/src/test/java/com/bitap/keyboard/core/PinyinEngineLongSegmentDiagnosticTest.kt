package com.bitap.keyboard.core

import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Temporary analysis probe for long-segment decoding.
 *
 * The report separates four failure classes per case:
 *  1-not-generated       the correct word path never appears in the lattice,
 *  2-beam/entry-pruning  it appears only when the lattice beam / entries budget grows,
 *  3-scoring             the path is collected but ranks below the top five,
 *  4-dedupe/limit        the path exists in the lattice but is dropped by caps.
 */
class PinyinEngineLongSegmentDiagnosticTest {

    private data class Case(
        val id: String,
        val category: String,
        val input: String,
        val expected: List<String>,
        val note: String,
    )

    private fun loadCases(): List<Case> {
        val file = File("../eval/pinyin_cases_dev.tsv")
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { it.split('\t') }
            .filter { it.size >= 5 && (it[1] == "long_segment" || it[0] == "dev-work_comm-28") }
            .map { Case(it[0], it[1], it[2], it[3].split('|'), it[4]) }
    }

    private fun loadAllDevCases(): List<Case> {
        val file = File("../eval/pinyin_cases_dev.tsv")
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { it.split('\t') }
            .filter { it.size >= 5 }
            .map { Case(it[0], it[1], it[2], it[3].split('|'), it[4]) }
    }

    private fun isPrefixCase(case: Case): Boolean = case.note.contains("分段选词")

    private fun matchesAny(candidate: String, expected: List<String>): Boolean =
        expected.any { it == candidate }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun hasCorrectPath(debug: PinyinEngine.DebugSearchResult, case: Case): Boolean =
        if (isPrefixCase(case)) {
            debug.partialPaths.any { matchesAny(it.text, case.expected) }
        } else {
            debug.latticePaths.any { matchesAny(it.text, case.expected) }
        }

    private fun hasCorrectCollected(debug: PinyinEngine.DebugSearchResult, case: Case): Boolean =
        debug.collected.any { matchesAny(it.word, case.expected) }

    private fun classify(
        case: Case,
        finalRank: Int,
        debug: PinyinEngine.DebugSearchResult,
        wide: PinyinEngine.DebugSearchResult,
    ): String {
        val final64Rank = debug.finalCandidates.indexOfFirst { matchesAny(it.word, case.expected) }
        return when {
            finalRank == 0 -> "0-ok"
            finalRank in 1..4 -> "0b-top5-scoring"
            final64Rank >= 0 -> "3-scoring"
            hasCorrectCollected(debug, case) -> "4-dedupe/limit"
            hasCorrectPath(debug, case) -> "4-dedupe/limit"
            hasCorrectPath(wide, case) || hasCorrectCollected(wide, case) -> "2-beam/entry-pruning"
            else -> "1-not-generated"
        }
    }

    private fun rankOf(candidates: List<Candidate>, case: Case): Int =
        candidates.indexOfFirst { matchesAny(it.word, case.expected) }

    @Test
    fun longSegmentDiagnostics() {
        val dict = File("src/main/assets/pinyin_dict.tsv")
        assumeTrue("dictionary missing", dict.isFile)
        val cases = loadCases()
        assumeTrue("dev corpus missing", cases.isNotEmpty())
        val engine = dict.useLines { PinyinEngine.fromLines(it) }
        // Explicit legacy budget/scoring to describe the pre-change decoder.
        val legacyTuning = PinyinEngine.EngineTuning(
            beamSize = 32,
            entriesPerKey = 4,
            dictionaryWordNgramWeight = 0.0,
            partialCoverageWeight = 0.0,
            partialEarlySlotCount = 0,
        )
        val legacyEngine = engine.withTuning(legacyTuning)
        val report = StringBuilder()
        val detail = StringBuilder()
        var baseTop1 = 0
        var baseTop5 = 0
        var afterTop1 = 0
        var afterTop5 = 0
        var baseRankCost = 0
        var afterRankCost = 0
        var legacyRecall = 0
        var prodRecall = 0
        var wideRecall = 0
        var legacyOracle = 0
        var prodOracle = 0
        var wideOracle = 0
        val baseCategories = LinkedHashMap<String, Int>()
        val afterCategories = LinkedHashMap<String, Int>()

        cases.forEach { case ->
            val legacy = legacyEngine.debugSearch(case.input)
            val prod = engine.debugSearch(case.input)
            val wide = engine.debugSearch(case.input, beamSize = 192, entriesPerKey = 16)

            val baseCandidates = legacyEngine.search(case.input, limit = 8)
            val afterCandidates = engine.search(case.input, limit = 8)
            val baseRank = rankOf(baseCandidates, case)
            val afterRank = rankOf(afterCandidates, case)
            if (baseRank == 0) baseTop1++
            if (baseRank in 0..4) baseTop5++
            if (afterRank == 0) afterTop1++
            if (afterRank in 0..4) afterTop5++
            baseRankCost += if (baseRank >= 0) baseRank + 1 else 9
            afterRankCost += if (afterRank >= 0) afterRank + 1 else 9

            val legacyHas = hasCorrectPath(legacy, case)
            val prodHas = hasCorrectPath(prod, case)
            val wideHas = hasCorrectPath(wide, case)
            if (legacyHas || hasCorrectCollected(legacy, case)) legacyRecall++
            if (prodHas || hasCorrectCollected(prod, case)) prodRecall++
            if (wideHas || hasCorrectCollected(wide, case)) wideRecall++
            if (hasCorrectCollected(legacy, case)) legacyOracle++
            if (hasCorrectCollected(prod, case)) prodOracle++
            if (hasCorrectCollected(wide, case)) wideOracle++

            val baseCategory = classify(case, baseRank, legacy, wide)
            val afterCategory = classify(case, afterRank, prod, wide)
            baseCategories[baseCategory] = (baseCategories[baseCategory] ?: 0) + 1
            afterCategories[afterCategory] = (afterCategories[afterCategory] ?: 0) + 1

            val expectedProd = prod.collected
                .filter { matchesAny(it.word, case.expected) }
                .maxByOrNull { it.score }
            val expectedPath = if (isPrefixCase(case)) {
                prod.partialPaths.filter { matchesAny(it.text, case.expected) }.maxByOrNull { it.score }
            } else {
                prod.latticePaths.filter { matchesAny(it.text, case.expected) }.maxByOrNull { it.score }
            }
            val letterCount = case.input.count { it in 'a'..'z' }
            val bestFull = prod.collected
                .filter { it.consumed >= letterCount }
                .maxByOrNull { it.score }
            val bestPartial = prod.collected
                .filter { it.consumed < letterCount }
                .maxByOrNull { it.score }

            report.appendLine(
                listOf(
                    case.id,
                    "baseRank=" + baseRank,
                    "afterRank=" + afterRank,
                    "baseTop1=" + (baseRank == 0),
                    "afterTop1=" + (afterRank == 0),
                    "baseCategory=" + baseCategory,
                    "afterCategory=" + afterCategory,
                    "legacyLattice=" + hasCorrectPath(legacy, case),
                    "prodLattice=" + prodHas,
                    "wideLattice=" + wideHas,
                    "legacyCollected=" + hasCorrectCollected(legacy, case),
                    "prodCollected=" + hasCorrectCollected(prod, case),
                    "wideCollected=" + hasCorrectCollected(wide, case),
                    "expectedScore=" + (expectedProd?.score?.let { format(it) } ?: "n/a"),
                    "expectedPathScore=" + (expectedPath?.score?.let { format(it) } ?: "n/a"),
                    "bestFull=" + (bestFull?.let { it.word + "@" + format(it.score) } ?: "n/a"),
                    "bestPartial=" + (bestPartial?.let { it.word + "@" + format(it.score) } ?: "n/a"),
                    "expected=" + case.expected.joinToString("|"),
                    "baseGot=" + baseCandidates.take(5).joinToString("|") { it.word },
                    "afterGot=" + afterCandidates.take(5).joinToString("|") { it.word },
                ).joinToString("\t"),
            )

            detail.appendLine("### " + case.id + " prefix=" + isPrefixCase(case) +
                " expected=" + case.expected.joinToString("|"))
            detail.appendLine("counts full=" + prod.latticePaths.size + " partial=" + prod.partialPaths.size)
            if (expectedPath != null) {
                detail.appendLine("expectedPath score=" + format(expectedPath.score) +
                    " segments=" + expectedPath.segments.joinToString("/"))
            }
            detail.appendLine("top full paths:")
            prod.latticePaths.take(10).forEach { path ->
                detail.appendLine("  " + format(path.score) + "\t" +
                    path.segments.joinToString("/") + "\t" + path.text)
            }
            if (!isPrefixCase(case) && !prodHas) {
                detail.appendLine("wide top full paths:")
                wide.latticePaths.take(12).forEach { path ->
                    detail.appendLine("  W " + format(path.score) + "\t" +
                        path.segments.joinToString("/") + "\t" + path.text)
                }
            }
            if (isPrefixCase(case)) {
                detail.appendLine("top partial paths:")
                prod.partialPaths.sortedByDescending { it.score }.take(15).forEach { path ->
                    detail.appendLine("  pos=" + path.consumed + " " + format(path.score) + "\t" +
                        path.segments.joinToString("/") + "\t" + path.text)
                }
            }
            detail.appendLine("after top5:")
            afterCandidates.take(5).forEach { candidate ->
                detail.appendLine("  consumed=" + candidate.consumedInputLength + " text=" + candidate.word)
            }
            detail.appendLine()
        }

        report.appendLine(
            "SUMMARY cases=" + cases.size +
                " baseTop1=" + baseTop1 + "/" + cases.size +
                " baseTop5=" + baseTop5 + "/" + cases.size +
                " baseRankCost=" + baseRankCost +
                " afterTop1=" + afterTop1 + "/" + cases.size +
                " afterTop5=" + afterTop5 + "/" + cases.size +
                " afterRankCost=" + afterRankCost +
                " legacyRecall=" + legacyRecall + "/" + cases.size +
                " prodRecall=" + prodRecall + "/" + cases.size +
                " wideRecall=" + wideRecall + "/" + cases.size +
                " legacyOracle=" + legacyOracle + "/" + cases.size +
                " prodOracle=" + prodOracle + "/" + cases.size +
                " wideOracle=" + wideOracle + "/" + cases.size +
                " baseCategories=" + baseCategories +
                " afterCategories=" + afterCategories,
        )
        File("build/long-segment-diagnostic.txt").writeText(report.toString())
        File("build/long-segment-detail.txt").writeText(detail.toString())
        assertTrue("cases", cases.size >= 8)
    }

    @Test
    fun memoryFootprint() {
        val dict = File("src/main/assets/pinyin_dict.tsv")
        assumeTrue("dictionary missing", dict.isFile)
        val runtime = Runtime.getRuntime()
        System.gc()
        Thread.sleep(200)
        val before = runtime.totalMemory() - runtime.freeMemory()
        val engine = dict.useLines { PinyinEngine.fromLines(it) }
        // Keep the engine reachable and force the index through one query.
        val probe = engine.search("mingtian", limit = 4)
        System.gc()
        Thread.sleep(200)
        val after = runtime.totalMemory() - runtime.freeMemory()
        val report = "dictionarySize=" + dict.length() +
            " entries=" + engine.entryCount +
            " indexHeapDeltaBytes=" + (after - before) +
            " probe=" + probe.joinToString("|") { it.word }
        File("build/long-segment-memory.txt").writeText(report)
        assertTrue("engine ready", engine.isReady)
    }

    private data class Metrics(
        val top1: Int,
        val top5: Int,
        val found8: Int,
        val rankCost: Int,
        val ranks: List<Int>,
        val latencies: List<Long>,
    )

    private fun measure(engine: PinyinEngine, cases: List<Case>): Metrics {
        var top1 = 0
        var top5 = 0
        var found = 0
        var rankCostTotal = 0
        val ranks = ArrayList<Int>(cases.size)
        val latencies = ArrayList<Long>(cases.size)
        cases.forEach { case ->
            val started = System.nanoTime()
            val result = engine.search(case.input, limit = 8)
            latencies += System.nanoTime() - started
            val words = result.map { it.word }
            val rank = words.indexOfFirst { word -> case.expected.any { it == word } }
            ranks += rank
            if (rank == 0) top1++
            if (rank in 0..4) top5++
            if (rank >= 0) found++
            rankCostTotal += if (rank >= 0) rank + 1 else 9
        }
        return Metrics(top1, top5, found, rankCostTotal, ranks, latencies)
    }

    private fun percentile(values: List<Long>, percent: Int): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * percent / 100).coerceIn(0, sorted.lastIndex)]
    }

    @Test
    fun tuningAblation() {
        val dict = File("src/main/assets/pinyin_dict.tsv")
        assumeTrue("dictionary missing", dict.isFile)
        val cases = loadCases()
        val allDev = loadAllDevCases()
        assumeTrue("dev corpus missing", allDev.isNotEmpty())
        val base = dict.useLines { PinyinEngine.fromLines(it) }
        val longCases = allDev.filter { it.input.length >= 40 }
        val normalCases = allDev.filter { it.input.length < 40 }

        fun tuning(
            beam: Int = 64,
            entries: Int = 8,
            dictW: Double = 0.4,
            coverage: Double = 4.0,
            early: Int = 1,
            target: Double = 0.36,
            longWord: Double = 0.0,
            sentenceCorpus: Double = 0.0,
            sentenceDict: Double = 0.0,
            dedupe: Boolean = false,
        ) = PinyinEngine.EngineTuning(
            beamSize = beam,
            entriesPerKey = entries,
            dictionaryWordNgramWeight = dictW,
            partialCoverageWeight = coverage,
            partialEarlySlotCount = early,
            partialCoverageTarget = target,
            longWordDictionaryWeight = longWord,
            sentenceCorpusWeight = sentenceCorpus,
            sentenceDictionaryWeight = sentenceDict,
            dedupeBeamByLastWord = dedupe,
        )

        val variants = listOf(
            "legacy-32-4" to tuning(beam = 32, entries = 4, dictW = 0.0, coverage = 0.0, early = 0),
            "budgetOnly-64-8" to tuning(dictW = 0.0, coverage = 0.0, early = 0),
            "prod-combo1e1" to PinyinEngine.EngineTuning(),
            "sentenceCorpus0.2" to tuning(sentenceCorpus = 0.2),
            "sentenceDict0.2" to tuning(sentenceDict = 0.2),
            "dictW0.2" to tuning(dictW = 0.2),
            "dictW0.6" to tuning(dictW = 0.6),
            "coverageOff" to tuning(coverage = 0.0, early = 0),
            "earlySlotOff" to tuning(early = 0),
            "dedupeOn" to tuning(dedupe = true),
        )
        // Warm every variant so JIT state is comparable before the measured pass.
        variants.forEach { (_, variant) ->
            val engine = base.withTuning(variant)
            (cases.take(3) + allDev.take(10)).forEach { engine.search(it.input, limit = 8) }
        }
        val report = StringBuilder()
        variants.forEach { (name, variant) ->
            val engine = base.withTuning(variant)
            val metrics = measure(engine, cases)
            val longMetrics = measure(engine, longCases)
            val normalMetrics = measure(engine, normalCases)
            var maxCollected = 0
            var maxLattice = 0
            cases.forEach { case ->
                val result = engine.debugSearch(case.input)
                maxCollected = maxOf(maxCollected, result.collected.size)
                maxLattice = maxOf(maxLattice, result.latticeFullTexts.size)
            }
            report.appendLine(
                name +
                    " top1=" + metrics.top1 + "/" + cases.size +
                    " top5=" + metrics.top5 + "/" + cases.size +
                    " found8=" + metrics.found8 + "/" + cases.size +
                    " rankCost=" + String.format(Locale.ROOT, "%.2f", metrics.rankCost.toDouble() / cases.size) +
                    " ranks=" + metrics.ranks.joinToString(",") +
                    " longP50us=" + percentile(longMetrics.latencies, 50) / 1_000 +
                    " longP95us=" + percentile(longMetrics.latencies, 95) / 1_000 +
                    " normalP50us=" + percentile(normalMetrics.latencies, 50) / 1_000 +
                    " normalP95us=" + percentile(normalMetrics.latencies, 95) / 1_000 +
                    " maxCollected=" + maxCollected +
                    " maxLattice=" + maxLattice,
            )
        }
        File("build/long-segment-tuning-ablation.txt").writeText(report.toString())
        assertTrue("ablated", variants.size >= 3)
    }
}
