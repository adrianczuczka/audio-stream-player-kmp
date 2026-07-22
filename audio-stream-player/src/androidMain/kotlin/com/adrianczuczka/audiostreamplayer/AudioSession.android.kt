package com.adrianczuczka.audiostreamplayer

// Android has no equivalent of the iOS audio session; AudioTrack routing is
// governed by the AudioAttributes set on the track.
internal actual fun configurePlaybackAudioSession() {}

internal actual fun activatePlaybackAudioSession() {}
