package com.slowpixels.guitartuner

import android.os.SystemClock
import com.slowpixels.guitartuner.audio.Bell
import com.slowpixels.guitartuner.audio.Ear
import com.slowpixels.guitartuner.audio.Feed
import com.slowpixels.guitartuner.audio.Speaker
import com.slowpixels.guitartuner.model.Chime
import com.slowpixels.guitartuner.model.Settings
import com.slowpixels.guitartuner.model.Store
import com.slowpixels.guitartuner.model.Tuning
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.audio.CaptureConfig
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioCaptureException
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.MicSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll

/**
 * The tuner for the life of the process, so it outlives the screens: LightOS can drop and reopen a screen whenever it
 * likes. The mic runs only while the tuner's own screen is in front.
 */
object Hub {
    /** As in lighttool.toml. */
    const val VERSION = "1.1.0"

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ear = Ear { SystemClock.uptimeMillis() }
    val settings = MutableStateFlow(Settings())

    /** The string the mic listens for alone, thickest first, or -1 for whichever is played. */
    val held = MutableStateFlow(-1)

    /** Whether the mic is allowed: null until asked. */
    val micOk = MutableStateFlow<Boolean?>(null)

    /** Set when a tuning has just been chosen, so the lists close themselves. */
    var closeLists = false

    /** When the mic was last opened, by the same clock as the ear's, for keeping the screen awake. */
    @Volatile var listenedAt = 0L
        private set

    private var store: Store? = null
    private var audio: LightAudio? = null
    private var speaker: Speaker? = null
    private var filesDir: File? = null

    /** The tool's first screen is up. */
    fun attach(audio: LightAudio, context: SealedLightContext) {
        this.audio = audio
        filesDir = context.filesDir
        if (store == null) {
            val s = Store(File(context.filesDir, "tuner.properties"))
            store = s
            settings.value = s.load()
            ear.onChime = ::chime
            dress()
        }
        if (speaker == null) speaker = Speaker(audio.newVoice(LightAudioUsage.Music, Bell.RATE))
    }

    private fun save() { store?.save(settings.value) }

    /** Puts the saved guitar and tuning in the mic's ears. */
    private fun dress(keepReached: Boolean = false) {
        val s = settings.value
        ear.quiet = s.electric
        ear.tune(s.tuningNow.hz(s.a4.toDouble()), keepReached)
        ear.lock(held.value)
    }

    private fun hz() = settings.value.let { it.tuningNow.hz(it.a4.toDouble()) }

    // ---- the mic ----

    /** Opens the mic, once it's allowed. */
    fun listen() {
        if (ear.listening) return
        val a = audio ?: return
        listenedAt = SystemClock.uptimeMillis()
        ear.start(scope, testFeed() ?: capture(a))
    }

    /** Closes the mic and quiets the speaker, as on leaving the screen. */
    fun hush() {
        ear.stop()
        speaker?.stop()
    }

    /** Raw input first, which skips the phone's voice processing; a phone that can't give it falls back to the ordinary mic. */
    private fun capture(a: LightAudio): Flow<ShortArray> =
        a.newCapture(CaptureConfig(Ear.RATE, BLOCK, MicSource.Unprocessed)).asFlow().catch { e ->
            if (e !is LightAudioCaptureException) throw e
            emitAll(a.newCapture(CaptureConfig(Ear.RATE, BLOCK, MicSource.Mic)).asFlow())
        }

    /** In a debug build, a PCM file in the tool's storage plays in place of the mic (see [Feed]). */
    private fun testFeed(): Flow<ShortArray>? {
        if (!BuildConfig.DEBUG) return null
        val f = File(filesDir ?: return null, Feed.FILE)
        return if (f.isFile) Feed.file(f) else null
    }

    // ---- the chime ----

    /** Called on the mic's thread: it turns its deaf ear before the sound starts. */
    private fun chime(kind: Chime, string: Int) {
        if (!settings.value.chime) return
        val hz = hz()
        val s = speaker ?: return
        if (kind == Chime.ALL_STRINGS) {
            ear.deafen(FLOURISH_LOUD_MS, FLOURISH_MS)
            s.play { Bell.flourish(hz) }
        } else {
            ear.deafen(CHIME_LOUD_MS, CHIME_MS)
            s.play { Bell.chime(hz[string]) }
        }
    }

    // ---- the tuner's controls ----

    /** Tap a string to listen for it alone, and tap it again to listen for all of them. */
    fun choose(string: Int) {
        held.value = if (held.value == string) -1 else string
        ear.lock(held.value)
    }

    /** Hold a string to hear its note. */
    fun hear(string: Int) {
        val hz = hz().getOrNull(string) ?: return
        val electric = settings.value.electric
        ear.deafen(PLUCK_MS)
        speaker?.play { Bell.pluck(hz, electric) }
    }

    fun setGuitar(electric: Boolean) {
        settings.value = settings.value.copy(electric = electric)
        ear.quiet = electric
        save()
    }

    fun chooseTuning(t: Tuning) {
        settings.value = settings.value.choosing(t)
        held.value = -1
        dress()
        save()
        closeLists = true
    }

    fun choosePitch(hz: Int) {
        settings.value = settings.value.copy(a4 = hz)
        dress(keepReached = true)
        save()
    }

    fun setChime(on: Boolean) {
        settings.value = settings.value.copy(chime = on)
        save()
        // Turned on, it plays once, so you know what you've switched on.
        if (on) speaker?.play { Bell.chime(hz()[5]) }
    }

    /** Samples the mic hands over at a time: about 21 ms. */
    private const val BLOCK = 1024

    /** How long the phone stays awake with nothing to hear. */
    const val AWAKE_MS = 120_000L

    /** A chime's loud part, when the mic hears nothing at all, and its whole length, when it ignores high notes. */
    private const val CHIME_LOUD_MS = 450L
    private const val CHIME_MS = 1400L
    private const val FLOURISH_LOUD_MS = 900L
    private const val FLOURISH_MS = 2200L
    private const val PLUCK_MS = 3000L
}
