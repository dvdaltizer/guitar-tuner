package com.slowpixels.guitartuner

import com.slowpixels.guitartuner.audio.Pitch
import com.slowpixels.guitartuner.model.Tunings
import com.slowpixels.guitartuner.model.cents
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plays made-up plucked strings to the detector, shaped like what a phone hears: thin on bass, with noise under it,
 * partials a little sharp as real strings' are, and the pitch settling after the pick.
 */
class PitchTest {
    private val rate = 48_000

    /** A plucked string with [partials] partials, each a bit sharper than a whole multiple. */
    private fun pluck(
        hz: Double,
        seconds: Double,
        pickAt: Double = 0.18,
        stiffness: Double = 0.00012,
        settleCents: Double = 0.0,
        partials: Int = 14,
    ): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        for (k in 1..partials) {
            val f = hz * k * sqrt(1.0 + stiffness * k * k)
            if (f > 9000) break
            // A string picked at one point barely sounds the partials with a node there.
            val shape = abs(sin(k * PI * pickAt)) / k
            val decay = 1.4 + 1.8 / k
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val bend = 2.0.pow(settleCents * exp(-t / 0.12) / 1200.0)
                phase += 2.0 * PI * f * bend / rate
                out[i] += (shape * exp(-t * k.toDouble().pow(0.6) / decay) * sin(phase)).toFloat()
            }
        }
        return out
    }

    /** What a small phone mic does: rolls off the bass below [corner] Hz, twelve decibels an octave. */
    private fun thinBass(x: FloatArray, corner: Double = 180.0): FloatArray {
        val k = 1.0 / (1.0 + 2.0 * PI * corner / rate)
        val out = x.copyOf()
        repeat(2) {
            var lastIn = 0.0
            var lastOut = 0.0
            for (i in out.indices) {
                val v = out[i].toDouble()
                lastOut = k * (lastOut + v - lastIn)
                lastIn = v
                out[i] = lastOut.toFloat()
            }
        }
        return out
    }

    private fun withNoise(x: FloatArray, level: Double, seed: Long = 5): FloatArray {
        val r = Random(seed)
        return FloatArray(x.size) { x[it] + (r.nextGaussian() * level).toFloat() }
    }

    private fun scaled(x: FloatArray, peak: Double): FloatArray {
        val top = x.maxOf { abs(it) }
        return FloatArray(x.size) { (x[it] / top * peak).toFloat() }
    }

    /** Runs the sound through in the app's blocks, returning each reading from [from] seconds on. */
    private fun readings(x: FloatArray, from: Double = 0.3): List<Double> {
        val pitch = Pitch(rate)
        val block = 1024
        val buf = FloatArray(block)
        val out = ArrayList<Double>()
        var at = 0
        var n = 0
        while (at + block <= x.size) {
            System.arraycopy(x, at, buf, 0, block)
            pitch.push(buf, block)
            at += block
            if (++n % 2 == 0 && at.toDouble() / rate >= from) pitch.look()?.let { if (it.clarity >= 0.85f) out += it.hz }
        }
        return out
    }

    private fun median(v: List<Double>) = v.sorted()[v.size / 2]

    @Test fun everyStringOfEveryTuningComesOutRight() {
        var worst = 0.0
        var worstAt = ""
        for (tuning in Tunings.all) for (note in tuning.notes) {
            val hz = note.hz()
            val heard = readings(withNoise(scaled(thinBass(pluck(hz, 1.2)), 0.2), 0.0015))
            assertTrue("${tuning.name} ${note.name}${note.octave}: nothing heard", heard.size > 8)
            val off = heard.map { cents(it, hz) }
            val slip = off.count { abs(it) > 50 }
            assertTrue("${tuning.name} ${note.name}${note.octave}: $slip of ${heard.size} readings slipped, e.g. ${off.firstOrNull { abs(it) > 50 }}", slip == 0)
            val m = abs(median(off))
            if (m > worst) { worst = m; worstAt = "${tuning.name} ${note.name}${note.octave}" }
        }
        println("Worst string: $worstAt, $worst cents out")
        // Stiff strings really do read a little sharp, so this isn't zero.
        assertTrue("$worstAt was $worst cents out", worst < 1.5)
    }

    @Test fun readingsHoldStillOnALowString() {
        val hz = 82.41
        val heard = readings(withNoise(scaled(thinBass(pluck(hz, 2.0)), 0.1), 0.001), from = 0.3).map { cents(it, hz) }
        val spread = heard.max() - heard.min()
        println("Low E: ${heard.size} readings, ${heard.min()} to ${heard.max()}")
        assertTrue("spread $spread", spread < 2.5)
    }

    @Test fun aQuietElectricStillReads() {
        // An unplugged electric across a room: about 40 dB down, with the mic's own hiss not far under it.
        for (hz in doubleArrayOf(82.41, 110.0, 146.83, 196.0, 246.94, 329.63)) {
            val heard = readings(withNoise(scaled(thinBass(pluck(hz, 1.2, pickAt = 0.11)), 0.01), 0.0006)).map { cents(it, hz) }
            assertTrue("$hz heard ${heard.size}", heard.size > 8)
            val m = median(heard)
            assertTrue("$hz is $m out", abs(m) < 2.5)
        }
    }

    @Test fun aStringThatRingsSharpSettles() {
        val hz = 110.0
        val heard = readings(withNoise(scaled(thinBass(pluck(hz, 1.5, settleCents = 14.0)), 0.2), 0.001), from = 0.1).map { cents(it, hz) }
        println("Settling: ${heard.take(10).joinToString { "%.1f".format(it) }}")
        assertTrue("starts sharp", heard.first() > 2.0)
        assertTrue("ends true", abs(median(heard.takeLast(10))) < 1.0)
    }

    @Test fun aVeryThinMicStillFindsTheLowStrings() {
        // Hardly any fundamental left at all: a mic that barely hears below 350 Hz.
        for (hz in doubleArrayOf(55.0, 61.74, 65.41, 73.42, 82.41, 98.0, 110.0)) {
            val heard = readings(withNoise(scaled(thinBass(pluck(hz, 1.2), corner = 350.0), 0.2), 0.0015)).map { cents(it, hz) }
            assertTrue("$hz heard ${heard.size}", heard.size > 8)
            val slips = heard.count { abs(it) > 50 }
            println("$hz Hz through a very thin mic: ${heard.size} readings, $slips slipped, middle ${median(heard)}")
            assertTrue("$hz slipped $slips of ${heard.size}", slips == 0)
            assertTrue("$hz middle ${median(heard)}", abs(median(heard)) < 2.0)
        }
    }

    @Test fun aPickNearTheBridgeReads() {
        // Picked close to the bridge the upper partials are strong, the way a thumb or a hard pick sounds.
        for (hz in doubleArrayOf(82.41, 110.0, 146.83, 196.0, 246.94, 329.63)) {
            val heard = readings(withNoise(scaled(thinBass(pluck(hz, 1.2, pickAt = 0.06)), 0.2), 0.0015)).map { cents(it, hz) }
            assertTrue("$hz slipped: ${heard.map { it.toInt() }}", heard.count { abs(it) > 50 } == 0)
            assertTrue("$hz middle ${median(heard)}", abs(median(heard)) < 2.0)
        }
    }

    @Test fun aNeighbourRingingAlongDoesNotThrowIt() {
        // A plucked string sets the others humming, a good way down.
        val strings = doubleArrayOf(82.41, 110.0, 146.83, 196.0, 246.94, 329.63)
        for (i in strings.indices) {
            val other = strings[(i + 1) % 6]
            val a = thinBass(pluck(strings[i], 1.2))
            val b = thinBass(pluck(other, 1.2))
            val top = a.maxOf { abs(it) }
            val otherTop = b.maxOf { abs(it) }
            val mixed = FloatArray(a.size) { a[it] / top + b[it] / otherTop * 0.12f }
            val heard = readings(withNoise(scaled(mixed, 0.2), 0.0015)).map { cents(it, strings[i]) }
            val slips = heard.count { abs(it) > 50 }
            println("${strings[i]} Hz with ${other} Hz under it: ${heard.size} readings, $slips slipped, middle ${median(heard)}")
            assertTrue("${strings[i]} slipped $slips of ${heard.size}", slips <= heard.size / 8)
            assertTrue("${strings[i]} middle ${median(heard)}", abs(median(heard)) < 3.0)
        }
    }

    @Test fun roomNoiseIsNotANote() {
        val noise = withNoise(FloatArray(rate * 2), 0.01)
        val heard = readings(noise, from = 0.1)
        assertTrue("heard ${heard.size} notes in noise", heard.size <= 1)
    }

    @Test fun aSteadyToneIsAccurate() {
        for (hz in doubleArrayOf(55.0, 82.41, 110.0, 220.0, 329.63, 493.88)) {
            val x = FloatArray(rate) { (0.2 * sin(2.0 * PI * hz * it / rate)).toFloat() }
            val off = median(readings(x)).let { cents(it, hz) }
            assertTrue("$hz is $off out", abs(off) < 0.3)
        }
    }

    @Test fun fortyEightAndFortyFourBothWork() {
        val hz = 196.0
        val x = FloatArray(44_100) { (0.2 * sin(2.0 * PI * hz * it / 44_100.0)).toFloat() }
        val pitch = Pitch(44_100)
        val buf = FloatArray(1024)
        var heard = 0.0
        var at = 0
        while (at + 1024 <= x.size) {
            System.arraycopy(x, at, buf, 0, 1024)
            pitch.push(buf, 1024)
            at += 1024
            pitch.look()?.let { heard = it.hz }
        }
        assertTrue("heard $heard", abs(cents(heard, hz)) < 0.5)
    }

    @Test fun lookingIsCheap() {
        val x = withNoise(scaled(thinBass(pluck(82.41, 2.0)), 0.2), 0.001)
        val pitch = Pitch(rate)
        val buf = FloatArray(1024)
        System.arraycopy(x, 0, buf, 0, 1024)
        repeat(40) { i ->
            System.arraycopy(x, i * 1024, buf, 0, 1024)
            pitch.push(buf, 1024)
        }
        repeat(300) { pitch.look() }
        val start = System.nanoTime()
        repeat(300) { pitch.look() }
        println("One look: ${(System.nanoTime() - start) / 300 / 1000} µs on the Mac")
    }
}
