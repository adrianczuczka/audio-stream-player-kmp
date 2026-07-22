package com.adrianczuczka.audiostreamplayer

/** Out-of-band events reported by an [AudioStreamPlayer] via [AudioStreamPlayer.events]. */
public sealed interface AudioStreamPlayerEvent {
    /**
     * The player ran out of buffered audio while [PlayerState.PLAYING].
     *
     * Playback continues automatically when more data is fed; this event is
     * for showing buffering UI or tuning chunk delivery. Not fired for the
     * expected buffer exhaustion after [AudioStreamPlayer.endOfStream].
     */
    public data object Underrun : AudioStreamPlayerEvent

    /** A platform error reported outside of a method call. */
    public data class Error(public val message: String) : AudioStreamPlayerEvent
}
