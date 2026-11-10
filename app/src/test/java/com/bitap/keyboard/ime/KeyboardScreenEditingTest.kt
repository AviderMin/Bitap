package com.bitap.keyboard.ime

import com.bitap.keyboard.core.KeyboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the dedicated editing-page visibility policy and key copy.
 *
 * The Compose page itself cannot render on the JVM, so [shouldShowEditingPage],
 * [editingToolLabels] and [editingHistoryToolLabels] carry the rules: the page is
 * visible only when the user toggled it on and no pinyin composition is active;
 * the eight main keys keep a stable visual order, and undo/redo live on a
 * separate history row instead of the crowded main keyboard function row.
 */
class KeyboardScreenEditingTest {

    @Test
    fun visibleWhenToggledOnAndComposingIsEmpty() {
        assertTrue(
            shouldShowEditingPage(
                KeyboardUiState(editingToolsVisible = true, composing = ""),
            ),
        )
    }

    @Test
    fun hiddenWhileComposing() {
        assertFalse(
            shouldShowEditingPage(
                KeyboardUiState(editingToolsVisible = true, composing = "nihao"),
            ),
        )
    }

    @Test
    fun hiddenWhenToggledOff() {
        assertFalse(
            shouldShowEditingPage(
                KeyboardUiState(editingToolsVisible = false, composing = ""),
            ),
        )
    }

    @Test
    fun labelsKeepVisualOrder() {
        assertEquals(
            listOf("全选", "复制", "剪切", "粘贴", "词左", "词右", "删词", "收起"),
            editingToolLabels(),
        )
    }

    @Test
    fun undoRedoLiveOnASeparateHistoryRow() {
        assertEquals(listOf("撤销", "重做"), editingHistoryToolLabels())
        assertEquals(2, editingHistoryTools().size)
        assertTrue(editingHistoryTools().all { tool -> tool.description.any { it in '\u4e00'..'\u9fff' } })
    }

    @Test
    fun everyKeyHasDistinctChineseContentDescription() {
        val descriptions = editingTools().map { it.description }
        assertEquals("editing page must expose exactly eight keys", 8, descriptions.size)
        assertEquals("content descriptions must be unique", 8, descriptions.toSet().size)
        assertTrue(
            "every editing key needs a Chinese contentDescription",
            descriptions.all { description -> description.any { char -> char in '\u4e00'..'\u9fff' } },
        )
    }
}
