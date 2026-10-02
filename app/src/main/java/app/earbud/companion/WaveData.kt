package app.earbud.companion

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WaveData(val sampleRate: Int, val channels: Int, val bits: Int, val pcm: ByteArray) {
    companion object {
        fun read(file: File): WaveData = parse(file.readBytes())
        fun parse(bytes: ByteArray): WaveData {
            require(bytes.size in 44..16_000_000) { "지원하지 않는 음성 파일 크기" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
            require(tag(0) == "RIFF" && tag(8) == "WAVE") { "WAV 음성이 아닙니다" }
            var offset = 12
            var rate = 0; var channels = 0; var bits = 0; var format = 0
            var pcm: ByteArray? = null
            while (offset + 8 <= bytes.size) {
                val size = b.getInt(offset + 4)
                require(size >= 0 && size <= bytes.size - offset - 8) { "손상된 WAV 청크" }
                val start = offset + 8
                when (tag(offset)) {
                    "fmt " -> {
                        require(size >= 16)
                        format = b.getShort(start).toInt()
                        channels = b.getShort(start + 2).toInt()
                        rate = b.getInt(start + 4)
                        bits = b.getShort(start + 14).toInt()
                    }
                    "data" -> pcm = bytes.copyOfRange(start, start + size)
                }
                offset = start + size + size % 2
            }
            require(format == 1 && channels in 1..2 && bits in listOf(8, 16) && rate in 8000..96000)
            val data = requireNotNull(pcm)
            require(data.isNotEmpty() && data.size % (channels * bits / 8) == 0)
            return WaveData(rate, channels, bits, data)
        }
    }
}
