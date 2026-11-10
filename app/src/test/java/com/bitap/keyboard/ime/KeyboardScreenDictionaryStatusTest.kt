package com.bitap.keyboard.ime

import com.bitap.keyboard.core.DictionaryLoadState
import com.bitap.keyboard.core.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-B JVM coverage for the candidate-bar dictionary status policy.
 *
 * The composable itself cannot run on the JVM, so the copy, visibility and
 * tappability rules live in pure functions that are pinned here: Chinese-only,
 * Loading shows progress, Failed shows the retry copy and is tappable, Ready
 * renders nothing, and a P0-A query error shares the same single line without
 * overriding the higher-priority dictionary failure.
 */
class KeyboardScreenDictionaryStatusTest {

    @Test
    fun loadingTextOnlyInChineseMode() {
        assertEquals(
            "词库加载中…",
            dictionaryStatusText(InputLanguage.Chinese, DictionaryLoadState.Loading),
        )
        assertNull(dictionaryStatusText(InputLanguage.Chinese, DictionaryLoadState.Ready))
        assertNull(dictionaryStatusText(InputLanguage.English, DictionaryLoadState.Loading))
    }

    @Test
    fun failedTextIsActionableAndContainsRawFallbackHint() {
        assertEquals(
            "词库加载失败，点此重试（仍可提交原文）",
            dictionaryStatusText(InputLanguage.Chinese, DictionaryLoadState.Failed),
        )
        assertTrue(dictionaryStatusActionable(DictionaryLoadState.Failed))
        assertFalse(dictionaryStatusActionable(DictionaryLoadState.Loading))
        assertFalse(dictionaryStatusActionable(DictionaryLoadState.Ready))
    }

    @Test
    fun readyStateRendersNoStatusLine() {
        assertNull(
            candidateBarStatus(
                language = InputLanguage.Chinese,
                dictionaryState = DictionaryLoadState.Ready,
                candidateQueryError = null,
            ),
        )
        assertNull(
            candidateBarStatus(
                language = InputLanguage.English,
                dictionaryState = DictionaryLoadState.Failed,
                candidateQueryError = null,
            ),
        )
    }

    @Test
    fun failureWinsOverQueryErrorOnTheSameLine() {
        val status = candidateBarStatus(
            language = InputLanguage.Chinese,
            dictionaryState = DictionaryLoadState.Failed,
            candidateQueryError = "候选查询失败，请重试或继续输入",
        )
        assertEquals("词库加载失败，点此重试（仍可提交原文）", status?.text)
        assertTrue(status?.actionable == true)
        assertTrue(status?.isError == true)
    }

    @Test
    fun queryErrorShowsWhenDictionaryIsReady() {
        val status = candidateBarStatus(
            language = InputLanguage.Chinese,
            dictionaryState = DictionaryLoadState.Ready,
            candidateQueryError = "候选查询失败，请重试或继续输入",
        )
        assertEquals("候选查询失败，请重试或继续输入", status?.text)
        assertFalse(status?.actionable == true)
        assertTrue(status?.isError == true)
    }

    @Test
    fun loadingIsInformationalButQueryErrorHasPriority() {
        val loading = candidateBarStatus(
            language = InputLanguage.Chinese,
            dictionaryState = DictionaryLoadState.Loading,
            candidateQueryError = null,
        )
        assertEquals("词库加载中…", loading?.text)
        assertFalse(loading?.isError == true)

        val queryError = candidateBarStatus(
            language = InputLanguage.Chinese,
            dictionaryState = DictionaryLoadState.Loading,
            candidateQueryError = "候选查询失败，请重试或继续输入",
        )
        assertEquals("候选查询失败，请重试或继续输入", queryError?.text)
        assertTrue(queryError?.isError == true)
    }
}
