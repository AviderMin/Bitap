package com.bitap.keyboard.core

import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/** Temporary D05 ranking sweep probe; final selected-mechanism test will extend this. */
class PinyinD05RankingAblationTest {

    private data class Case(val id: String, val category: String, val input: String, val expected: List<String>)

    private data class Result(val rank8: Int, val latencyNanos: Long, val oracle: Boolean, val full: Boolean) {
        val top1 get() = rank8 == 0
        val top5 get() = rank8 in 0..4
        val cost get() = if (rank8 in 0..4) rank8 + 1 else 9
    }

    @Test
    fun runRankingAblations() {
        val root = findRoot()
        val cases = loadCases(File(root, "eval/d05/dev/dev.tsv")).filter { it.category.startsWith("natural_") }
        assertTrue("natural cases missing", cases.size >= 600)
        val engine = dictionaryEngine(root)
        val base = PinyinEngine.EngineTuning()
        val variants = linkedMapOf(
            "baseline" to base,
            "wordInsertionCompensation-8" to base.copy(wordInsertionCompensation = 8.0),
            "wordInsertionCompensation-10" to base.copy(wordInsertionCompensation = 10.0),
            "wordInsertionCompensation-14" to base.copy(wordInsertionCompensation = 14.0),
            "wordInsertionCompensation-16" to base.copy(wordInsertionCompensation = 16.0),
            "wordInsertionCompensation-20" to base.copy(wordInsertionCompensation = 20.0),
            "contextWeight-0.6" to base.copy(contextWeight = 0.6),
            "contextWeight-0.9" to base.copy(contextWeight = 0.9),
            "contextWeight-1.6" to base.copy(contextWeight = 1.6),
            "contextWeight-2.0" to base.copy(contextWeight = 2.0),
            "contextWeight-2.4" to base.copy(contextWeight = 2.4),
            "corpusWordNgramWeight-0.15" to base.copy(corpusWordNgramWeight = 0.15),
            "corpusWordNgramWeight-0.25" to base.copy(corpusWordNgramWeight = 0.25),
            "corpusWordNgramWeight-0.40" to base.copy(corpusWordNgramWeight = 0.40),
            "corpusWordNgramWeight-0.45" to base.copy(corpusWordNgramWeight = 0.45),
            "corpusWordNgramWeight-0.5" to base.copy(corpusWordNgramWeight = 0.5),
            "corpusWordNgramWeight-0.55" to base.copy(corpusWordNgramWeight = 0.55),
            "corpusWordNgramWeight-0.60" to base.copy(corpusWordNgramWeight = 0.60),
            "corpusWordNgramWeight-0.65" to base.copy(corpusWordNgramWeight = 0.65),
            "corpusWordNgramWeight-0.7" to base.copy(corpusWordNgramWeight = 0.7),
            "dictionaryWordNgramWeight-0.2" to base.copy(dictionaryWordNgramWeight = 0.2),
            "dictionaryWordNgramWeight-0.6" to base.copy(dictionaryWordNgramWeight = 0.6),
            "dictionaryWordNgramWeight-0.8" to base.copy(dictionaryWordNgramWeight = 0.8),
            "sentenceCorpusWeight-0.05" to base.copy(sentenceCorpusWeight = 0.05),
            "sentenceCorpusWeight-0.10" to base.copy(sentenceCorpusWeight = 0.10),
            "sentenceCorpusWeight-0.20" to base.copy(sentenceCorpusWeight = 0.20),
            "sentenceDictionaryWeight-0.05" to base.copy(sentenceDictionaryWeight = 0.05),
            "sentenceDictionaryWeight-0.10" to base.copy(sentenceDictionaryWeight = 0.10),
        )
        val builder = StringBuilder()
        builder.appendLine("variant	n	top1	top5	rankCost	oracle	fullCoverage	p50	p95")
        variants.forEach { (name, tuning) ->
            val variantEngine = engine.withTuning(tuning)
            val rows = cases.map { evaluate(variantEngine, it) }
            val values = rows.map { it.latencyNanos / 1_000_000.0 }.sorted()
            fun pct(value: Int): String = String.format(Locale.ROOT, "%.1f", value * 100.0 / rows.size)
            builder.appendLine(
                name + "\t" + rows.size + "\t" +
                    rows.count { it.top1 } + "/" + rows.size + "(" + pct(rows.count { it.top1 }) + ")\t" +
                    rows.count { it.top5 } + "/" + rows.size + "(" + pct(rows.count { it.top5 }) + ")\t" +
                    String.format(Locale.ROOT, "%.3f", rows.sumOf { it.cost }.toDouble() / rows.size) + "\t" +
                    rows.count { it.oracle } + "/" + rows.size + "\t" +
                    rows.count { it.full } + "/" + rows.size + "\t" +
                    String.format(Locale.ROOT, "%.3f", values[(values.size - 1) * 50 / 100]) + "\t" +
                    String.format(Locale.ROOT, "%.3f", values[(values.size - 1) * 95 / 100]),
            )
        }
        val file = File(root, "build/d05/ranking-ablation.txt")
        file.parentFile?.mkdirs()
        file.writeText(builder.toString(), Charsets.UTF_8)
        println(builder)
    }

