package com.slowpixels.guitartuner

import com.slowpixels.guitartuner.model.Chime
import com.slowpixels.guitartuner.model.Judge
import com.slowpixels.guitartuner.model.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Plays whole tuning sessions, a reading every 43 ms, as the mic does. */
class JudgeTest {
    private val hop = 43L
    private var now = 1_000L
    private val judge = Judge().apply { tune(Tunings.standard.hz(440.0)) }
    private val chimes = ArrayList<Chime>()

    private fun offBy(hz: Double, cents: Double) = hz * 2.0.pow(cents / 1200.0)

    private fun play(hz: Double?, ms: Long) {
        val end = now + ms
        while (now < end) {
            val c = judge.hear(now, hz)
            if (c != Chime.NONE) chimes += c
            now += hop
        }
    }

    private fun pick(hz: Double, ms: Long = 900) {
        judge.onset()
        play(hz, ms)
    }

    private val hz get() = Tunings.standard.hz(440.0)

    @Test fun aStringInTuneChimesOnce() {
        pick(offBy(hz[0], 1.0), 1500)
        assertEquals(listOf(Chime.STRING), chimes)
        assertEquals(0, judge.dial.string)
        assertTrue(judge.dial.inTune)
        assertEquals(1, judge.dial.reached)
    }

    @Test fun aStringOutOfTuneSaysWhichWay() {
        pick(offBy(hz[1], -18.0))
        assertEquals(1, judge.dial.string)
        assertEquals(-18.0, judge.dial.cents, 0.01)
        assertFalse(judge.dial.inTune)
        assertTrue(chimes.isEmpty())
    }

    @Test fun turningThePegIntoTuneChimes() {
        judge.onset()
        var off = -30.0
        while (off < 0) {
            play(offBy(hz[3], off), 86)
            off += 1.5
        }
        assertTrue(chimes.isEmpty() || chimes == listOf(Chime.STRING))
        play(offBy(hz[3], 0.0), 500)
        assertEquals(listOf(Chime.STRING), chimes)
    }

    @Test fun passingThroughTheWindowQuicklyDoesNotChime() {
        judge.onset()
        var off = -20.0
        while (off < 20) {
            play(offBy(hz[2], off), 43)
            off += 3.0
        }
        assertTrue(chimes.isEmpty())
    }

    @Test fun pickingAgainChimesAgain() {
        pick(hz[4], 800)
        play(null, 400)
        pick(hz[4], 800)
        assertEquals(listOf(Chime.STRING, Chime.STRING), chimes)
    }

    @Test fun aRingingStringDoesNotChimeTwice() {
        pick(hz[5], 4000)
        assertEquals(listOf(Chime.STRING), chimes)
    }

    @Test fun allSixEndInTheBigOne() {
        for (i in 0 until 6) {
            pick(hz[i], 800)
            play(null, 300)
        }
        assertEquals(List(5) { Chime.STRING } + Chime.ALL_STRINGS, chimes)
        assertEquals(63, judge.dial.reached)
    }

    @Test fun aStringThatSlipsLosesItsMark() {
        pick(hz[2], 800)
        assertEquals(1 shl 2, judge.dial.reached)
        pick(offBy(hz[2], -15.0), 800)
        assertEquals(0, judge.dial.reached)
    }

    @Test fun aStringFarOutStillFindsItsPeg() {
        pick(offBy(hz[0], -180.0))
        assertEquals(0, judge.dial.string)
        assertEquals(-180.0, judge.dial.cents, 0.01)
    }

    @Test fun anOctaveSlipOnADyingNoteStaysOnTheString() {
        pick(offBy(hz[0], -9.0), 500)
        play(offBy(hz[0] * 2, -9.0), 300)
        assertEquals(0, judge.dial.string)
        assertEquals(-9.0, judge.dial.cents, 0.01)
    }

    @Test fun aHarmonicCountsForItsString() {
        pick(hz[5] * 2, 800)
        assertEquals(5, judge.dial.string)
        assertEquals(listOf(Chime.STRING), chimes)
    }

    @Test fun aLockedStringOnlyListensForThatString() {
        judge.lock(5)
        pick(hz[0], 700)
        assertEquals(5, judge.dial.string)
        assertTrue(chimes.isEmpty() || judge.dial.cents.isNaN().not())
        pick(hz[5], 700)
        assertEquals(listOf(Chime.STRING), chimes)
    }

    @Test fun twoStringsOnOneNoteTakeTurns() {
        val iris = Tunings.named("Iris").hz(440.0)
        val j = Judge().apply { tune(iris) }
        fun go(pitch: Double) {
            j.onset()
            var t = now
            repeat(20) { j.hear(t, pitch); t += hop }
            repeat(10) { j.hear(t, null); t += hop }
            now = t
        }
        go(iris[2])
        assertEquals(1 shl 2, j.dial.reached)
        go(iris[3])
        assertEquals((1 shl 2) or (1 shl 3), j.dial.reached)
    }

    @Test fun theLastReadingStaysUpThenRests() {
        pick(offBy(hz[1], 8.0))
        play(null, 800)
        assertEquals(1, judge.dial.string)
        assertEquals(8.0, judge.dial.cents, 0.01)
        assertFalse(judge.dial.hearing)
        play(null, 1000)
        assertEquals(-1, judge.dial.string)
        assertTrue(judge.dial.cents.isNaN())
    }

    @Test fun itsOwnChimeIsNotHeard() {
        pick(hz[0], 800)
        assertEquals(1, chimes.size)
        judge.deafen(now, 450, 1400)
        play(1318.5, 450) // the chime itself, loud
        play(659.3, 900) // its quiet tail
        assertEquals(1, chimes.size)
        // A new pick comes through as soon as the loud part is over.
        now += 500
        pick(hz[1], 800)
        assertEquals(2, chimes.size)
    }

    @Test fun aDifferentReferencePitchMovesTheTargets() {
        val j = Judge().apply { tune(Tunings.standard.hz(432.0)) }
        j.onset()
        var t = now
        repeat(10) { j.hear(t, 108.0); t += hop } // A at 432
        assertEquals(1, j.dial.string)
        assertEquals(0.0, j.dial.cents, 0.01)
    }
}
