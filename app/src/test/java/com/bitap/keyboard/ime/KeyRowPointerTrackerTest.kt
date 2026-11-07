package com.bitap.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event-level regression tests for R01/R02.
 *
 * [KeyRowPointerTracker] is the exact bookkeeping object driven by KeyRow's
 * pointerInput handler. These tests model the pointer down/move/up event
 * sequences that the Compose layer receives from a real multi-touch screen.
 */
class KeyRowPointerTrackerTest {

    @Test
    fun simultaneousQFingersCommitInReleaseOrder() {
        val tracker = KeyRowPointerTracker()
        tracker.down(pointerId = 1L, keyIndex = 0) // q
        tracker.down(pointerId = 2L, keyIndex = 1) // w

        assertEquals(0, tracker.release(1L))
        assertEquals(1, tracker.release(2L))
        assertEquals(0, tracker.activePointerCount)
    }

    @Test
    fun interleavedLiftCommitsInActualLiftOrder() {
        val tracker = KeyRowPointerTracker()
        tracker.down(pointerId = 1L, keyIndex = 0) // q
        tracker.down(pointerId = 2L, keyIndex = 1) // w

        assertEquals(1, tracker.release(2L))
        assertEquals(0, tracker.release(1L))
        assertTrue(tracker.pressedKeyIndices.isEmpty())
    }

    @Test
    fun crossRowPointersAreIndependent() {
        val firstRow = KeyRowPointerTracker()
        val secondRow = KeyRowPointerTracker()
        firstRow.down(pointerId = 10L, keyIndex = 0) // q in row 1
        secondRow.down(pointerId = 11L, keyIndex = 16) // a in row 2

        assertEquals(16, secondRow.release(11L))
        assertEquals(0, firstRow.release(10L))
    }

    @Test
    fun longPressAfterExactlyNRepeatsNeverAddsClickOnRelease() {
        val tracker = KeyRowPointerTracker()
        tracker.down(pointerId = 1L, keyIndex = 9) // delete-like key

        repeat(5) { tracker.recordRepeat(1L) }
        assertEquals(5, tracker.repeatCount(1L))
        assertTrue(tracker.isRepeated(1L))
        // Stopping/cancelling the job must not erase the fact that repeats fired.
        assertNull(tracker.release(1L))
    }

    @Test
    fun shortTapStillCommitsExactlyOneKey() {
        val tracker = KeyRowPointerTracker()
        tracker.down(pointerId = 1L, keyIndex = 9)
        assertEquals(9, tracker.release(1L))
    }

    @Test
    fun slidingToAnotherKeyResetsPreviousRepeatFact() {
        val tracker = KeyRowPointerTracker()
        tracker.down(pointerId = 1L, keyIndex = 9)
        repeat(3) { tracker.recordRepeat(1L) }
        assertTrue(tracker.isRepeated(1L))

        assertTrue(tracker.setKey(1L, keyIndex = 10))
        assertFalse(tracker.isRepeated(1L))
        assertEquals(0, tracker.repeatCount(1L))
        assertEquals(10, tracker.release(1L))
    }

    @Test
    fun reenteringSameKeyKeepsRepeatFact() {
        val tracker = KeyRowPointerTracker()
        tracker.down(pointerId = 1L, keyIndex = 9)
        tracker.recordRepeat(1L)
        assertTrue(tracker.cancel(1L))
        assertTrue(tracker.resume(1L))
        assertTrue(tracker.isRepeated(1L))
        // The same long-press selection is still in flight, so no extra click.
        assertNull(tracker.release(1L))
    }

    @Test
    fun cancelledPointerDoesNotCommitOnRelease() {
        val tracker = KeyRowPointerTracker()
        tracker.down(pointerId = 1L, keyIndex = 4)
        assertTrue(tracker.cancel(1L))
        assertTrue(tracker.pressedKeyIndices.isEmpty())
        assertNull(tracker.release(1L))
    }

    @Test
    fun pressedIndicesKeepEveryFingerVisible() {
        val tracker = KeyRowPointerTracker()
        tracker.down(1L, 0)
        tracker.down(2L, 1)
        assertEquals(setOf(0, 1), tracker.pressedKeyIndices)
        tracker.release(1L)
        assertEquals(setOf(1), tracker.pressedKeyIndices)
    }

    @Test
    fun hundredTwoThumbPairsNeverDropOrDuplicate() {
        val committed = mutableListOf<Int>()
        repeat(100) { round ->
            val tracker = KeyRowPointerTracker()
            tracker.down(pointerId = 1L, keyIndex = 0) // q
            tracker.down(pointerId = 2L, keyIndex = 1) // w
            if (round % 2 == 0) {
                tracker.release(1L)?.let { committed.add(it) }
                tracker.release(2L)?.let { committed.add(it) }
            } else {
                tracker.release(2L)?.let { committed.add(it) }
                tracker.release(1L)?.let { committed.add(it) }
            }
        }

        assertEquals(200, committed.size)
        assertEquals(100, committed.count { it == 0 })
        assertEquals(100, committed.count { it == 1 })
    }
}
