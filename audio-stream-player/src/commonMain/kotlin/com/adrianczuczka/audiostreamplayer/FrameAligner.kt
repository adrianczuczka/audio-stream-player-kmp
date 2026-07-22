package com.adrianczuczka.audiostreamplayer

/**
 * Splits an arbitrary byte stream into whole PCM frames: a trailing partial
 * frame is held back and prepended to the next chunk.
 */
internal class FrameAligner(private val frameBytes: Int) {
    private var carry: ByteArray = ByteArray(0)

    /** Returns the frame-aligned prefix of carry + [chunk], possibly empty. */
    fun align(chunk: ByteArray): ByteArray {
        val data = if (carry.isEmpty()) chunk else carry + chunk
        val alignedLength = data.size - data.size % frameBytes
        carry = data.copyOfRange(alignedLength, data.size)
        return if (alignedLength == data.size) data else data.copyOfRange(0, alignedLength)
    }

    /** Discards any held-back partial frame. */
    fun reset() {
        carry = ByteArray(0)
    }
}
