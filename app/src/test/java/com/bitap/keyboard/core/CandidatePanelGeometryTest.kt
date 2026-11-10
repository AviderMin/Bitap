package com.bitap.keyboard.core

import com.bitap.keyboard.ime.CANDIDATE_PANEL_FOOTER_HEIGHT_DP
import com.bitap.keyboard.ime.CANDIDATE_PANEL_INNER_PADDING_DP
import com.bitap.keyboard.ime.CANDIDATE_PANEL_ROW_HEIGHT_DP
import com.bitap.keyboard.ime.candidateBadgeLabel
import com.bitap.keyboard.ime.candidateChipContentDescription
import com.bitap.keyboard.ime.candidatePanelMaxHeightDp
import com.bitap.keyboard.ime.candidatePanelPageCount
import com.bitap.keyboard.ime.candidatePanelPageSize
import com.bitap.keyboard.ime.candidatePanelVisibleRows
import com.bitap.keyboard.ime.firstPartialCandidateIndex
import com.bitap.keyboard.ime.pageForIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM policy coverage for the P0 candidate panel geometry and partial
 * discoverability batch.
 *
 * Device baseline: the expanded panel was configured for 6 rows per page but
 * only ~4 full rows were visible, and the page footer sat below the fold.
 * These tests lock the current flow layout arithmetic (12/8 candidates per page,
 * a 4/2 visible-row height budget plus fixed footer) and the pure helpers that
 * locate partial candidates without reordering the engine candidate list.
 */
class CandidatePanelGeometryTest {

    private fun candidate(
        word: String,
        consumed: Int = 0,
        correction: Boolean = false,
    ): Candidate = Candidate(
        word = word,
        pinyin = "",
        frequency = 0,
        consumedInputLength = consumed,
        isCorrection = correction,
    )

    @Test
    fun pageCapacityFitsMoreCandidatesThanVisibleRows() {
        // Flow layout packs multiple short chips per row, so one page now holds
        // 12 (portrait) / 8 (compact) candidates while the visible-row budget
        // stays 4 / 2.
        assertEquals(12, candidatePanelPageSize(compact = false))
        assertEquals(8, candidatePanelPageSize(compact = true))
        assertTrue(candidatePanelPageSize(false) > candidatePanelVisibleRows(false) * 2)
        assertTrue(candidatePanelPageSize(true) > candidatePanelVisibleRows(true) * 2)
    }

    @Test
    fun maxHeightFitsEveryVisibleRowAndFixedFooter() {
        for (compact in listOf(false, true)) {
            val visibleRows = candidatePanelVisibleRows(compact)
            val expected = visibleRows * CANDIDATE_PANEL_ROW_HEIGHT_DP +
                CANDIDATE_PANEL_FOOTER_HEIGHT_DP + CANDIDATE_PANEL_INNER_PADDING_DP
            assertEquals(
                "total height budget for compact=$compact",
                expected,
                candidatePanelMaxHeightDp(compact),
            )
            // After subtracting the inner padding, the content budget must equal
            // the visible-row budget plus the fixed footer.
            val contentBudget = candidatePanelMaxHeightDp(compact) - CANDIDATE_PANEL_INNER_PADDING_DP
            assertEquals(
                visibleRows * CANDIDATE_PANEL_ROW_HEIGHT_DP,
                contentBudget - CANDIDATE_PANEL_FOOTER_HEIGHT_DP,
            )
            assertTrue("every visible row must fit", contentBudget > CANDIDATE_PANEL_FOOTER_HEIGHT_DP)
        }
        // Explicit before -> after values: portrait 220 -> 264 dp, compact 120 -> 164 dp.
        assertEquals(264, candidatePanelMaxHeightDp(compact = false))
        assertEquals(164, candidatePanelMaxHeightDp(compact = true))
    }

    @Test
    fun pageCountCoversAllCandidatesWithoutExtraEmptyPage() {
        assertEquals(1, candidatePanelPageCount(candidateCount = 0, pageSize = 4))
        assertEquals(1, candidatePanelPageCount(candidateCount = 1, pageSize = 4))
        assertEquals(1, candidatePanelPageCount(candidateCount = 4, pageSize = 4))
        assertEquals(2, candidatePanelPageCount(candidateCount = 5, pageSize = 4))
        assertEquals(2, candidatePanelPageCount(candidateCount = 8, pageSize = 4))
        assertEquals(3, candidatePanelPageCount(candidateCount = 9, pageSize = 4))
        // Compact pages hold 2 rows; 13 candidates -> 7 pages.
        assertEquals(7, candidatePanelPageCount(candidateCount = 13, pageSize = 2))
        // Defensive: zero/negative page size still yields one page (no divide by zero).
        assertEquals(1, candidatePanelPageCount(candidateCount = 5, pageSize = 0))
    }

