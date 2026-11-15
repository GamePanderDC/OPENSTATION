package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMicAdpcmTest {
    private fun leShortAt(bytes: ByteArray, sampleIndex: Int): Int {
        val lo = bytes[sampleIndex * 2].toInt() and 0xFF
        val hi = bytes[sampleIndex * 2 + 1].toInt()
        return (hi shl 8) or lo
    }

    @Test
    fun shortPacketReturnsEmpty() {
        val dec = RemoteMicStreamer.ImaAdpcmDecoder()
        assertEquals(0, dec.decodeBlockToPcmLeBytes(ByteArray(4), offset = 6).size)
        assertEquals(0, dec.decodeBlockToPcmLeBytes(ByteArray(6), offset = 6).size)
    }

    @Test
    fun decodesHighNibbleFirstAndResetsCleanly() {
        val dec = RemoteMicStreamer.ImaAdpcmDecoder()
        // 6-byte OmniRCU header + 1 ADPCM payload byte = 0x7F (hi=7 positive large step, lo=15 negative)
        // Initial step=7, curPred=0:
        //   hi=7 (sign=0, mag=7): diff = 0 + 7 + 3 + 1 = 11 -> pred=11, idx = min(88, 0+8)=8 (step=16)
        //   lo=15 (0x0F: sign=1, mag=7): diff = 2 + 16 + 8 + 4 = 30 -> pred = 11 - 30 = -19
        val packet = byteArrayOf(0, 0, 0, 0, 0, 0, 0x7F.toByte())
        val pcm1 = dec.decodeBlockToPcmLeBytes(packet, offset = 6)
        assertEquals(4, pcm1.size)
        assertEquals(11, leShortAt(pcm1, 0))
        assertEquals(-19, leShortAt(pcm1, 1))

        // Decoding another block preserves predictor state unless reset() is called.
        val pcmNext = dec.decodeBlockToPcmLeBytes(packet, offset = 6)
        assertTrue(leShortAt(pcmNext, 0) != 11)

        dec.reset()
        val pcmReset = dec.decodeBlockToPcmLeBytes(packet, offset = 6)
        assertEquals(11, leShortAt(pcmReset, 0))
        assertEquals(-19, leShortAt(pcmReset, 1))
    }
}
