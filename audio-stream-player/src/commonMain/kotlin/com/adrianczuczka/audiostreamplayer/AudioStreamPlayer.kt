package com.adrianczuczka.audiostreamplayer

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A player for raw PCM audio streams.
 *
 * Designed for feeding audio as it arrives from a TTS or realtime voice API:
 *
 * ```kotlin
 * val player = AudioStreamPlayer(sampleRate = 24000)
 * player.play()
 * ttsResponse.collect { chunk -> player.feed(chunk) }
 * player.endOfStream() // suspends until the last sample has played
 * player.dispose()
 * ```
 *
 * Chunks may be any length — frame alignment across chunk boundaries is
 * handled internally. Buffering is unbounded; use [bufferedDuration] for
 * backpressure if the source can outrun playback indefinitely.
 *
 * Playback events arrive on [state] and [events]; they may be emitted from a
 * background thread. Control methods are not designed for concurrent calls
 * from multiple threads — drive the player from a single thread or coroutine
 * context, as you typically would.
 *
 * @property sampleRate Sample rate of the fed PCM data, in hertz. It does not
 *   need to match the device's output; resampling is handled natively.
 * @property channels Number of interleaved channels in the fed PCM data.
 * @property format Sample encoding of the fed PCM data.
 * @param configureAudioSession On iOS, configure the audio session for
 *   playback (category `playback`, mode `spokenAudio`) and activate it on
 *   [play]. Pass false if you manage the session yourself. Ignored on Android
 *   and macOS.
 */
public class AudioStreamPlayer(
    public val sampleRate: Int,
    public val channels: Int = 1,
    public val format: PcmFormat = PcmFormat.S16LE,
    configureAudioSession: Boolean = true,
) {
    init {
        require(sampleRate in 4000..384000) { "sampleRate out of range: $sampleRate" }
        require(channels in 1..2) { "channels must be 1 or 2: $channels" }
    }

    private val manageSession = configureAudioSession

    private val _state = MutableStateFlow(PlayerState.IDLE)

    /** The current playback state, as a hot observable flow. */
    public val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AudioStreamPlayerEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Underrun and error events. See [AudioStreamPlayerEvent]. */
    public val events: SharedFlow<AudioStreamPlayerEvent> = _events.asSharedFlow()

    private val aligner = FrameAligner(channels * format.bytesPerSample)

    @Volatile
    private var eosPending = false

    @Volatile
    private var drainDeferred: CompletableDeferred<Unit>? = null

    @Volatile
    private var disposed = false

    private val platform: PlatformPcmPlayer

    init {
        if (manageSession) configurePlaybackAudioSession()
        platform = PlatformPcmPlayer(
            sampleRate = sampleRate,
            channels = channels,
            isFloat = format == PcmFormat.F32LE,
            emit = ::onPlatformEvent,
        )
    }

    /**
     * Appends a chunk of PCM bytes to the playback buffer.
     *
     * [chunk] must match the [format], [sampleRate] and [channels] the player
     * was created with. Chunks may split frames at any byte boundary; a
     * trailing partial frame is held back and prepended to the next chunk.
     *
     * @throws IllegalStateException if called between [endOfStream] and its
     *   completion, or after [dispose].
     */
    public fun feed(chunk: ByteArray) {
        checkNotDisposed()
        check(!eosPending) { "Cannot feed after endOfStream() until the stream drains" }
        val aligned = aligner.align(chunk)
        if (aligned.isEmpty()) return
        platform.feed(aligned)
    }

    /**
     * Starts or resumes playback of buffered audio.
     *
     * Safe to call before the first [feed]; the player plays silence-free from
     * the moment data arrives.
     */
    public fun play() {
        checkNotDisposed()
        if (manageSession) activatePlaybackAudioSession()
        platform.play()
    }

    /** Pauses playback, retaining buffered audio. */
    public fun pause() {
        checkNotDisposed()
        platform.pause()
    }

    /**
     * Stops playback and discards all buffered audio.
     *
     * A pending [endOfStream] call completes (its audio will never play).
     */
    public fun stop() {
        checkNotDisposed()
        aligner.reset()
        eosPending = false
        platform.stop()
        completeDrain()
    }

    /** Sets playback volume, from 0.0 (silent) to 1.0 (full). */
    public fun setVolume(volume: Float) {
        checkNotDisposed()
        platform.setVolume(volume.coerceIn(0f, 1f))
    }

    /** How much fed audio is buffered but not yet played. */
    public fun bufferedDuration(): Duration {
        checkNotDisposed()
        return platform.bufferedDurationMicros().microseconds
    }

    /**
     * Signals that no more chunks will be fed for the current stream, and
     * suspends until every buffered sample has been played (or the player is
     * stopped first). Afterwards the player is [PlayerState.IDLE] and can be
     * fed a new stream.
     *
     * A held-back partial frame from an unaligned final chunk is discarded.
     */
    public suspend fun endOfStream() {
        checkNotDisposed()
        drainDeferred?.let { existing ->
            if (eosPending) {
                existing.await()
                return
            }
        }
        aligner.reset()
        val deferred = CompletableDeferred<Unit>()
        drainDeferred = deferred
        eosPending = true
        platform.endOfStream()
        deferred.await()
    }

    /** Releases native resources. The player cannot be used afterwards. */
    public fun dispose() {
        if (disposed) return
        disposed = true
        completeDrain()
        platform.release()
    }

    private fun onPlatformEvent(event: PlatformEvent) {
        when (event) {
            is PlatformEvent.StateChanged -> _state.value = event.state
            PlatformEvent.Underrun -> _events.tryEmit(AudioStreamPlayerEvent.Underrun)
            PlatformEvent.Drained -> completeDrain()
            is PlatformEvent.Error ->
                _events.tryEmit(AudioStreamPlayerEvent.Error(event.message))
        }
    }

    private fun completeDrain() {
        eosPending = false
        val deferred = drainDeferred
        drainDeferred = null
        deferred?.complete(Unit)
    }

    private fun checkNotDisposed() {
        check(!disposed) { "AudioStreamPlayer has been disposed" }
    }
}
