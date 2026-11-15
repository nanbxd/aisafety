package kz.safetyhack.audiotester

import java.io.File
import java.io.RandomAccessFile

/** RIFF PCM header is finalized only after capture stops. */
class WavFile(val file: File) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw")
    private var bytes = 0
    init { out.setLength(0); out.write(ByteArray(44)) }
    fun write(data: ByteArray, count: Int) { out.write(data, 0, count); bytes += count }
    private fun le(value: Int, width: Int) { repeat(width) { out.write(value ushr (8 * it) and 255) } }
    override fun close() {
        try {
            out.seek(0)
            out.writeBytes("RIFF"); le(36 + bytes, 4); out.writeBytes("WAVEfmt ")
            le(16, 4); le(1, 2); le(1, 2); le(16000, 4); le(32000, 4)
            le(2, 2); le(16, 2); out.writeBytes("data"); le(bytes, 4)
        } finally { out.close() }
    }
}
