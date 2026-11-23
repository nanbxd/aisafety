package kz.safetyhack.audiotester

/** 100 ms at 16 kHz mono PCM16; stays below the server's message-rate ceiling. */
class PcmBatcher(private val size: Int = 3200) {
    private val buffer = ByteArray(size)
    private var used = 0
    @Synchronized fun append(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(size - used, bytes.size - offset)
            bytes.copyInto(buffer, used, offset, offset + count)
            used += count; offset += count
            if (used == size) { frames.add(buffer.copyOf()); used = 0 }
        }
        return frames
    }
}
