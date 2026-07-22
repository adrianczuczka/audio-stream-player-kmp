package com.adrianczuczka.audiostreamplayer

// macOS has no AVAudioSession; output routing is system-managed.
internal actual fun configurePlaybackAudioSession() {}

internal actual fun activatePlaybackAudioSession() {}
