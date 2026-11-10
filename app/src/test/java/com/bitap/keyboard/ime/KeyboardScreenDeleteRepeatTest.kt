package com.bitap.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for the progressive backspace repeat curve. */
class KeyboardScreenDeleteRepeatTest {

    @Test
    fun deleteRepeatAcceleratesAndNeverSlows() {
        assertEquals("initial cadence stays unchanged", 55L, deleteRepeatIntervalMs(0))
        assertEquals(55L, deleteRepeatIntervalMs(8))
        assertEquals("after eight repeats", 40L, deleteRepeatIntervalMs(9))
        assertEquals(40L, deleteRepeatIntervalMs(20))
        assertEquals("after twenty repeats", 25L, deleteRepeatIntervalMs(21))

        val values = (0..30).map(::deleteRepeatIntervalMs)
        assertTrue(
            "repeat interval must never increase as the hold continues",
            values.zipWithNext().all { (current, next) -> next <= current },
        )
    }
}
