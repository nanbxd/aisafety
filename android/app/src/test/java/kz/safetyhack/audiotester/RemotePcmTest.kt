package kz.safetyhack.audiotester

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RemotePcmTest {
    @Test fun stereo48kBecomesMono16kWithoutChangingInput() {
        val input = ByteBuffer.allocate(480 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(480) { input.putShort(1200); input.putShort(800) }
        input.flip()
        val converted = RemotePcm.to16k(input, 48000, 2, 480)
        assertEquals(320, converted.size)
        assertEquals(0, input.position())
        val output = ByteBuffer.wrap(converted).order(ByteOrder.LITTLE_ENDIAN)
        repeat(160) { assertEquals(1000, output.short.toInt()) }
    }
    @Test fun tenSecondsOfWebRtcFramesStayBelowServerRateLimit() {
        val batcher = PcmBatcher()
        val packets = (0 until 1000).flatMap { batcher.append(ByteArray(320) { 7 }) }
        assertEquals(100, packets.size)
        assertTrue(packets.all { it.size == 3200 && it.all { byte -> byte == 7.toByte() } })
    }
    @Test fun incompleteFrameWaitsForNextCallback() {
        val batcher = PcmBatcher()
        assertTrue(batcher.append(ByteArray(3100) { 1 }).isEmpty())
        val result = batcher.append(ByteArray(200) { 2 })
        assertEquals(1, result.size)
        assertEquals(1, result[0][3099].toInt())
        assertEquals(2, result[0][3100].toInt())
    }
    @Test fun invalidAudioIsNotForwarded() {
        assertEquals(0, RemotePcm.to16k(ByteBuffer.allocate(16), 8000, 1, 8).size)
        assertEquals(0, RemotePcm.to16k(ByteBuffer.allocate(16), 48000, 0, 8).size)
    }
}
