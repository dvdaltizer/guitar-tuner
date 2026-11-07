package com.slowpixels.guitartuner.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What one look at the sound found: a pitch in Hz, and how sure of it (0 to 1). */
class Heard(@JvmField val hz: Double, @JvmField val clarity: Float)

/**
 * Finds the note in the mic's sound.
 *
 * A phone's mic hears very little of a low string's fundamental, so looking for the loudest frequency would call a low
 * E an octave or more too high. This looks for the sound repeating instead, which holds however weak the fundamental is
 * (the McLeod method: each lag's correlation, scaled so a perfect repeat reads 1).
 *
 * The sound is filtered and thinned to about 12 kHz first, which keeps every string's useful partials and makes the
 * sums a quarter as long. The first repeat fixes the note. The accuracy comes from what follows: a lag of many periods
 * is timed as easily as a lag of one, so measuring the eighth repeat gets eight times closer. The lag doubles each step,
 * so it can't slip on to the wrong peak, and ends near half the window.
 *
 * Plain arithmetic with no Android in it, so the tests can run it on anything.
 */
class Pitch(rate: Int = 48_000) {
    private val step = max(1, (rate / 12_000.0).roundToInt())

    /** Samples a second after thinning. */
    val slowRate = rate.toDouble() / step

    private val ring = FloatArray(RING)
    private var head = 0
    private var filled = 0
    private var phase = 0

    private val bass = HighPass(rate, 36.0)
    private val top1 = LowPass(rate, 2400.0, 0.5412)
    private val top2 = LowPass(rate, 2400.0, 1.3066)

    private val x = FloatArray(LONG + LEAD)
    private val square = DoubleArray(LONG + 1)
    private val minLag = (slowRate / MAX_HZ).toInt()
    private val maxLag = (slowRate / MIN_HZ).toInt()
    private val corr = FloatArray(maxLag + 2)
    private val lags = IntArray(maxLag)
    private val values = FloatArray(maxLag)