    @Test
    fun evaluateSelectedCorpusWeight() {
        val root = findRoot()
        val engine = dictionaryEngine(root)
        val base = PinyinEngine.EngineTuning()
        val after = base.copy(corpusWordNgramWeight = 0.45)
        val all = loadCases(File(root, "eval/d05/dev/dev.tsv"))
        val cats = listOf("natural_short", "natural_medium", "natural_long", "typo", "shorthand", "mixed_pinyin")
        val out = StringBuilder()
        cats.forEach { category ->
            val cases = all.filter { it.category == category }
            listOf("baseline" to base, "after" to after).forEach { (name, tuning) ->
                val rows = cases.map { evaluate(engine.withTuning(tuning), it) }
                out.appendLine(
                    category + " " + name + " n=" + rows.size +
                        " top1=" + rows.count { it.top1 } + "/" + rows.size +
                        " top5=" + rows.count { it.top5 } + "/" + rows.size +
                        " rankCost=" + String.format(Locale.ROOT, "%.3f", rows.sumOf { it.cost }.toDouble() / rows.size) +
                        " oracle=" + rows.count { it.oracle } + "/" + rows.size +
                        " full=" + rows.count { it.full } + "/" + rows.size,
                )
            }
        }
        listOf("eval/pinyin_cases_dev.tsv" to "v1dev", "eval/pinyin_cases_holdout.tsv" to "v1holdout").forEach { (path,label) ->
            val cases = loadV1Cases(File(root, path)).filter { it.category != "mixed_en" && !(it.category == "number_punct" && it.input.any { c -> c.isDigit() }) }
            listOf("baseline" to base, "after" to after).forEach { (name,tuning) ->
                val rows=cases.map { evaluate(engine.withTuning(tuning),it) }
                out.appendLine(label+" "+name+" n="+rows.size+" top1="+rows.count{it.top1}+"/"+rows.size+" top5="+rows.count{it.top5}+"/"+rows.size+" rankCost="+String.format(Locale.ROOT,"%.3f",rows.sumOf{it.cost}.toDouble()/rows.size)+" oracle="+rows.count{it.oracle}+"/"+rows.size)
            }
        }
        val file=File(root,"build/d05/ranking-selected.txt"); file.writeText(out.toString(),Charsets.UTF_8); println(out)
    }

    private fun loadV1Cases(file: File): List<Case> = file.useLines { lines ->
        lines.mapNotNull { raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val p = line.split('\t')
            if (p.size < 4) return@mapNotNull null
            Case(p[0], p[1], p[2], p[3].split('|').map { it.trim() }.filter { it.isNotEmpty() })
        }.toList()
    }

    private fun evaluate(engine: PinyinEngine, case: Case): Result {
        val started = System.nanoTime()
        val top8 = engine.search(case.input, limit = 8)
        val latency = System.nanoTime() - started
        val rank = top8.indexOfFirst { matches(it, case) }
        val pool = engine.search(case.input, limit = 64)
        val coverage = requiredCoverage(case.input)
        return Result(rank, latency, pool.any { matches(it, case) }, pool.any { it.consumedInputLength >= coverage })
    }

    private fun matches(candidate: Candidate, case: Case): Boolean {
        val text = normalize(candidate.word)
        return text.isNotEmpty() && candidate.consumedInputLength >= requiredCoverage(case.input) &&
            case.expected.any { normalize(it) == text }
    }

    private fun requiredCoverage(input: String): Int = input.count { it.isLetterOrDigit() || it == '\'' }

    private fun normalize(text: String): String = text.filter { it.isLetterOrDigit() || isCjk(it) }

    private fun isCjk(char: Char): Boolean = char.code in 0x3400..0x4DBF || char.code in 0x4E00..0x9FFF

    private fun dictionaryEngine(root: File): PinyinEngine =
        File(root, "app/src/main/assets/pinyin_dict.tsv").useLines { PinyinEngine.fromLines(it) }.also { it.setLearningEnabled(false) }

    private fun loadCases(file: File): List<Case> = file.useLines { lines ->
        lines.mapNotNull { raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val p = line.split('\t')
            if (p.size != 6) return@mapNotNull null
            Case(p[0], p[1], p[2], p[3].split('|').map { it.trim() }.filter { it.isNotEmpty() })
        }.toList()
    }

    private fun findRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "eval/d05/dev/dev.tsv").isFile && File(dir, "app/src/main/assets/pinyin_dict.tsv").isFile) return dir
            dir = dir.parentFile
        }
        error("repo root not found")
    }
}
