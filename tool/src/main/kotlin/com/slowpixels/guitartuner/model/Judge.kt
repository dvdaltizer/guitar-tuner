package com.slowpixels.guitartuner.model

import kotlin.math.abs
import kotlin.math.ln

/** How far [hz] is from [target], in hundredths of a semitone. */
fun cents(hz: Double, target: Double) = 1200.0 * ln(hz / target) / ln(2.0)

/** What the screen should show. A fresh one is made for each change, so another thread can read it whole. */
class Dial(
    /** The string being tuned, thickest first, or -1. */
    @JvmField val string: Int,
    /** How far off, or NaN if nothing's been heard. */
    @JvmField val cents: Double,
    /** True once a string has held the note long enough to count. */
    @JvmField val inTune: Boolean,
    /** Which strings have reached their note, one bit each. */
    @JvmField val reached: Int,
    /** True while sound is coming in, for keeping the screen awake. */
    @JvmField val hearing: Boolean,
)

/** What the player should hear. */
enum class Chime { NONE, STRING, ALL_STRINGS }

/**
 * Decides, from each look at the sound, which string is being played, how far off it is, and when it's in tune.
 *
 * A string doesn't hold still. It rings a few cents sharp right after the pick leaves it, then settles, and it wanders a
 * little as it fades. So nothing here trusts one reading: it goes by the middle of the last few, and a string only counts
 * as in tune once it has stayed inside the window for [HOLD_MS].
 *
 * Plain Kotlin with no clock of its own: every call says what time it is, so the tests can play a whole tuning session
 * in a moment.
 */
class Judge {
    private var targets = DoubleArray(6) { 110.0 }
    private var locked = -1
    private var string = -1
    private var reached = 0
    private var armed = true
    private var inTune = false
    private var since = -1L
    private var driftSince = -1L
    private var lastSound = Long.MIN_VALUE / 2
    private var deafUntil = 0L
    private var noHighUntil = 0L
    private var wanted = -1
    private var wantedFor = 0

    private val recent = DoubleArray(RECENT)
    private var count = 0
    private val sorted = DoubleArray(RECENT)

    var dial = Dial(-1, Double.NaN, false, 0, false)
        private set

    /** Sets what each string should be. Forgets which strings were done, unless only the pitches moved. */
    fun tune(hz: DoubleArray, keepReached: Boolean = false) {
        targets = hz
        if (!keepReached) reached = 0
        reset()
        publish(false)
    }

    /** Listens for [string] only, or for whichever is played with -1. */
    fun lock(string: Int) {
        locked = string
        this.string = string
        reset()
        publish(false)
    }

    val lockedString get() = locked

    /** Stops listening for [ms], while the phone's own speaker is sounding. */
    fun deafen(now: Long, ms: Long, tailMs: Long = ms) {
        deafUntil = now + ms
        noHighUntil = now + tailMs
    }

    private fun reset() {
        count = 0
        since = -1L
        driftSince = -1L
        inTune = false
        armed = true
        wanted = -1
        wantedFor = 0
    }

    /** The mic has stopped: nothing's being heard, and the next sound starts afresh. */
    fun rest() {
        reset()
        if (locked < 0) string = -1
        lastSound = Long.MIN_VALUE / 2
        publish(false)
    }

    /** A new sound began: the string's been picked again, so it can earn its chime again. */
    fun onset() {
        reset()
    }

