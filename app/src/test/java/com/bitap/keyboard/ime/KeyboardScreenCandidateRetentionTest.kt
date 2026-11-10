package com.bitap.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for the no-blank candidate-row transition policy. */
class KeyboardScreenCandidateRetentionTest {

    @Test
    fun keepsPreviousRowOnlyWhileNextQueryIsPending() {
        assertTrue(
            "new composition with no fresh result yet must keep the previous row",
            shouldRetainCandidateRow(
                composing = "nih",
                candidatesFor = "ni",
                hasFreshCandidates = false,
                candidateQueryError = null,
            ),
        )
        assertFalse(
            "fresh result must replace immediately",
            shouldRetainCandidateRow("nih", "nih", hasFreshCandidates = true, candidateQueryError = null),
        )
        assertFalse(
            "completed empty result must clear the old row",
            shouldRetainCandidateRow("nih", "nih", hasFreshCandidates = false, candidateQueryError = null),
        )
        assertFalse(
            "empty composition must clear the old row",
            shouldRetainCandidateRow("", "ni", hasFreshCandidates = false, candidateQueryError = null),
        )
        assertFalse(
            "query failure must clear the old row and expose the error",
            shouldRetainCandidateRow("nih", "ni", hasFreshCandidates = false, candidateQueryError = "候选查询失败"),
        )
    }
}
