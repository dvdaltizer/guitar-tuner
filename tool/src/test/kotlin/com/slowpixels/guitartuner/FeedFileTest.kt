package com.slowpixels.guitartuner

import com.slowpixels.guitartuner.model.Tunings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/**
 * Writes the sound for trying the tuner in the simulator, which has no mic: a tuning session on a standard-tuned
 * guitar, each string first a little flat and then in tune, as raw 16-bit PCM for `Feed`. Only when FEED_OUT names
 * the file to write:
 *
 *   FEED_OUT=/tmp/test-feed.pcm ./gradlew :tool:testDebugUnitTest --tests '*FeedFileTest'
 */
class FeedFileTest {
    @Test fun writeTheSession() {
        val out = System.getenv("FEED_OUT") ?: return
        val hz = Tunings.standard.hz(440.0)
        val parts = ArrayList<FloatArray>()
        parts += Plucks.silence(1.0)
        for (i in 0 until 6) {
            parts += Plucks.heard(hz[i], -14.0, seconds = 1.3, seed = i.toLong())
            parts += Plucks.silence(0.5)
            parts += Plucks.heard(hz[i], 8.0, seconds = 1.0, seed = i + 10L)
            parts += Plucks.silence(0.5)
            parts += Plucks.heard(hz[i], 0.0, seconds = 1.6, seed = i + 20L)
            parts += Plucks.silence(2.5)
        }
        parts += Plucks.silence(4.0)
        val all = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { Plucks.shorts(p).copyInto(all, at); at += p.size }
        val buf = ByteBuffer.allocate(all.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(all)
        File(out).writeBytes(buf.array())
        println("Wrote ${all.size / 48_000} s to $out")
    }
}