    @Test
    fun firstPartialIndexIsMinusOneWhenEveryCandidateIsFull() {
        val candidates = listOf(
            candidate("你", consumed = 4),
            candidate("你好", consumed = 4),
            candidate("拟", consumed = 4, correction = true),
            candidate("尼", consumed = 0), // legacy 0 means the whole composition
        )
        assertEquals(-1, firstPartialCandidateIndex(candidates, composingLength = 4))
        assertEquals(-1, firstPartialCandidateIndex(emptyList(), composingLength = 4))
    }

    @Test
    fun firstPartialIndexAtStartAndAfterFullCandidates() {
        assertEquals(
            0,
            firstPartialCandidateIndex(
                listOf(candidate("你", consumed = 2), candidate("你好", consumed = 4)),
                composingLength = 4,
            ),
        )

        // Device case: the partial candidate sits at rank 6 after five full ones.
        val candidates = listOf(
            candidate("完整一", consumed = 7),
            candidate("完整二", consumed = 7),
            candidate("完整三", consumed = 7),
            candidate("完整四", consumed = 7),
            candidate("完整五", consumed = 7),
            candidate("分段词", consumed = 3),
            candidate("完整六", consumed = 7),
        )
        assertEquals(5, firstPartialCandidateIndex(candidates, composingLength = 7))
    }

    @Test
    fun partialDetectionBoundaries() {
        val candidates = listOf(
            candidate("零", consumed = 0),
            candidate("等于全长", consumed = 6),
            candidate("超长", consumed = 9),
            candidate("前缀", consumed = 1),
            candidate("末位前缀", consumed = 5),
        )
        assertEquals(3, firstPartialCandidateIndex(candidates, composingLength = 6))
        // Zero composing length (no active composition) never marks candidates partial.
        assertEquals(-1, firstPartialCandidateIndex(candidates, composingLength = 0))
    }

    @Test
    fun pageForIndexMapsRankToContainingPage() {
        assertEquals(0, pageForIndex(index = 0, pageSize = 4))
        assertEquals(0, pageForIndex(index = 3, pageSize = 4))
        assertEquals(1, pageForIndex(index = 4, pageSize = 4))
        assertEquals(1, pageForIndex(index = 7, pageSize = 4))
        assertEquals(2, pageForIndex(index = 8, pageSize = 4))
        assertEquals(2, pageForIndex(index = 4, pageSize = 2))
        // Defensive clamping for negative indices / invalid page size.
        assertEquals(0, pageForIndex(index = -1, pageSize = 4))
        assertEquals(0, pageForIndex(index = 9, pageSize = 0))
    }

    @Test
    fun firstPartialPageMatchesDeviceCaseAndLastPageBoundary() {
        // Rank 6 partial is on page 0 with both flow capacities, so page 0 must
        // offer the "看分段" shortcut.
        val rankSix = (1..5).map { candidate("完整$it", consumed = 7) } +
            candidate("分段词", consumed = 3)
        val index = firstPartialCandidateIndex(rankSix, composingLength = 7)
        assertEquals(5, index)
        assertEquals(0, pageForIndex(index, candidatePanelPageSize(compact = false)))
        assertEquals(0, pageForIndex(index, candidatePanelPageSize(compact = true)))

        // A partial at the final candidate index is reachable on the same page
        // at the new flow capacity.
        val lastPageCandidates = (1..8).map { candidate("完整$it", consumed = 5) } +
            candidate("尾段", consumed = 2)
        val lastIndex = firstPartialCandidateIndex(lastPageCandidates, composingLength = 5)
        assertEquals(8, lastIndex)
        assertEquals(0, pageForIndex(lastIndex, candidatePanelPageSize(compact = false)))
        val pageCount = candidatePanelPageCount(lastPageCandidates.size, candidatePanelPageSize(compact = false))
        assertEquals(1, pageCount)
    }

    @Test
    fun partialBadgeAndAccessibilityDescription() {
        val partial = candidate("你好", consumed = 2)
        val correctionPartial = candidate("拟", consumed = 2, correction = true)
        val correction = candidate("拟", consumed = 5, correction = true)
        val full = candidate("你", consumed = 5)

        assertEquals("分段", candidateBadgeLabel(isCorrection = false, isPartial = true))
        assertEquals("纠错·分段", candidateBadgeLabel(isCorrection = true, isPartial = true))
        assertEquals("纠错", candidateBadgeLabel(isCorrection = true, isPartial = false))
        assertNull(candidateBadgeLabel(isCorrection = false, isPartial = false))

        assertEquals(
            "分段候选 你好，选择后其余拼音保留",
            candidateChipContentDescription(partial, composingLength = 5),
        )
        assertTrue(
            candidateChipContentDescription(correctionPartial, composingLength = 5)
                .contains("分段候选 拟，选择后其余拼音保留"),
        )
        assertEquals("纠错候选 拟", candidateChipContentDescription(correction, composingLength = 5))
        assertEquals("候选词 你", candidateChipContentDescription(full, composingLength = 5))
    }
}
