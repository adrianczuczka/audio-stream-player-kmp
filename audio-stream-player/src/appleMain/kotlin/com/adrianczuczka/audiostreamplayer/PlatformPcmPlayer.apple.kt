package com.adrianczuczka.audiostreamplayer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.Foundation.NSError
import platform.Foundation.NSRecursiveLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Streams raw PCM chunks through an `AVAudioPlayerNode`.
 *
 * Fed chunks are converted to the node's float32 deinterleaved format and
 * scheduled back-to-back, which `AVAudioPlayerNode` plays gaplessly. The
 * engine's mixer resamples from the stream's rate to the hardware rate.
 *
 * Unlike the Flutter plugin's Swift implementation (main-thread confined via
 * the method channel), callers may be on any thread here, so state is guarded
 * by a recursive lock. Buffer completions hop to the main queue before taking
 * it: player-node commands serialize through AVFAudio's completion queue, so
 * a drain's `node.stop()` issued from inside the callback deadlocks. The lock
 * stays recursive because `node.stop()` can still fire completion handlers
 * synchronously on the stopping thread; generation checks make those no-ops.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal actual class PlatformPcmPlayer actual constructor(
    private val sampleRate: Int,
    private val channels: Int,
    private val isFloat: Boolean,
    private val emit: (PlatformEvent) -> Unit,
) {
    private val engine = AVAudioEngine()
    private val node = AVAudioPlayerNode()
    private val format: AVAudioFormat = AVAudioFormat(
        commonFormat = AVAudioPCMFormatFloat32,
        sampleRate = sampleRate.toDouble(),
        channels = channels.toUInt(),
        interleaved = false,
    )

    private val lock = NSRecursiveLock()

    // All of the below are guarded by [lock].
    private var playing = false
    private var paused = false
    private var eosPending = false
    private var underrunReported = false
    private var framesScheduled = 0L
    private var framesCompleted = 0L
    private var buffersPending = 0
    // Bumped on stop/dispose so completions from cleared buffers are ignored.
    private var generation = 0L

    init {
        engine.attachNode(node)
        engine.connect(node, to = engine.mainMixerNode, format = format)
        engine.prepare()
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    actual fun feed(data: ByteArray) {
        val buffer = makeBuffer(data)
        val frames = buffer.frameLength.toLong()
        val myGeneration = locked {
            underrunReported = false
            buffersPending += 1
            framesScheduled += frames
            generation
        }
        node.scheduleBuffer(
            buffer,
            atTime = null,
            options = 0u,
            completionCallbackType = AVAudioPlayerNodeCompletionDataPlayedBack,
        ) { _ ->
            dispatch_async(dispatch_get_main_queue()) {
                bufferPlayedBack(frames = frames, bufferGeneration = myGeneration)
            }
        }
    }

    actual fun play() {
        val changed = locked {
            if (playing && !paused) return@locked false
            if (!engine.running) startEngine()
            node.play()
            playing = true
            paused = false
            true
        }
        if (changed) emit(PlatformEvent.StateChanged(PlayerState.PLAYING))
    }

    actual fun pause() {
        val changed = locked {
            if (!playing || paused) return@locked false
            node.pause()
            paused = true
            true
        }
        if (changed) emit(PlatformEvent.StateChanged(PlayerState.PAUSED))
    }

    actual fun stop() {
        val wasActive = locked {
            val was = playing
            resetLocked()
            was
        }
        if (wasActive) emit(PlatformEvent.StateChanged(PlayerState.IDLE))
    }

    actual fun endOfStream() {
        val drainNow = locked {
            eosPending = true
            buffersPending == 0
        }
        if (drainNow) drain()
    }

    actual fun setVolume(volume: Float) {
        node.volume = volume
    }

    actual fun bufferedDurationMicros(): Long = locked {
        // Completion-count based: overestimates by the not-yet-finished part
        // of the currently playing chunk, which is fine for a gauge and stays
        // correct across underruns (unlike playerTime, which keeps advancing
        // through starvation silence).
        val frames = (framesScheduled - framesCompleted).coerceAtLeast(0)
        frames * 1_000_000L / sampleRate
    }

    actual fun release() {
        locked { resetLocked() }
        engine.stop()
        engine.detachNode(node)
    }

    private fun resetLocked() {
        generation += 1
        node.stop()
        playing = false
        paused = false
        eosPending = false
        underrunReported = false
        framesScheduled = 0
        framesCompleted = 0
        buffersPending = 0
    }

    private fun bufferPlayedBack(frames: Long, bufferGeneration: Long) {
        var doDrain = false
        var doUnderrun = false
        locked {
            if (bufferGeneration != generation) return
            framesCompleted += frames
            buffersPending -= 1
            if (buffersPending > 0) return
            if (eosPending) {
                doDrain = true
            } else if (playing && !paused && !underrunReported) {
                underrunReported = true
                doUnderrun = true
            }
        }
        if (doDrain) drain()
        if (doUnderrun) emit(PlatformEvent.Underrun)
    }

    private fun drain() {
        val wasActive = locked {
            val was = playing
            resetLocked()
            was
        }
        if (wasActive) emit(PlatformEvent.StateChanged(PlayerState.IDLE))
        emit(PlatformEvent.Drained)
    }

    private fun startEngine() {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!engine.startAndReturnError(error.ptr)) {
                throw IllegalStateException(
                    "Audio engine failed to start: ${error.value?.localizedDescription}"
                )
            }
        }
    }

    private fun makeBuffer(data: ByteArray): AVAudioPCMBuffer {
        val bytesPerSample = if (isFloat) 4 else 2
        val frameCount = data.size / (bytesPerSample * channels)
        require(frameCount > 0) { "Fed chunk smaller than one frame" }
        val buffer = AVAudioPCMBuffer(
            pCMFormat = format,
            frameCapacity = frameCount.toUInt(),
        )
        buffer.frameLength = frameCount.toUInt()
        val dst = buffer.floatChannelData
            ?: throw IllegalStateException("Could not allocate PCM buffer")
        data.usePinned { pinned ->
            if (isFloat) {
                val src = pinned.addressOf(0).reinterpret<FloatVar>()
                for (frame in 0 until frameCount) {
                    for (ch in 0 until channels) {
                        dst[ch]!![frame] = src[frame * channels + ch]
                    }
                }
            } else {
                val src = pinned.addressOf(0).reinterpret<ShortVar>()
                val scale = 1.0f / 32768.0f
                for (frame in 0 until frameCount) {
                    for (ch in 0 until channels) {
                        dst[ch]!![frame] = src[frame * channels + ch] * scale
                    }
                }
            }
        }
        return buffer
    }
}
