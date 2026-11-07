package com.bitap.keyboard.ai

import com.bitap.keyboard.ai.internal.AiCandidateParser
import com.bitap.keyboard.ai.internal.AiLexiconOutcome
import com.bitap.keyboard.ai.internal.AiLexiconPromptBuilder
import com.bitap.keyboard.ai.internal.AiLexiconQualityGate
import com.bitap.keyboard.ai.internal.AiPinyinDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLexiconTest {

    private val dictionary = AiPinyinDictionary.fromMap(
        mapOf(
            "你好" to setOf("nihao"),
            "你号" to setOf("nihao"),
            "你豪" to setOf("nihao"),
            "苹果" to setOf("pingguo"),
            "天气" to setOf("tianqi"),
            "西安" to setOf("xian"),
            "长" to setOf("zhang", "chang"),
        ),
    )

    @Test
    fun promptKeepsPinyinAndContext() {
        val prompt = AiLexiconPromptBuilder.build("nihao", "我想说")
        assertTrue(prompt.system.contains("中文候选词"))
        assertTrue(prompt.system.contains("nihao"))
        assertTrue(prompt.user.contains("拼音：nihao"))
        assertTrue(prompt.user.contains("上文：我想说"))
    }

    @Test
    fun promptBoundsPinyinAndContext() {
        assertEquals(48, AiLexiconPromptBuilder.normalizePinyin("a".repeat(100)).length)
        assertEquals(160, AiLexiconPromptBuilder.normalizeContext("b".repeat(300)).length)
        assertEquals("（无）", AiLexiconPromptBuilder.build("nihao", "   ").user.substringAfter("上文：").substringBefore("\n"))
    }

    @Test
    fun lexiconSamplingIsGreedyAndShort() {
        assertEquals(0.0, AiLexiconPromptBuilder.TEMPERATURE, 0.0)
        assertEquals(1, AiLexiconPromptBuilder.TOP_K)
        assertEquals(1.0, AiLexiconPromptBuilder.TOP_P, 0.0)
        assertEquals(48, AiLexiconPromptBuilder.MAX_OUTPUT_TOKENS)
    }

    @Test
    fun parserKeepsCleanChineseLines() {
        val raw = "你好\n你号\n你豪"
        assertEquals(listOf("你好", "你号", "你豪"), AiCandidateParser.parse(raw))
    }

    @Test
    fun parserStripsNumberingBulletsAndQuotes() {
        val raw = "1. 你好\n- “你号”\n3、你豪"
        assertEquals(listOf("你好", "你号", "你豪"), AiCandidateParser.parse(raw))
    }

    @Test
    fun parserRejectsPinyinEchoAndExplanations() {
        val raw = "nihao\n拼音：nihao\n候选：\n以下是候选：\n你好"
        assertEquals(listOf("你好"), AiCandidateParser.parse(raw))
    }

    @Test
    fun parserDeduplicatesAndCapsAtFive() {
        val raw = "你好\n你好\n你号\n你豪\n泥好\n你耗\n你昊"
        assertEquals(listOf("你好", "你号", "你豪", "泥好", "你耗"), AiCandidateParser.parse(raw))
        assertEquals(3, AiCandidateParser.parse("你好 你号 你豪").size)
    }

    @Test
    fun parserRejectsEmptyAndNonChinese() {
        assertEquals(emptyList<String>(), AiCandidateParser.parse("\n  \n"))
        assertEquals(emptyList<String>(), AiCandidateParser.parse("hello\n123\nabc"))
    }

    @Test
    fun parserRemovesThinkBlockBeforeParsing() {
        val raw = "<think>思考</think>你好"
        assertEquals(listOf("你好"), AiCandidateParser.parse(raw))
    }

    @Test
    fun parserForPinyinRejectsPinyinEcho() {
        assertEquals(emptyList<String>(), AiCandidateParser.parseForPinyin("nihao", "nihao", dictionary))
        assertEquals(listOf("你好"), AiCandidateParser.parseForPinyin("你好\nnihao", "nihao", dictionary))
    }

    @Test
    fun parserForPinyinRejectsUnrelatedChinese() {
        // 天气 has pronunciation tianqi, not nihao: generic dictionary check.
        assertEquals(emptyList<String>(), AiCandidateParser.parseForPinyin("天气", "nihao", dictionary))
        assertEquals(listOf("天气"), AiCandidateParser.parseForPinyin("天气", "tianqi", dictionary))
    }

    @Test
    fun parserForPinyinRejectsExampleLeakFromOtherQuery() {
        assertEquals(emptyList<String>(), AiCandidateParser.parseForPinyin("苹果", "nihao", dictionary))
        assertEquals(listOf("苹果"), AiCandidateParser.parseForPinyin("苹果", "pingguo", dictionary))
    }

    @Test
    fun parserForPinyinAllowsPrefixCompletion() {
        assertEquals(listOf("你好"), AiCandidateParser.parseForPinyin("你好", "nih", dictionary))
    }

    @Test
    fun parserForPinyinAllowsPolyphonicReadings() {
        assertEquals(listOf("长"), AiCandidateParser.parseForPinyin("长", "chang", dictionary))
        assertEquals(listOf("长"), AiCandidateParser.parseForPinyin("长", "zhang", dictionary))
    }

    @Test
    fun parserForPinyinHandlesApostropheSeparator() {
        assertEquals(listOf("西安"), AiCandidateParser.parseForPinyin("西安", "xi'an", dictionary))
    }

    @Test
    fun qualityGateReturnsCandidatesForGoodOutput() {
        val outcome = AiLexiconQualityGate.evaluate("你好\n你号", "nihao", dictionary)
        assertEquals(AiLexiconOutcome.Candidates(listOf("你好", "你号")), outcome)
    }

    @Test
    fun qualityGateFallsBackForEchoOrUnrelatedOutput() {
        assertEquals(
            AiLexiconOutcome.Fallback(AiLexiconQualityGate.FALLBACK_MESSAGE),
            AiLexiconQualityGate.evaluate("nihao", "nihao", dictionary),
        )
        assertEquals(
            AiLexiconOutcome.Fallback(AiLexiconQualityGate.FALLBACK_MESSAGE),
            AiLexiconQualityGate.evaluate("苹果", "nihao", dictionary),
        )
        assertEquals(
            AiLexiconOutcome.Fallback(AiLexiconQualityGate.FALLBACK_MESSAGE),
            AiLexiconQualityGate.evaluate("天气", "nihao", dictionary),
        )
    }

    @Test
    fun qualityGateFallsBackForUnknownDictionary() {
        assertEquals(
            AiLexiconOutcome.Fallback(AiLexiconQualityGate.FALLBACK_MESSAGE),
            AiLexiconQualityGate.evaluate("你好", "nihao", AiPinyinDictionary.EMPTY),
        )
    }

    @Test
    fun formatJoinsCandidatesWithNewLines() {
        assertEquals("你好\n你号", AiCandidateParser.format(listOf("你好", "你号")))
    }
}
