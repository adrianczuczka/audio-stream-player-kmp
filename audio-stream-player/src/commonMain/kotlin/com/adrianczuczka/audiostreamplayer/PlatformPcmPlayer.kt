package com.adrianczuczka.audiostreamplayer

/** Events emitted by the platform player, possibly from a non-main thread. */
internal sealed interface PlatformEvent {
    data class StateChanged(val state: PlayerState) : PlatformEvent
    data object Underrun : PlatformEvent
    data object Drained : PlatformEvent
    data class Error(val message: String) : PlatformEvent
}

/**
 * The native PCM streaming engine: AudioTrack on Android, AVAudioEngine +
 * AVAudioPlayerNode on Apple platforms.
 *
 * Receives only frame-aligned data — alignment is handled by
 * [AudioStreamPlayer] before it reaches this class.
 */
internal expect class PlatformPcmPlayer(
    sampleRate: Int,
    channels: Int,
    isFloat: Boolean,
    emit: (PlatformEvent) -> Unit,
) {
    fun feed(data: ByteArray)
    fun play()
    fun pause()
    fun stop()
    fun endOfStream()
    fun setVolume(volume: Float)
    fun bufferedDurationMicros(): Long
    fun release()
}

/**
 * Configures the platform audio session for playback (iOS: category
 * `playback`, mode `spokenAudio`). No-op on Android and macOS.
 */
internal expect fun configurePlaybackAudioSession()

/** Activates the platform audio session (iOS: `setActive(true)`). No-op elsewhere. */
internal expect fun activatePlaybackAudioSession()
