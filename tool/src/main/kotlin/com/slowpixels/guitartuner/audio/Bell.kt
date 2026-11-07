package com.slowpixels.guitartuner.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The app's sounds, made from nothing as they're needed, so there are no sound files.
 *
 * A chime is two strikes of a small bell: a soft one a fourth below, then the note itself, the way a resolving tune falls
 * home. It rings three octaves above the string that earned it, up where the phone's speaker is at its best. Being the
 * string's own note, it sits in harmony with the string that's still sounding, and six of them in a row spell out the
 * tuning: finishing Open G rings a G chord.
 */
object Bell {
    const val RATE = 48_000

    /** The two strikes and the air after them, for one string in tune. */
    fun chime(stringHz: Double): ShortArray {
        val note = rung(stringHz)
        val mix = FloatArray(samples(1.5))
        strike(mix, 0, note * FOURTH_BELOW, 0.5f, 0.3f)
        strike(mix, samples(0.095), note, 1f, 1f)
        return finished(mix)
    }

    /** All the strings' bells, lowest to highest, for the last string coming into tune. */
    fun flourish(strings: DoubleArray): ShortArray {
        val notes = strings.map(::rung).sorted()
        val mix = FloatArray(samples(2.3))
        notes.forEachIndexed { i, note ->
            val last = i == notes.lastIndex
            strike(mix, samples(0.085 * i), note, if (last) 1f else 0.72f, if (last) 1.5f else 0.9f)
        }
        return finished(mix)
    }

    /** Where a string's bell rings: its own note, moved by octaves into the speaker's best range. */
    fun rung(stringHz: Double): Double {
        var hz = stringHz * 8.0
        while (hz < LOWEST_BELL) hz *= 2.0
        while (hz > HIGHEST_BELL) hz /= 2.0
        return hz
    }

    /**
     * A plucked string, for tuning by ear: a burst of noise going round a short loop, which is near enough what a string
     * does with a pick. Each trip takes one period, and the loop's small losses dull it as a string dulls.
     */
    fun pluck(hz: Double, electric: Boolean): ShortArray {
        val soften = if (electric) 0.28 else 0.46
        val ringFor = if (electric) 5.5 else 3.8
        val trip = RATE / hz
        val wanted = trip - soften
        val whole = floor(wanted - 0.25).toInt()
        val rest = wanted - whole
        val allpass = ((1.0 - rest) / (1.0 + rest)).toFloat()
        val keep = 10.0.pow(-3.0 * trip / (ringFor * RATE)).toFloat()

        // The pick: a burst of noise as long as the loop, dulled, with the notch a pick's place on the string makes.
        val noise = Random(hz.toLong())
        val line = FloatArray(whole) { noise.nextFloat() * 2f - 1f }
        val dull = if (electric) 0.35f else 0.6f
        repeat(2) {
            var last = 0f
            for (i in line.indices) {
                last = line[i] * (1f - dull) + last * dull
                line[i] = last
            }
        }
        val pickedAt = max(1, (whole * 0.16).toInt())
        val first = line.copyOf()
        for (i in line.indices) line[i] = first[i] - first[(i + pickedAt) % whole]

        val out = FloatArray(samples(min(3.2, ringFor)))
        var at = 0
        var before = 0f
        var apIn = 0f
        var apOut = 0f
        val a = soften.toFloat()
        for (n in out.indices) {
            val y = line[at]
            out[n] = y
            val smooth = (1f - a) * y + a * before
            before = y
            val tuned = allpass * smooth + apIn - allpass * apOut
            apIn = smooth
            apOut = tuned
            line[at] = keep * tuned
            at = if (at + 1 == whole) 0 else at + 1
        }
        // Stays quiet enough not to overload the little speaker on a low string's first swing.
        return shorts(out, 0.7f)
    }

    private fun samples(seconds: Double) = (seconds * RATE).toInt()

