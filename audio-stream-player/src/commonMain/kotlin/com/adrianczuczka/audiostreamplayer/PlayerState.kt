package com.adrianczuczka.audiostreamplayer

/** Playback state of an [AudioStreamPlayer]. */
public enum class PlayerState {
    /**
     * Not playing. The initial state, and the state after [AudioStreamPlayer.stop]
     * or after the buffer drains following [AudioStreamPlayer.endOfStream].
     */
    IDLE,

    /**
     * Consuming buffered audio. A starved player (buffer empty, waiting for
     * more chunks) is still [PLAYING]; listen for
     * [AudioStreamPlayerEvent.Underrun] to detect starvation.
     */
    PLAYING,

    /**
     * Paused. Buffered audio is retained and playback resumes where it left
     * off.
     */
    PAUSED,
}