    /**
     * Takes one look at the sound. [hz] is null when nothing's playing. Returns the chime it has earned.
     */
    fun hear(now: Long, hz: Double?): Chime {
        if (now < deafUntil) return Chime.NONE
        var pitch = hz
        // The end of a chime is quiet, but it's high and pure, and on its own it would read as a note.
        if (pitch != null && now < noHighUntil && pitch > HIGHEST_STRING) pitch = null

        if (pitch == null) {
            val gone = now - lastSound
            if (gone > GAP_MS) {
                armed = true
                count = 0
                since = -1L
                driftSince = -1L
            }
            if (gone > REST_MS && (dial.string != locked || !dial.cents.isNaN() || dial.hearing)) {
                inTune = false
                if (locked < 0) string = -1
                publish(false)
            } else if (dial.hearing) publish(false, dial.cents)
            return Chime.NONE
        }
        lastSound = now

        val s = choose(pitch)
        if (s != string) {
            string = s
            reset()
        }
        val off = cents(fold(pitch, targets[s]), targets[s])
        push(off)
        val middle = middle()
        var chime = Chime.NONE

        if (abs(middle) <= WINDOW && abs(off) <= WINDOW + 2.0) {
            driftSince = -1L
            if (since < 0) since = now
            if (armed && count >= 3 && now - since >= HOLD_MS) {
                armed = false
                inTune = true
                val before = reached
                reached = reached or (1 shl s)
                val all = (1 shl targets.size) - 1
                chime = if (reached == all && before != all) Chime.ALL_STRINGS else Chime.STRING
            }
        } else {
            since = -1L
            if (abs(middle) > WINDOW + 1.5) inTune = false
            if (abs(middle) > LEFT_TUNE) {
                armed = true
                if (reached and (1 shl s) != 0) {
                    if (driftSince < 0) driftSince = now
                    if (now - driftSince >= HOLD_MS) reached = reached and (1 shl s).inv()
                }
            }
        }
        publish(true, middle)
        return chime
    }

    /** Which string this pitch belongs to. */
    private fun choose(hz: Double): Int {
        if (locked >= 0) return locked
        val pitch = inRange(hz)
        var nearest = 0
        var distance = Double.MAX_VALUE
        for (i in targets.indices) {
            val d = abs(cents(pitch, targets[i]))
            val fresh = reached and (1 shl i) == 0
            val nearestFresh = reached and (1 shl nearest) == 0
            // Two strings can want the same note: the one still to do comes first.
            if (d < distance - 0.5 || (abs(d - distance) <= 0.5 && fresh && !nearestFresh)) {
                distance = d
                nearest = i
            }
        }
        if (string < 0 || count == 0) {
            wanted = -1
            return nearest
        }
        if (nearest == string) {
            wanted = -1
            return string
        }
        // A change of string with no new pick is usually a wobble, or an octave slip on a dying note.
        val here = abs(cents(fold(pitch, targets[string]), targets[string]))
        if (here < STAY) return string
        if (nearest != wanted) {
            wanted = nearest
            wantedFor = 0
        }
        return if (++wantedFor >= 3) nearest else string
    }

    /** Brings harmonics and octave slips back among the open strings. */
    private fun inRange(hz: Double): Double {
        var pitch = hz
        val low = targets.min() / 1.45
        val high = targets.max() * 1.45
        while (pitch > high) pitch /= 2.0
        while (pitch < low) pitch *= 2.0
        return pitch
    }

    /**
     * Moves [hz] an octave towards [target] if that's where it belongs: a dying note can slip an octave, and a harmonic
     * sounds one up. Two octaves off is a different string, the low E played with the high E chosen.
     */
    private fun fold(hz: Double, target: Double) = when {
        hz > target * 1.5 -> hz / 2.0
        hz < target / 1.5 -> hz * 2.0
        else -> hz
    }

    private fun push(off: Double) {
        if (count < RECENT) {
            recent[count++] = off
        } else {
            System.arraycopy(recent, 1, recent, 0, RECENT - 1)
            recent[RECENT - 1] = off
        }
    }

    private fun middle(): Double {
        System.arraycopy(recent, 0, sorted, 0, count)
        java.util.Arrays.sort(sorted, 0, count)
        return if (count % 2 == 1) sorted[count / 2] else (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0
    }

    private fun publish(hearing: Boolean, off: Double = Double.NaN) {
        dial = Dial(string, off, inTune, reached, hearing)
    }

    companion object {
        /** Within this many cents counts as in tune. Few ears can tell, and a plucked string won't hold much closer. */
        const val WINDOW = 3.0

        /** How long a string has to stay in the window. */
        const val HOLD_MS = 280L

        /** Past this the string has left the window for real and can chime again. */
        private const val LEFT_TUNE = 7.0

        /** How much silence means the next sound is a new pick. */
        private const val GAP_MS = 180L

        /** How long the last reading stays up after the sound stops. */
        private const val REST_MS = 1500L

        /** The last few readings, about a fifth of a second. */
        private const val RECENT = 5

        /** Within this many cents of the current string, it stays the current string. */
        private const val STAY = 80.0

        /** Above any guitar's open strings: only a chime's tail reads this high. */
        private const val HIGHEST_STRING = 520.0
    }
}
