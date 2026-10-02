package app.earbud.companion

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WaveDataTest {
    private fun wave(): ByteArray {
        val b = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(40).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(24000).putInt(48000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(4).putShort(200).putShort(-200)
        return b.array()
    }
    @Test fun decodesSynthesizedPcmWithoutPlayingHeader() {
        val w = WaveData.parse(wave())
        assertEquals(24000, w.sampleRate); assertEquals(1, w.channels)
        assertEquals(16, w.bits); assertEquals(4, w.pcm.size)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMalformedChunkLength() {
        val bytes = wave(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Int.MAX_VALUE)
        WaveData.parse(bytes)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsupportedCompression() {
        val bytes = wave(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 3)
        WaveData.parse(bytes)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedVoiceFile() { WaveData.parse(wave().copyOf(45)) }
}
