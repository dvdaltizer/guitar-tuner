package com.slowpixels.guitartuner

import com.slowpixels.guitartuner.audio.Ear
import com.slowpixels.guitartuner.model.Chime
import com.slowpixels.guitartuner.model.Tunings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Plays whole sessions to the ear as the mic would hand them over, with the clock following the sound. */
class EarTest {
    private var now = 1_000L
    private val ear = Ear { now }
    private val chimes = ArrayList<Pair<Chime, Int>>()
    private val hz = Tunings.standard.hz(440.0)

    init {
        ear.tune(hz)
        ear.onChime = { c, s -> chimes += c to s }
    }

    /** The sound in the mic's blocks of 1024, the clock moving with each. */
    private fun mic(vararg parts: FloatArray, block: Int = 1024): Flow<ShortArray> = flow {
        for (part in parts) {
            val all = Plucks.shorts(part)
            var at = 0
            while (at < all.size) {
                val n = minOf(block, all.size - at)
                now += n * 1000L / Ear.RATE
                emit(all.copyOfRange(at, at + n))
                at += n
            }
        }
    }

    private fun play(vararg parts: FloatArray, block: Int = 1024) = runBlocking {
        ear.start(this, mic(*parts, block = block))!!.join()
    }

    @Test fun aStringInTuneChimesOnce() = runBlocking {
        val job = ear.start(this, mic(Plucks.silence(0.5), Plucks.heard(hz[0], 0.5, seconds = 1.5)))!!
        job.join()
        assertEquals(listOf(Chime.STRING to 0), chimes)
        // The mic has closed, so the dial rests.
        assertTrue(ear.dial.value.cents.isNaN())
        assertEquals(1, ear.dial.value.reached)
        assertFalse(ear.listening)
    }

    @Test fun aFlatStringSaysHowFar() = runBlocking {
        var seen = Double.NaN
        val job = ear.start(this, flow {
            mic(Plucks.silence(0.4), Plucks.heard(hz[1], -18.0)).collect { emit(it); seen = ear.dial.value.cents.takeIf { c -> !c.isNaN() } ?: seen }
        })!!
        job.join()
        assertTrue("read $seen", abs(seen + 18.0) < 3.0)
        assertTrue(chimes.isEmpty())
    }

    @Test fun noiseIsNotANote() {
        var heard = false
        runBlocking {
            ear.start(this, flow { mic(Plucks.silence(3.0, noise = 0.01)).collect { emit(it); if (ear.dial.value.hearing) heard = true } })!!.join()
        }
        assertFalse(heard)
        assertTrue(chimes.isEmpty())
    }

    @Test fun allSixEndInTheBigOne() {
        val parts = ArrayList<FloatArray>()
        parts += Plucks.silence(0.5)
        for (i in 0 until 6) {
            parts += Plucks.heard(hz[i], seconds = 1.2, seed = i.toLong())
            parts += Plucks.silence(0.4)
        }
        play(*parts.toTypedArray())
        assertEquals(List(5) { Chime.STRING to it } + (Chime.ALL_STRINGS to 5), chimes)
        assertEquals(63, ear.dial.value.reached)
    }

    @Test fun theMicsBlockSizeDoesNotMatter() {
        play(Plucks.silence(0.5), Plucks.heard(hz[3], seconds = 1.5), block = 1920)
        assertEquals(listOf(Chime.STRING to 3), chimes)
    }

    @Test fun aQuietElectricReadsWithTheLowerBar() {
        ear.quiet = true
        play(Plucks.silence(0.5, noise = 0.0006), Plucks.heard(hz[2], peak = 0.01, noise = 0.0006, seconds = 1.5))
        assertEquals(listOf(Chime.STRING to 2), chimes)
    }

    @Test fun aLockedStringOnlyListensForThatString() {
        ear.lock(5)
        play(Plucks.silence(0.5), Plucks.heard(hz[0], seconds = 1.0))
        assertTrue(chimes.isEmpty())
        play(Plucks.silence(0.5), Plucks.heard(hz[5], seconds = 1.2))
        assertEquals(listOf(Chime.STRING to 5), chimes)
    }

    @Test fun aMicThatWontOpenIsSaidSo() {
        runBlocking { ear.start(this, flow { throw IllegalStateException("no mic") })!!.join() }
        assertTrue(ear.failed.value)
        assertFalse(ear.listening)
    }

    @Test fun stoppingRestsTheDial() = runBlocking {
        ear.start(this, flow { mic(Plucks.silence(0.5), Plucks.heard(hz[4], -10.0, seconds = 60.0)).collect { emit(it); if (!ear.dial.value.cents.isNaN()) ear.stop() } })
        // stop() cancels the listening, so this comes back as soon as the string has been heard.
        kotlinx.coroutines.delay(10)
        var waited = 0
        while (ear.listening && waited++ < 500) kotlinx.coroutines.delay(10)
        assertFalse(ear.listening)
        assertTrue(ear.dial.value.cents.isNaN())
        assertEquals(-1, ear.dial.value.string)
    }
}
