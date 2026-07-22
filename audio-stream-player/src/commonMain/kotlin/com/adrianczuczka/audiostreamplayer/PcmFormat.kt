package com.adrianczuczka.audiostreamplayer

/** Sample encoding of the PCM bytes fed to an [AudioStreamPlayer]. */
public enum class PcmFormat(
    /** Size of a single sample of this format, in bytes. */
    public val bytesPerSample: Int,
) {
    /**
     * Signed 16-bit integers, little-endian, interleaved.
     *
     * This is what most TTS and realtime voice APIs emit (OpenAI, ElevenLabs,
     * Cartesia, Google, ...).
     */
    S16LE(2),

    /** 32-bit IEEE floats, little-endian, interleaved, in the range -1.0 to 1.0. */
    F32LE(4),
}
