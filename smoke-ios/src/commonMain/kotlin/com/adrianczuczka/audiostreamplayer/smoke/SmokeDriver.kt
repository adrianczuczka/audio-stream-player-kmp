package com.adrianczuczka.audiostreamplayer.smoke

import com.adrianczuczka.audiostreamplayer.AudioStreamPlayer
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SAMPLE_RATE = 24_000
private const val FRAMES_PER_CHUNK = SAMPLE_RATE / 10 // 100ms

/**
 * Runs the full smoke sequence — tone, underrun, pause/resume — against the
 * library exactly as a Kotlin consumer would, reporting progress through a
 * Swift-friendly callback.
 */
class SmokeDriver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun run(log: (String) -> Unit) {
        scope.launch {
            val player = AudioStreamPlayer(sampleRate = SAMPLE_RATE)
            launch { player.state.collect { log("state: $it") } }
            launch { player.events.collect { log("EVENT: $it") } }
            log("player created: ${SAMPLE_RATE}Hz mono s16le")

            log("--- tone test: play, feed 20x100ms at 2x realtime ---")
            player.play()
            repeat(20) { i ->
                player.feed(sineChunk(i * FRAMES_PER_CHUNK))
                delay(50)
            }
            log("buffered=${player.bufferedDuration()}")
            player.endOfStream()
            log("tone test drained")
            delay(1000)

            log("--- underrun test: 100ms chunks every 400ms ---")
            player.play()
            repeat(6) { i ->
                player.feed(sineChunk(i * FRAMES_PER_CHUNK))
                delay(400)
            }
            player.endOfStream()
            log("underrun test drained")
            delay(1000)

            log("--- pause/resume test ---")
            player.play()
            repeat(15) { i -> player.feed(sineChunk(i * FRAMES_PER_CHUNK)) }
            delay(400)
            player.pause()
            log("paused for 1s")
            delay(1000)
            player.play()
            player.endOfStream()
            log("pause/resume test drained")

            player.dispose()
            log("ALL TESTS DONE")
        }
    }

    /** 440Hz sine, s16le mono, phase-continuous across chunks. */
    private fun sineChunk(startFrame: Int): ByteArray {
        val bytes = ByteArray(FRAMES_PER_CHUNK * 2)
        for (i in 0 until FRAMES_PER_CHUNK) {
            val sample =
                (sin(2.0 * PI * 440.0 * (startFrame + i) / SAMPLE_RATE) * 12_000).toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
