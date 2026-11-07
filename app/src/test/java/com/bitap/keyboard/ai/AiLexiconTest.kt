package com.bitap.keyboard.ai

import com.bitap.keyboard.ai.internal.AiCandidateParser
import com.bitap.keyboard.ai.internal.AiLexiconPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLexiconTest {

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
    fun formatJoinsCandidatesWithNewLines() {
        assertEquals("你好\n你号", AiCandidateParser.format(listOf("你好", "你号")))
    }
}
