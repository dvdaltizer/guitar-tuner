package com.slowpixels.guitartuner

import com.slowpixels.guitartuner.ui.ledFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The electric's row of lights: which one a reading lights. */
class LedTest {
    @Test fun flatLightsTheLeft_sharpTheRight() {
        assertTrue(ledFor(-20.0) < 0)
        assertTrue(ledFor(20.0) > 0)
    }

    @Test fun walksInAsTheStringComesIn() {
        var last = 6
        for (c in intArrayOf(50, 30, 20, 12, 6, 3, 1)) {
            val led = ledFor(c.toDouble())
            assertTrue("$c cents lights $led, after $last", led in 1..last)
            last = led
        }
        assertEquals(1, ledFor(0.5))
        assertEquals(5, ledFor(50.0))
        assertEquals(5, ledFor(300.0))
    }

    @Test fun theLastFewCentsStillMoveIt() {
        assertTrue(ledFor(1.0) < ledFor(8.0))
        assertTrue(ledFor(8.0) < ledFor(25.0))
    }
}
