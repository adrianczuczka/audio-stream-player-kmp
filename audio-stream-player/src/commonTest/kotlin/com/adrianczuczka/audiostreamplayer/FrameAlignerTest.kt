package com.adrianczuczka.audiostreamplayer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class FrameAlignerTest {
    // Stereo s16le: 4 bytes per frame.
    private val aligner = FrameAligner(4)

    @Test
    fun alignedChunkPassesThroughUnchanged() {
        val chunk = ByteArray(8) { it.toByte() }
        assertContentEquals(chunk, aligner.align(chunk))
    }

    @Test
    fun trailingPartialFrameIsHeldBackAndPrepended() {
        val first = ByteArray(6) { it.toByte() }
        assertContentEquals(byteArrayOf(0, 1, 2, 3), aligner.align(first))
        val second = ByteArray(6) { (10 + it).toByte() }
        // carry (4, 5) + second's first six bytes -> two whole frames.
        assertContentEquals(
            byteArrayOf(4, 5, 10, 11, 12, 13, 14, 15),
            aligner.align(second),
        )
    }

    @Test
    fun subFrameChunksAccumulateUntilAFrameCompletes() {
        assertTrue(aligner.align(byteArrayOf(0)).isEmpty())
        assertTrue(aligner.align(byteArrayOf(1, 2)).isEmpty())
        assertContentEquals(byteArrayOf(0, 1, 2, 3), aligner.align(byteArrayOf(3)))
    }

    @Test
    fun resetDiscardsHeldBackBytes() {
        aligner.align(byteArrayOf(0, 1, 2))
        aligner.reset()
        assertContentEquals(byteArrayOf(9, 8, 7, 6), aligner.align(byteArrayOf(9, 8, 7, 6)))
    }

    @Test
    fun emptyChunkYieldsEmpty() {
        assertTrue(aligner.align(ByteArray(0)).isEmpty())
    }
}
