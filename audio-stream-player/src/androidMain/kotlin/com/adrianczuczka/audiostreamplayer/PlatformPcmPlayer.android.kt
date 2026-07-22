package com.adrianczuczka.audiostreamplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams raw PCM chunks through an [AudioTrack] in MODE_STREAM.
 *
 * Control methods may be called from any thread. A dedicated writer thread
 * drains the chunk queue into the track with non-blocking writes so
 * stop/dispose can never deadlock against a full track buffer.
 *
 * The track is recreated on every reset (stop, drain) instead of reusing a
 * flushed instance: `playbackHeadPosition` behavior after pause+flush is
 * device-dependent (it may keep its value or reset on restart), and a fresh
 * track is the only way to keep played-frame accounting trustworthy.
 */
internal actual class PlatformPcmPlayer actual constructor(
    private val sampleRate: Int,
    channels: Int,
    isFloat: Boolean,
    private val emit: (PlatformEvent) -> Unit,
) {
    private object EndOfStream

    private val bytesPerFrame = channels * (if (isFloat) 4 else 2)
    private val channelMask =
        if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    private val encoding =
        if (isFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT

    private val queue = LinkedBlockingQueue<Any>()
    private val queuedBytes = AtomicLong(0)
    private val lock = Any()

    private val writer: Thread

    // All of the below are guarded by [lock].
    private var track: AudioTrack
    private var playing = false
    private var paused = false
    private var released = false
    private var volume = 1.0f
    private var framesWritten = 0L
    private var underrunReported = false
    // Bumped on stop/dispose; the writer abandons in-flight work when it changes.
    private var generation = 0L

    // Writer-thread local: consecutive idle polls with a frozen head position.
    private var idleStallPolls = 0
    private var lastIdleHead = -1L

    init {
        track = buildTrack()
        writer = Thread({ writerLoop() }, "audio_stream_player-writer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun buildTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            throw IllegalArgumentException(
                "Unsupported PCM configuration: ${sampleRate}Hz, mask $channelMask"
            )
        }
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 4)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val newTrack = builder.build()
        if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
            newTrack.release()
            throw IllegalStateException("AudioTrack failed to initialize")
        }
        newTrack.setVolume(volume)
        return newTrack
    }

    actual fun feed(data: ByteArray) {
        synchronized(lock) {
            if (released) return
            underrunReported = false
        }
        queuedBytes.addAndGet(data.size.toLong())
        queue.put(data)
    }

    actual fun play() {
        synchronized(lock) {
            if (released || (playing && !paused)) return
            playing = true
            paused = false
            track.play()
        }
        emit(PlatformEvent.StateChanged(PlayerState.PLAYING))
    }

    actual fun pause() {
        synchronized(lock) {
            if (released || !playing || paused) return
            paused = true
            track.pause()
        }
        emit(PlatformEvent.StateChanged(PlayerState.PAUSED))
    }

    actual fun stop() {
        val wasActive: Boolean
        synchronized(lock) {
            if (released) return
            wasActive = playing
            resetLocked()
        }
        if (wasActive) emit(PlatformEvent.StateChanged(PlayerState.IDLE))
    }

    actual fun endOfStream() {
        synchronized(lock) {
            if (released) return
        }
        queue.put(EndOfStream)
    }

    actual fun setVolume(volume: Float) {
        synchronized(lock) {
            if (released) return
            this.volume = volume
            track.setVolume(this.volume)
        }
    }

    actual fun bufferedDurationMicros(): Long {
        val trackBufferedFrames: Long
        synchronized(lock) {
            if (released) return 0
            trackBufferedFrames = (framesWritten - playedFramesLocked()).coerceAtLeast(0)
        }
        // queuedBytes can transiently dip negative if the writer's decrement
        // races a stop() reset; clamp so callers never see a negative duration.
        val queuedFrames = queuedBytes.get().coerceAtLeast(0) / bytesPerFrame
        return (trackBufferedFrames + queuedFrames) * 1_000_000L / sampleRate
    }

    actual fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            generation++
            queue.clear()
            queuedBytes.set(0)
            track.release()
        }
        writer.interrupt()
        writer.join(1000)
    }

    /** Stops playback, discards buffered audio, and swaps in a fresh track. */
    private fun resetLocked() {
        generation++
        queue.clear()
        queuedBytes.set(0)
        playing = false
        paused = false
        underrunReported = false
        framesWritten = 0
        track.release()
        track = buildTrack()
    }

    private fun playedFramesLocked(): Long =
        track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    private fun writerLoop() {
        while (true) {
            val item = try {
                queue.poll(50, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            synchronized(lock) { if (released) return }
            when (item) {
                null -> checkUnderrun()
                is EndOfStream -> awaitPlayout()
                is ByteArray -> {
                    idleStallPolls = 0
                    lastIdleHead = -1
                    writeChunk(item)
                }
            }
        }
    }

    private fun writeChunk(data: ByteArray) {
        val myGeneration = synchronized(lock) { generation }
        var offset = 0
        while (offset < data.size) {
            val currentTrack: AudioTrack
            synchronized(lock) {
                if (released || generation != myGeneration) return
                currentTrack = track
            }
            val written = currentTrack.write(
                data, offset, data.size - offset, AudioTrack.WRITE_NON_BLOCKING
            )
            if (written < 0) {
                synchronized(lock) {
                    if (released || generation != myGeneration) return
                }
                emit(PlatformEvent.Error("AudioTrack write error $written"))
                return
            }
            if (written > 0) {
                offset += written
                queuedBytes.addAndGet(-written.toLong())
                synchronized(lock) { framesWritten += written / bytesPerFrame }
            } else {
                // Track buffer full (or paused): back off briefly.
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /**
     * Underrun detection: while playing with an empty queue, a head position
     * frozen across consecutive idle polls means the track ran dry. This is
     * deliberately not an exact framesWritten comparison — a starved track
     * can stall a few frames short of everything written.
     */
    private fun checkUnderrun() {
        val shouldEmit: Boolean
        synchronized(lock) {
            if (!playing || paused || framesWritten == 0L || underrunReported) {
                idleStallPolls = 0
                lastIdleHead = -1
                return
            }
            val head = playedFramesLocked()
            if (head == lastIdleHead) {
                idleStallPolls++
            } else {
                idleStallPolls = 0
            }
            lastIdleHead = head
            shouldEmit = idleStallPolls >= 2 && queue.isEmpty()
            if (shouldEmit) underrunReported = true
        }
        if (shouldEmit) emit(PlatformEvent.Underrun)
    }

    /** Blocks the writer until everything written has been played, then drains. */
    private fun awaitPlayout() {
        val myGeneration = synchronized(lock) { generation }
        var stallMs = 0
        var lastHead = -1L
        while (true) {
            val done: Boolean
            val activelyPlaying: Boolean
            val head: Long
            synchronized(lock) {
                if (released || generation != myGeneration) return
                activelyPlaying = playing && !paused
                head = playedFramesLocked()
                // Nothing written means nothing can ever play out: complete
                // immediately. Otherwise wait for active playback to finish.
                done = framesWritten == 0L || (activelyPlaying && head >= framesWritten)
            }
            if (done) break
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                return
            }
            // Safety valve against a dead output route: a head position that
            // stops advancing during active playback is a stall. Idle/paused
            // waits are legitimate (drain completes once the user resumes).
            if (activelyPlaying && head == lastHead) {
                stallMs += 20
                if (stallMs > 2_000) break
            } else {
                stallMs = 0
            }
            lastHead = head
        }
        val wasPlaying: Boolean
        synchronized(lock) {
            if (released || generation != myGeneration) return
            wasPlaying = playing
            resetLocked()
        }
        if (wasPlaying) emit(PlatformEvent.StateChanged(PlayerState.IDLE))
        emit(PlatformEvent.Drained)
    }
}