    /** Takes [count] new samples, each from -1 to 1. */
    fun push(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            val y = top2.next(top1.next(bass.next(samples[i])))
            if (++phase >= step) {
                phase = 0
                ring[head] = y
                head = (head + 1) and (RING - 1)
                if (filled < RING) filled++
            }
        }
    }

    /** Forgets the sound so far, as after the mic stops. */
    fun clear() {
        filled = 0
        head = 0
        bass.clear(); top1.clear(); top2.clear()
    }

    /** How loud the latest stretch of sound is, from 0 to 1 (root mean square). */
    fun level(): Float {
        val n = min(SHORT, filled)
        if (n == 0) return 0f
        var e = 0.0
        var i = (head - n) and (RING - 1)
        repeat(n) {
            val v = ring[i]
            e += v * v
            i = (i + 1) and (RING - 1)
        }
        return sqrt(e / n).toFloat()
    }

    /** The pitch in the latest stretch of sound, or null if nothing in it repeats. */
    fun look(): Heard? {
        if (filled < SHORT) return null
        load(SHORT)
        var e = 0.0
        for (i in 0 until SHORT) e += x[i] * x[i]
        if (e < 1e-10) return null

        val found = firstRepeat(SHORT) ?: return null
        var clarity = corr[found]
        var period = found + vertex(corr[found - 1], corr[found], corr[found + 1])

        // A low note is worth a longer look: it gets more repeats to time, and it changes slowly.
        val window = if (period > LONG_FROM && filled >= LONG + LEAD) LONG else SHORT
        soften(window, period)
        energies(window)
        clarity = max(clarity, 0f)

        var times = 2
        val reach = window / 2
        while (period * times <= reach) {
            period = refine(window, period, times) ?: return null
            times *= 2
        }
        // The furthest whole repeat that fits, for the last step.
        val last = floor(reach / period).toInt()
        if (last > times / 2) period = refine(window, period, last) ?: return null
        return Heard(slowRate / period, clarity)
    }

    /** Brings the latest [n] samples into [x], oldest first. */
    private fun load(n: Int) {
        var i = (head - n) and (RING - 1)
        for (j in 0 until n) {
            x[j] = ring[i]
            i = (i + 1) and (RING - 1)
        }
    }

    /**
     * Quietens the upper partials before the timing. A real string's partials run a little sharp, the thicker the string
     * the more, and the sharpest peaks in the match come from them. Left alone they pull a low E a few cents high, and
     * the reading sags as they die away.
     */
    private fun soften(n: Int, period: Double) {
        // It starts early so the filter has settled by the time the window begins: a window that doesn't quite repeat
        // reads a little flat.
        val lead = min(LEAD, filled - n).coerceAtLeast(0)
        load(n + lead)
        val k = (1.0 - exp(-2.0 * PI * SOFTEN_FROM / period)).toFloat()
        var a = x[0]
        var b = x[0]
        for (i in 0 until n + lead) {
            a += k * (x[i] - a)
            b += k * (a - b)
            x[i] = b
        }
        System.arraycopy(x, lead, x, 0, n)
    }

    private fun energies(n: Int) {
        square[0] = 0.0
        for (i in 0 until n) square[i + 1] = square[i] + x[i].toDouble() * x[i]
    }

    /** How well the sound matches itself [lag] samples later: 1 is a perfect repeat, -1 a perfect opposite. */
    private fun match(n: Int, lag: Int): Float {
        val span = n - lag
        var sum = 0f
        for (j in 0 until span) sum += x[j] * x[j + lag]
        val power = square[span] + square[n] - square[lag]
        return if (power <= 0.0) 0f else (2.0 * sum / power).toFloat()
    }

    /** The shortest lag that repeats about as well as the best one, with [corr] filled in for it. */
    private fun firstRepeat(n: Int): Int? {
        energies(n)
        for (lag in 1..maxLag + 1) corr[lag] = match(n, lag)

        // Past the hump around zero, each positive hump has one peak.
        var i = 1
        while (i <= maxLag && corr[i] > 0f) i++
        var peaks = 0
        var best = 0f
        while (i <= maxLag) {
            while (i <= maxLag && corr[i] <= 0f) i++
            var at = -1
            var value = 0f
            while (i <= maxLag && corr[i] > 0f) {
                if (corr[i] > value) { value = corr[i]; at = i }
                i++
            }
            if (at in max(minLag, 2)..maxLag) {
                lags[peaks] = at
                values[peaks] = value
                peaks++
                if (value > best) best = value
            }
        }
        if (peaks == 0 || best < LEAST_MATCH) return null

        var chosen = -1
        for (p in 0 until peaks) if (values[p] >= best * NEAR_BEST) { chosen = p; break }
        if (chosen < 0) return null

        // A note with strong even partials repeats nearly as well at half its period. If the first peak is only so-so and
        // twice the lag is clearly better, that's what was found, and the same goes for three times. A first peak that's
        // already a good match is the note: a string's partials also line up now and then at twice its period.
        if (values[chosen] < GOOD_MATCH) for (times in intArrayOf(2, 3)) {
            val want = lags[chosen] * times
            for (p in chosen + 1 until peaks) {
                if (abs(lags[p] - want) <= 1 + want / 50 && values[p] > values[chosen] + LOWER_NOTE_MARGIN) {
                    chosen = p
                    break
                }
            }
        }
        return lags[chosen]
    }

    /** A closer [period], from timing the repeat [times] periods on. Null if the sound has stopped repeating there. */
    private fun refine(n: Int, period: Double, times: Int): Double? {
        val guess = period * times
        var center = guess.roundToInt()
        var best = Float.NEGATIVE_INFINITY
        var at = center
        for (lag in center - 2..center + 2) {
            if (lag < 2 || lag >= n - 1) return null
            val v = match(n, lag)
            if (v > best) { best = v; at = lag }
        }
        center = at
        val a = match(n, center - 1)
        val b = match(n, center)
        val c = match(n, center + 1)
        if (b < a || b < c) return period
        return (center + vertex(a, b, c)) / times
    }

    /** Where the top of a parabola through three neighbours sits, from the middle one. */
    private fun vertex(a: Float, b: Float, c: Float): Double {
        val bend = a - 2f * b + c
        return if (abs(bend) < 1e-9f) 0.0 else (0.5f * (a - c) / bend).toDouble().coerceIn(-1.0, 1.0)
    }

    private class LowPass(rate: Int, hz: Double, q: Double) {
        private val w = 2.0 * PI * hz / rate
        private val alpha = sin(w) / (2.0 * q)
        private val norm = 1.0 + alpha
        private val b0 = ((1.0 - cos(w)) / 2.0 / norm).toFloat()
        private val b1 = ((1.0 - cos(w)) / norm).toFloat()
        private val a1 = (-2.0 * cos(w) / norm).toFloat()
        private val a2 = ((1.0 - alpha) / norm).toFloat()
        private var s1 = 0f
        private var s2 = 0f

        fun next(v: Float): Float {
            val y = b0 * v + s1
            s1 = b1 * v - a1 * y + s2
            s2 = b0 * v - a2 * y
            return y
        }

        fun clear() { s1 = 0f; s2 = 0f }
    }

    private class HighPass(rate: Int, hz: Double) {
        private val k = (1.0 / (1.0 + 2.0 * PI * hz / rate)).toFloat()
        private var lastIn = 0f
        private var lastOut = 0f

        fun next(v: Float): Float {
            lastOut = k * (lastOut + v - lastIn)
            lastIn = v
            return lastOut
        }

        fun clear() { lastIn = 0f; lastOut = 0f }
    }

    companion object {
        /** A low string a long way down, to the top of what a guitar's open strings and harmonics reach. */
        const val MIN_HZ = 42.0
        const val MAX_HZ = 1100.0

        private const val RING = 4096

        /** The usual look, about 85 ms, and the longer one for low notes, about 170 ms. */
        private const val SHORT = 1024
        private const val LONG = 2048

        /** Sound taken in ahead of the window to settle [soften], enough for the lowest note's whole period. */
        private const val LEAD = 320

        /** Periods longer than this (notes below about 150 Hz) get the longer look. */
        private const val LONG_FROM = 80.0

        /** Where [soften] starts, as a multiple of the note. */
        private const val SOFTEN_FROM = 2.0

        /** Below this nothing repeats enough to call it a note. */
        private const val LEAST_MATCH = 0.5f

        /** The first peak this close to the best one is the note. */
        private const val NEAR_BEST = 0.92f

        /** A first peak this good isn't questioned. */
        private const val GOOD_MATCH = 0.9f

        /** How much better a multiple of the lag has to be to overrule the first peak. */
        private const val LOWER_NOTE_MARGIN = 0.03f
    }
}
