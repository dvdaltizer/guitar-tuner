package com.slowpixels.guitartuner.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Sound from a file in place of the mic, for trying the tuner where there's no guitar and no mic (the simulator).
 * Raw mono 16-bit little-endian PCM at 48 kHz, played at the mic's pace and round again when it ends.
 */
object Feed {
    const val FILE = "test-feed.pcm"
    private const val BLOCK = 1024

    fun file(file: File): Flow<ShortArray> = flow {
        val bytes = file.readBytes()
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        if (samples.isEmpty()) return@flow
        var at = 0
        while (true) {
            val n = minOf(BLOCK, samples.size - at)
            emit(samples.copyOfRange(at, at + n))
            at += n
            if (at >= samples.size) at = 0
            delay(n * 1000L / Ear.RATE)
        }
    }
}
