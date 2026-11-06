package com.bitap.keyboard.ai

import com.bitap.keyboard.ai.internal.AiResultSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResultSanitizerTest {

    @Test
    fun stripsWrappingQuotes() {
        val result = AiResultSanitizer.sanitize("\"你好\"", "你好")
        assertEquals(AiResultSanitizer.Result.Success("你好"), result)
    }

    @Test
    fun stripsChineseQuotes() {
        val result = AiResultSanitizer.sanitize("「你好」", "你好")
        assertEquals(AiResultSanitizer.Result.Success("你好"), result)
    }

    @Test
    fun stripsMarkdownEmphasis() {
        val result = AiResultSanitizer.sanitize("**你好**", "你好")
        assertEquals(AiResultSanitizer.Result.Success("你好"), result)
    }

    @Test
    fun stripsCodeFences() {
        val raw = "```\n你好\n```"
        assertEquals("你好", AiResultSanitizer.cleanup(raw))
    }

    @Test
    fun stripsLeadingChinesePrefix() {
        val result = AiResultSanitizer.sanitize("以下是润色后的文本：\n\n你好", "你好")
        assertEquals(AiResultSanitizer.Result.Success("你好"), result)
    }

    @Test
    fun removesThinkBlock() {
        val raw = "<think>内部思考</think>最终答案"
        assertEquals("最终答案", AiResultSanitizer.cleanup(raw))
    }

    @Test
    fun removesThinkBlockWithFullWidthEndMarker() {
        val raw = "<think>内部思考<｜end▁of▁thinking｜>最终答案"
        assertEquals("最终答案", AiResultSanitizer.cleanup(raw))
    }

    @Test
    fun emptyResultFails() {
        val result = AiResultSanitizer.sanitize("   \n  ", "原文")
        assertEquals(AiResultSanitizer.Result.Failure(AiResultSanitizer.FailureReason.EMPTY), result)
    }

    @Test
    fun tooLongResultFails() {
        val source = "1234567890"
        val raw = "a".repeat(AiResultSanitizer.maxAllowedLength(source) + 1)
        val result = AiResultSanitizer.sanitize(raw, source)
        assertEquals(AiResultSanitizer.Result.Failure(AiResultSanitizer.FailureReason.TOO_LONG), result)
    }

    @Test
    fun lengthLimitIsThreeTimesSourceCappedAt512() {
        assertEquals(300, AiResultSanitizer.maxAllowedLength("a".repeat(100)))
        assertEquals(512, AiResultSanitizer.maxAllowedLength("a".repeat(300)))
        assertEquals(512, AiResultSanitizer.maxAllowedLength("a".repeat(2000)))
    }

    @Test
    fun exact512CharactersIsAccepted() {
        val source = "a".repeat(2000)
        val raw = "b".repeat(512)
        val result = AiResultSanitizer.sanitize(raw, source)
        assertTrue(result is AiResultSanitizer.Result.Success)
        assertEquals(raw, (result as AiResultSanitizer.Result.Success).text)
    }

    @Test
    fun collapsesExtraBlankLines() {
        assertEquals("第一段\n\n第二段", AiResultSanitizer.cleanup("第一段\n\n\n\n第二段"))
    }
}
