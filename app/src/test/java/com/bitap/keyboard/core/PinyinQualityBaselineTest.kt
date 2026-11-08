package com.bitap.keyboard.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T42 independent quality baseline: fixed natural-word composition cases, not
 * derived from the engine's own segmentation or dictionary ordering.
 *
 * The cases intentionally exercise general word combination rather than only
 * exact dictionary lookups. Metrics are written to build/pinyin-quality-baseline.txt
 * so the pre/post improvement record can be reviewed without depending on
 * Gradle's console formatting.
 */
class PinyinQualityBaselineTest {

    private data class Case(val input: String, val expected: String, val category: String)

    private fun cases(): List<Case> {
        val subjects = listOf(
            Triple("wo", "我", "subject"),
            Triple("ni", "你", "subject"),
            Triple("ta", "他", "subject"),
            Triple("women", "我们", "subject"),
            Triple("tamen", "他们", "subject"),
            Triple("jintian", "今天", "time"),
            Triple("mingtian", "明天", "time"),
            Triple("zuotian", "昨天", "time"),
            Triple("xiawu", "下午", "time"),
            Triple("zaoshang", "早上", "time"),
        )
        val predicates = listOf(
            Triple("qugongsi", "去公司", "action"),
            Triple("xiangchifan", "想吃饭", "action"),
            Triple("yaoshuijiao", "要睡觉", "action"),
            Triple("kandianying", "看电影", "action"),
            Triple("xuezhongwen", "学中文", "action"),
            Triple("huijia", "回家", "action"),
            Triple("gongzuo", "工作", "action"),
            Triple("xiuxi", "休息", "action"),
            Triple("maidongxi", "买东西", "action"),
            Triple("dadianhua", "打电话", "action"),
        )
        val generated = subjects.flatMap { subject ->
            predicates.map { predicate ->
                Case(subject.first + predicate.first, subject.second + predicate.second, "compose")
            }
        }
        val curated = listOf(
            Case("mingtianshangwushidianjian", "明天上午十点见", "sentence"),
            Case("mingtianshangwushijian", "明天上午时间", "sentence"),
            Case("wojintianxiangchifan", "我今天想吃饭", "sentence"),
            Case("womenyiqiqugongsi", "我们一起去公司", "sentence"),
            Case("mingtianjian", "明天见", "sentence"),
            Case("jintiantianqihenhao", "今天天气很好", "sentence"),
            Case("woxiangchifan", "我想吃饭", "sentence"),
            Case("woaini", "我爱你", "word"),
            Case("zaijian", "再见", "word"),
            Case("zhongguoren", "中国人", "word"),
            Case("tamenxiawuqukandianying", "他们下午去看电影", "sentence"),
            Case("womenshangwuqugongsi", "我们上午去公司", "sentence"),
        )
        return generated + curated
    }

    @Test
    fun baselineHasAtLeastHundredIndependentCasesAndReportsMetrics() {
        val dictionary = File("src/main/assets/pinyin_dict.tsv")
        assumeTrue("bundled dictionary not found", dictionary.isFile)
        val engine = dictionary.useLines { PinyinEngine.fromLines(it) }
        val cases = cases()
        assertTrue("baseline needs >=100 cases", cases.size >= 100)

        var top1 = 0
        var top5 = 0
        var rankCost = 0
        val failures = mutableListOf<String>()
        val failureCategories = LinkedHashMap<String, Int>()
        val byCategory = LinkedHashMap<String, IntArray>()
        val latencies = ArrayList<Long>(cases.size)
        cases.forEach { case ->
            val started = System.nanoTime()
            val candidates = engine.search(case.input, limit = 8)
            latencies += System.nanoTime() - started
            val rank = candidates.indexOfFirst { it.word == case.expected }
            val hit1 = rank == 0
            val hit5 = rank in 0..4
            if (hit1) top1++
            if (hit5) top5++
            rankCost += if (rank >= 0) rank + 1 else 9
            val category = byCategory.getOrPut(case.category) { IntArray(2) }
            if (hit1) category[0]++
            category[1]++
            if (!hit5) {
                failures += case.input + " -> " + candidates.take(5).map { it.word }
                failureCategories[case.category] = (failureCategories[case.category] ?: 0) + 1
            }
        }
        latencies.sort()
        val p50 = latencies[(latencies.size - 1) * 50 / 100]
        val p95 = latencies[(latencies.size - 1) * 95 / 100]
        val report = buildString {
            appendLine("cases=" + cases.size)
            appendLine("top1=" + top1 + "/" + cases.size + " (" + (top1 * 100 / cases.size) + "%)")
            appendLine("top5=" + top5 + "/" + cases.size + " (" + (top5 * 100 / cases.size) + "%)")
            appendLine("rankCost=" + String.format("%.2f", rankCost.toDouble() / cases.size))
            appendLine("p50Us=" + p50 / 1_000 + " p95Us=" + p95 / 1_000)
            byCategory.forEach { (category, counts) ->
                appendLine(category + ": top1=" + counts[0] + "/" + counts[1])
            }
            appendLine("failureCategories=" + if (failureCategories.isEmpty()) "none" else failureCategories.toString())
            appendLine("failures=" + failures.joinToString(" | "))
        }
        File("build/pinyin-quality-baseline.txt").writeText(report)
        println(report)

        // Floor keeps the baseline red-line meaningful while allowing known
        // hard cases to be tracked explicitly in failures.
        assertTrue("top5 quality floor too low: " + top5 + "/" + cases.size, top5 * 100 >= cases.size * 95)
        assertTrue("top1 quality floor too low: " + top1 + "/" + cases.size, top1 * 100 >= cases.size * 90)
    }
}
