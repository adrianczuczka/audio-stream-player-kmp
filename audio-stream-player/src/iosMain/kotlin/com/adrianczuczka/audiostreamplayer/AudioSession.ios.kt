package com.adrianczuczka.audiostreamplayer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.setActive
import platform.Foundation.NSError

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal actual fun configurePlaybackAudioSession() {
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val ok = AVAudioSession.sharedInstance().setCategory(
            category = AVAudioSessionCategoryPlayback,
            mode = AVAudioSessionModeSpokenAudio,
            options = 0u,
            error = error.ptr,
        )
        if (!ok) {
            throw IllegalStateException(
                "Failed to configure audio session: ${error.value?.localizedDescription}"
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal actual fun activatePlaybackAudioSession() {
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val ok = AVAudioSession.sharedInstance().setActive(true, error = error.ptr)
        if (!ok) {
            throw IllegalStateException(
                "Failed to activate audio session: ${error.value?.localizedDescription}"
            )
        }
    }
}
