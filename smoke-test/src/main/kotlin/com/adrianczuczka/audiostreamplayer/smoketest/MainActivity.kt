package com.adrianczuczka.audiostreamplayer.smoketest

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.adrianczuczka.audiostreamplayer.AudioStreamPlayer
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SmokeTest"
private const val SAMPLE_RATE = 24_000

/**
 * Manual smoke test for the audio-stream-player library. Each button logs to
 * both the screen and logcat under the "SmokeTest" tag so runs can be
 * verified without audio (though the point is to listen).
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: AudioStreamPlayer
    private lateinit var log: TextView
    private var feedJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = AudioStreamPlayer(sampleRate = SAMPLE_RATE)

        log = TextView(this).apply { setPadding(24, 24, 24, 24) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addButton("PLAY TONE 2S") { playTone() }
            addButton("UNDERRUN TEST") { underrunTest() }
            addButton("PAUSE") { player.pause(); append("pause() called") }
            addButton("RESUME") { player.play(); append("play() called") }
            addButton("STOP") {
                feedJob?.cancel()
                player.stop()
                append("stop() called")
            }
            addView(ScrollView(this@MainActivity).apply { addView(log) })
        }
        setContentView(layout)

        scope.launch { player.state.collect { append("state: $it") } }
        scope.launch { player.events.collect { append("EVENT: $it") } }
        append("player created: ${SAMPLE_RATE}Hz mono s16le")
    }

    /** Feeds 2s of 440Hz sine in 100ms chunks faster than realtime, then drains. */
    private fun playTone() {
        feedJob?.cancel()
        feedJob = scope.launch {
            append("--- tone test: play, feed 20x100ms, endOfStream ---")
            player.play()
            for (i in 0 until 20) {
                player.feed(sineChunk(startFrame = i * FRAMES_PER_CHUNK))
                append("fed chunk ${i + 1}/20, buffered=${player.bufferedDuration()}")
                delay(50)
            }
            player.endOfStream()
            append("endOfStream() completed (drained)")
        }
    }

    /** Feeds 100ms chunks every 400ms — slower than realtime, so playback starves. */
    private fun underrunTest() {
        feedJob?.cancel()
        feedJob = scope.launch {
            append("--- underrun test: 100ms chunks every 400ms ---")
            player.play()
            for (i in 0 until 6) {
                player.feed(sineChunk(startFrame = i * FRAMES_PER_CHUNK))
                delay(400)
            }
            player.endOfStream()
            append("endOfStream() completed (drained)")
        }
    }

    private fun append(message: String) {
        Log.i(TAG, message)
        log.text = "${log.text}\n$message"
    }

    private fun LinearLayout.addButton(label: String, onClick: () -> Unit) {
        addView(Button(context).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        player.dispose()
    }

    private companion object {
        const val FRAMES_PER_CHUNK = SAMPLE_RATE / 10 // 100ms

        /** 440Hz sine, s16le mono, phase-continuous across chunks. */
        fun sineChunk(startFrame: Int): ByteArray {
            val bytes = ByteArray(FRAMES_PER_CHUNK * 2)
            for (i in 0 until FRAMES_PER_CHUNK) {
                val sample =
                    (sin(2.0 * PI * 440.0 * (startFrame + i) / SAMPLE_RATE) * 12_000)
                        .toInt()
                bytes[i * 2] = (sample and 0xFF).toByte()
                bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            return bytes
        }
    }
}
