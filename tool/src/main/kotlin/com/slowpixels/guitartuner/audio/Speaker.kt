package com.slowpixels.guitartuner.audio

import com.thelightphone.sdk.audio.LightAudioVoice
import java.util.concurrent.Executors

/**
 * Plays one sound at a time through Light's PCM voice. Sounds are made on a thread of their own, which takes the phone a
 * few hundredths of a second, and the voice's own write blocks, so none of it touches the screen's thread.
 */
class Speaker(private val voice: LightAudioVoice) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "speaker") }

    /** Makes a sound with [make] and plays it, over whatever was playing. */
    fun play(make: () -> ShortArray) {
        worker.execute {
            val sound = make()
            runCatching { voice.play(sound) }
        }
    }

    /** Stops the sound, as on leaving the tool. */
    fun stop() {
        worker.execute { runCatching { voice.stop() } }
    }
}
