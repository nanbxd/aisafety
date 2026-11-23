package kz.safetyhack.audiotester

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded remote PCM to mono PCM16, with averaging to suppress aliasing during downsampling. */
object RemotePcm {
    fun to16k(input: ByteBuffer, rate: Int, channels: Int, frames: Int): ByteArray {
        if (rate < 16000 || channels !in 1..8 || frames <= 0) return ByteArray(0)
        val data = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val available = minOf(frames, data.remaining() / (channels * 2))
        val count = (available.toLong() * 16000 / rate).toInt()
        val output = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        val base = data.position()
        repeat(count) { index ->
            val start = index * rate / 16000
            val end = minOf(available, (index + 1) * rate / 16000)
            var sum = 0L
            for (frame in start until end) for (channel in 0 until channels) sum += data.getShort(base + (frame * channels + channel) * 2)
            output.putShort((sum / ((end - start) * channels)).toInt().coerceIn(-32768, 32767).toShort())
        }
        return output.array()
    }
}
