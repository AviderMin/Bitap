package com.bitap.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for the invisible long-press slide pad below the spacebar. */
class KeyboardScreenSpaceCursorTest {

    @Test
    fun dragDistanceMapsToWholeCursorSteps() {
        assertEquals("no movement needs no step", 0, cursorStepsForDrag(0f, 16f))
        assertEquals("below one threshold is ignored", 0, cursorStepsForDrag(15.9f, 16f))
        assertEquals("one threshold is one step", 1, cursorStepsForDrag(16f, 16f))
        assertEquals("one and a half thresholds is still one step", 1, cursorStepsForDrag(31.9f, 16f))
        assertEquals("two thresholds are two steps", 2, cursorStepsForDrag(32f, 16f))
    }

    @Test
    fun dragDirectionKeepsSignAndRejectsInvalidThreshold() {
        assertEquals("left drag is negative", -1, cursorStepsForDrag(-16f, 16f))
        assertEquals(-2, cursorStepsForDrag(-47.9f, 16f))
        assertEquals("invalid threshold cannot move the cursor", 0, cursorStepsForDrag(64f, 0f))
        assertEquals(0, cursorStepsForDrag(64f, -1f))
    }

    @Test
    fun gestureThresholdAndHintStayStable() {
        assertEquals(16, CURSOR_DRAG_STEP_DP)
        assertTrue(SPACE_CURSOR_HINT.contains("长按滑动"))
        assertTrue(SPACE_CURSOR_HINT.contains("光标"))
    }
}
