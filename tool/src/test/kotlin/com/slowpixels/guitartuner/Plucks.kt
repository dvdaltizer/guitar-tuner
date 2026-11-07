package com.slowpixels.guitartuner

import com.slowpixels.guitartuner.audio.Ear
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Made-up plucked strings, shaped like what a phone hears: thin on bass, with noise under it, partials a little sharp
 * as real strings' are, and the pitch settling after the pick. The same maker as PitchTest's, for the ear and the feed.
 */
object Plucks {
    const val RATE = Ear.RATE

    /** A plucked string with [partials] partials, each a bit sharper than a whole multiple. */
    fun pluck(hz: Double, seconds: Double, pickAt: Double = 0.18, stiffness: Double = 0.00012, settleCents: Double = 0.0, partials: Int = 14): FloatArray {
        val n = (seconds * RATE).toInt()
        val out = FloatArray(n)
        for (k in 1..partials) {
            val f = hz * k * sqrt(1.0 + stiffness * k * k)
            if (f > 9000) break
            val shape = abs(sin(k * PI * pickAt)) / k
            val decay = 1.4 + 1.8 / k
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val bend = 2.0.pow(settleCents * exp(-t / 0.12) / 1200.0)
                phase += 2.0 * PI * f * bend / RATE
                out[i] += (shape * exp(-t * k.toDouble().pow(0.6) / decay) * sin(phase)).toFloat()
            }
        }
        return out
    }

    /** What a small phone mic does: rolls off the bass below [corner] Hz, twelve decibels an octave. */
    fun thinBass(x: FloatArray, corner: Double = 180.0): FloatArray {
        val k = 1.0 / (1.0 + 2.0 * PI * corner / RATE)
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

    fun withNoise(x: FloatArray, level: Double, seed: Long = 5): FloatArray {
        val r = Random(seed)
        return FloatArray(x.size) { x[it] + (r.nextGaussian() * level).toFloat() }
    }

    fun scaled(x: FloatArray, peak: Double): FloatArray {
        val top = x.maxOf { abs(it) }
        return FloatArray(x.size) { (x[it] / top * peak).toFloat() }
    }

    /** A string as the phone hears it: [cents] off [hz], [peak] loud, over room noise. */
    fun heard(hz: Double, cents: Double = 0.0, seconds: Double = 1.2, peak: Double = 0.2, noise: Double = 0.0015, seed: Long = 5): FloatArray =
        withNoise(scaled(thinBass(pluck(hz * 2.0.pow(cents / 1200.0), seconds)), peak), noise, seed)

    fun silence(seconds: Double, noise: Double = 0.0015, seed: Long = 9): FloatArray = withNoise(FloatArray((seconds * RATE).toInt()), noise, seed)

    fun shorts(x: FloatArray) = ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
}
