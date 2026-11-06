package com.bitap.keyboard.qa

import com.bitap.keyboard.ai.internal.AiResultSanitizer
import com.bitap.keyboard.core.AiPanelUiState
import com.bitap.keyboard.core.AiStatus
import com.bitap.keyboard.core.KeyboardAction
import com.bitap.keyboard.core.KeyboardReducer
import com.bitap.keyboard.core.KeyboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA acceptance guard for the AI safety invariants in docs/03-ai-design.md and
 * docs/01-experience-spec.md: cleaned output only, bounded length, never auto-insert.
 */
class QaAiSafetyTest {

    @Test
    fun sanitizerAcceptsExactlyThreeTimesSourceButRejectsOneMoreChar() {
        val source = "a".repeat(100)
        assertEquals(300, AiResultSanitizer.maxAllowedLength(source))
        assertTrue(AiResultSanitizer.sanitize("b".repeat(300), source) is AiResultSanitizer.Result.Success)
        assertEquals(
            AiResultSanitizer.Result.Failure(AiResultSanitizer.FailureReason.TOO_LONG),
            AiResultSanitizer.sanitize("b".repeat(301), source),
        )
    }

    @Test
    fun sanitizerRemovesThinkPreambleAndWrappingQuotesTogether() {
        val raw = "<think>内部推理</think>以下是润色后的文本：\n\n“你好，世界。”"
        assertEquals("你好，世界。", AiResultSanitizer.cleanup(raw))
    }

    @Test
    fun sanitizerFailsOnWhitespaceOnlyOutput() {
        assertEquals(
            AiResultSanitizer.Result.Failure(AiResultSanitizer.FailureReason.EMPTY),
            AiResultSanitizer.sanitize("\n \t\n", "原文"),
        )
    }

    @Test
    fun aiInsertIsNoOpUntilResultCompleted() {
        val statuses = listOf(
            AiStatus.Idle,
            AiStatus.Thinking,
            AiStatus.Streaming,
            AiStatus.Failed,
            AiStatus.Unavailable,
        )
        statuses.forEach { status ->
            val state = KeyboardUiState(
                ai = AiPanelUiState(visible = true, source = "原文", status = status, result = "候选"),
            )
            val reduction = KeyboardReducer.reduce(state, KeyboardAction.AiInsert)
            assertTrue("status=" + status + " must not insert", reduction.commands.isEmpty())
            assertTrue("status=" + status + " must keep panel", reduction.state.ai.visible)
        }
    }

    @Test
    fun aiCompletedResultIsCappedAtMaxResultLength() {
        val state = KeyboardUiState(
            ai = AiPanelUiState(visible = true, source = "原文", status = AiStatus.Thinking),
        )
        val reduction = KeyboardReducer.reduce(state, KeyboardAction.AiCompleted("字".repeat(2000)))
        assertEquals(KeyboardReducer.MAX_AI_RESULT_CHARS, reduction.state.ai.result.length)
        assertEquals(AiStatus.Completed, reduction.state.ai.status)
    }

    @Test
    fun aiPanelCannotOpenWhenSettingIsOff() {
        val reduction = KeyboardReducer.reduce(
            KeyboardUiState(aiEnabled = false, aiReady = true),
            KeyboardAction.AiPanelOpened("hello"),
        )
        assertFalse(reduction.state.ai.visible)
        assertTrue(reduction.commands.isEmpty())
    }
}
