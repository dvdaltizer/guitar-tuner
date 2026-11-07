package com.slowpixels.guitartuner.audio

import com.slowpixels.guitartuner.model.Chime
import com.slowpixels.guitartuner.model.Dial
import com.slowpixels.guitartuner.model.Judge
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Listens. The mic's sound comes in as a flow of 16-bit blocks (Light's `LightAudioCapture`, or a file in place of it);
 * about 23 times a second it looks for a note and passes what it finds to the [Judge]. Nothing it hears is kept: each
 * block is gone once the next one has pushed it out of [Pitch]'s short memory.
 *
 * A real string needs no more sensitivity than the room allows, so instead of a fixed level it keeps track of how quiet
 * the room gets, and counts anything well above that as sound. It has no clock of its own, so the tests can play a
 * session in a moment.
 */
class Ear(private val clock: () -> Long) {
    private val judge = Judge()
    private var job: Job? = null

    /** An unplugged electric is far quieter than an acoustic, so it gets a lower bar. */
    @Volatile var quiet = false

    /** Set if the mic couldn't be opened. */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> get() = _failed

    /** What the screen should show; a fresh one at every look. */
    private val _dial = MutableStateFlow(judge.dial)
    val dial: StateFlow<Dial> get() = _dial

    /** When, by [clock], sound was last coming in. */
    @Volatile var lastSound = 0L
        private set

    /** Called on the ear's own thread when a string earns its chime. */
    var onChime: (Chime, Int) -> Unit = { _, _ -> }

    val listening get() = job?.isActive == true

    /** Pitches to listen for, thickest string first. [keepReached] keeps the marks when only the reference moved. */
    fun tune(hz: DoubleArray, keepReached: Boolean = false) = synchronized(judge) { judge.tune(hz, keepReached); publish() }

    /** Listens for one string only, or whichever is played with -1. */
    fun lock(string: Int) = synchronized(judge) { judge.lock(string); publish() }

    /** Turns a deaf ear to the phone's own speaker for a while. */
    fun deafen(ms: Long, tailMs: Long = ms) = synchronized(judge) { judge.deafen(clock(), ms, tailMs) }

    /** Starts listening to [source]. Collecting it opens the mic; stopping closes it. Returns the listening, for tests to wait on. */
    fun start(scope: CoroutineScope, source: Flow<ShortArray>): Job? {
        job?.takeIf { it.isActive }?.let { return it }
        _failed.value = false
        val j = scope.launch(Dispatchers.Default) {
            val take = Take()
            try {
                source.collect(take::block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _failed.value = true
            } finally {
                rest()
            }
        }
        job = j
        return j
    }

    fun stop() {
        job?.cancel()
        job = null
        rest()
    }

    private fun rest() = synchronized(judge) { judge.rest(); publish() }

    private fun publish() { _dial.value = judge.dial }

    /** One session's listening state. Pitch's memory is only touched from the collecting thread. */
    private inner class Take {
        private val pitch = Pitch(RATE)
        private var floats = FloatArray(4096)
        private var pending = 0
        private var room = -1f
        private var slow = 0f
        private var lastPick = 0L
        private var wasHearing = false

        fun block(raw: ShortArray) {
            val n = raw.size
            if (floats.size < n) floats = FloatArray(n)
            for (i in 0 until n) floats[i] = raw[i] / 32768f
            pitch.push(floats, n)
            pending += n
            while (pending >= LOOK_EVERY) {
                pending -= LOOK_EVERY
                look()
            }
        }

        private fun look() {
            val now = clock()
            val level = pitch.level()

            // How quiet the room gets: down at once, back up slowly, so a long note doesn't count as the room.
            room = if (room < 0f || level < room) level else room * ROOM_RISE
            val bar = (min(room, LOUDEST_ROOM) * (if (quiet) ABOVE_ROOM_QUIET else ABOVE_ROOM)).coerceAtLeast(QUIETEST_SOUND)
            val sounding = level > bar

            val picked = sounding && level > slow * PICK_JUMP && now - lastPick > PICK_GAP_MS
            slow += (level - slow) * SLOW_FOLLOW
            if (picked) lastPick = now

            val heard = if (sounding) pitch.look()?.takeIf { it.clarity >= LEAST_CLARITY } else null
            val chime: Chime
            val string: Int
            val hearing: Boolean
            synchronized(judge) {
                if (picked) judge.onset()
                chime = judge.hear(now, heard?.hz)
                string = judge.dial.string
                hearing = judge.dial.hearing
                publish()
            }
            if (hearing) lastSound = now
            if (chime != Chime.NONE) onChime(chime, string)
            wasHearing = hearing
        }
    }

    companion object {
        const val RATE = 48_000

        /** A look every 2048 samples, about 43 ms, however the mic cuts its blocks. */
        private const val LOOK_EVERY = 2048

        /** The room's level creeps up by this at each look, about 2 dB in five seconds. */
        private const val ROOM_RISE = 1.0035f

        /** Sound has to be this far over the room, about 7 dB, or 4 dB for an electric. */
        private const val ABOVE_ROOM = 2.2f
        private const val ABOVE_ROOM_QUIET = 1.6f

        /** However loud the room is, a note this loud gets through. */
        private const val LOUDEST_ROOM = 0.02f

        /** Below this it's the mic's own hiss. */
        private const val QUIETEST_SOUND = 0.0004f

        /** A pick: the level jumps past this multiple of where it's been. */
        private const val PICK_JUMP = 1.6f
        private const val PICK_GAP_MS = 140L
        private const val SLOW_FOLLOW = 0.3f

        /** How clean a repeat has to be to count as a note. */
        private const val LEAST_CLARITY = 0.8f
    }
}
