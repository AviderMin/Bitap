package com.bitap.keyboard.ai

import com.bitap.keyboard.ai.internal.AiPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptBuilderTest {

    @Test
    fun systemPromptMatchesProductSpec() {
        assertTrue(AiPromptBuilder.SYSTEM_PROMPT.contains("只输出改写后的文本"))
        assertTrue(AiPromptBuilder.SYSTEM_PROMPT.contains("不解释"))
        assertTrue(AiPromptBuilder.SYSTEM_PROMPT.contains("不要展示思考过程"))
    }

    @Test
    fun normalizeInputTrimsWhitespace() {
        assertEquals("你好", AiPromptBuilder.normalizeInput("  你好  "))
    }

    @Test
    fun normalizeInputCapsAt512Characters() {
        val input = "a".repeat(600)
        assertEquals(512, AiPromptBuilder.normalizeInput(input).length)
    }

    @Test
    fun userPromptContainsToneAndSource() {
        val prompt = AiPromptBuilder.buildUserPrompt("你好世界", RewriteTone.Polish)
        assertTrue(prompt.contains("任务：润色"))
        assertTrue(prompt.contains("原文：你好世界"))
        assertTrue(prompt.contains("/no_think"))
    }

    @Test
    fun everyToneHasLabel() {
        assertEquals("润色", RewriteTone.Polish.label)
        assertEquals("精简", RewriteTone.Concise.label)
        assertEquals("正式", RewriteTone.Formal.label)
        assertEquals("译为英文", RewriteTone.English.label)
    }

    @Test
    fun buildCombinesSystemAndUserPrompts() {
        val prompt = AiPromptBuilder.build("原文", RewriteTone.Concise)
        assertEquals(AiPromptBuilder.SYSTEM_PROMPT, prompt.system)
        assertTrue(prompt.user.contains("任务：精简"))
    }
}