    /** Adds one strike of the bell to [mix], starting at [at]. [ring] stretches how long it sounds. */
    private fun strike(mix: FloatArray, at: Int, hz: Double, loud: Float, ring: Float) {
        val rise = samples(0.0012).toFloat()
        for (p in PARTIALS.indices step 3) {
            val ratio = PARTIALS[p]
            val level = PARTIALS[p + 1] * loud / 2.0
            val lasts = PARTIALS[p + 2] * ring
            val fall = exp(-1.0 / (lasts * RATE))
            for (detune in SHIMMER) {
                val f = hz * ratio * detune
                if (f > RATE * 0.42) continue
                // A sine by rotation: two multiplies a sample, where calling sin() would cost far more.
                val w = 2.0 * PI * f / RATE
                val k = 2.0 * cos(w)
                var y1 = sin(-w)
                var y2 = sin(-2.0 * w)
                var env = level
                var i = at
                var n = 0
                while (i < mix.size && env > 0.00003) {
                    val y = k * y1 - y2
                    y2 = y1
                    y1 = y
                    val shaped = if (n < rise) env * n / rise else env
                    mix[i] += (y * shaped).toFloat()
                    env *= fall
                    i++
                    n++
                }
            }
        }
    }

    /** The room: a little of the bell comes back for most of a second after it's struck. */
    private fun withAir(dry: FloatArray): FloatArray {
        val wet = FloatArray(dry.size)
        for (i in COMBS.indices) {
            val delay = samples(COMBS[i])
            val feedback = 10.0.pow(-3.0 * COMBS[i] / AIR_SECONDS).toFloat()
            val line = FloatArray(delay)
            var at = 0
            for (n in dry.indices) {
                val y = line[at]
                line[at] = dry[n] + y * feedback
                at = if (at + 1 == delay) 0 else at + 1
                wet[n] += y
            }
        }
        for (seconds in DIFFUSERS) {
            val delay = samples(seconds)
            val line = FloatArray(delay)
            var at = 0
            for (n in wet.indices) {
                val held = line[at]
                val v = wet[n]
                val y = -0.6f * v + held
                line[at] = v + 0.6f * y
                at = if (at + 1 == delay) 0 else at + 1
                wet[n] = y
            }
        }
        val out = FloatArray(dry.size)
        for (n in dry.indices) out[n] = dry[n] + wet[n] * AIR_LEVEL
        return out
    }

    private fun finished(mix: FloatArray): ShortArray {
        val out = withAir(mix)
        // Fades the last moments away, so a sound cut short by the end of its buffer can't click.
        val fade = samples(0.25)
        for (i in 0 until fade) out[out.size - 1 - i] *= i.toFloat() / fade
        return shorts(out, 0.85f)
    }

    private fun shorts(x: FloatArray, peak: Float): ShortArray {
        var top = 1e-6f
        for (v in x) top = max(top, abs(v))
        val gain = peak / top * 32767f
        return ShortArray(x.size) { (x[it] * gain).toInt().toShort() }
    }

    /** Down a fourth, as a ratio. */
    private const val FOURTH_BELOW = 0.74915353843834

    /** A bell below this hardly comes out of the phone, and one above it sounds thin. */
    private const val LOWEST_BELL = 620.0
    private const val HIGHEST_BELL = 2800.0

    /**
     * Each partial's pitch, level and how long it sounds. The first four are in tune with each other, which is what keeps
     * it sweet. The last two aren't: they're there for a few hundredths of a second and make the "tink" of the strike.
     */
    private val PARTIALS = doubleArrayOf(
        1.00, 1.00, 0.42,
        2.00, 0.40, 0.27,
        3.00, 0.13, 0.16,
        4.00, 0.20, 0.10,
        5.43, 0.08, 0.035,
        8.12, 0.05, 0.018,
    )

    /** Two strikers a hair apart in pitch: their slow beat is the shimmer a real bell has. */
    private val SHIMMER = doubleArrayOf(0.99948, 1.00052)

    private val COMBS = doubleArrayOf(0.0297, 0.0371, 0.0411, 0.0437)
    private val DIFFUSERS = doubleArrayOf(0.005, 0.0017)
    private const val AIR_SECONDS = 0.8
    private const val AIR_LEVEL = 0.09f
}
