package com.pcremote.modes

import com.pcremote.agent.Msg
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay

/** Binary file chunk frames: `[0x01][16-byte uuid][8-byte offset BE][data]` (docs/protocol.md). */
object FileFraming {
    const val MAX_CHUNK = 256 * 1024
    private const val HEADER = 1 + 16 + 8

    class Chunk(val id: UUID, val offset: Long, val data: ByteArray)

    fun chunk(id: UUID, offset: Long, data: ByteArray, len: Int): ByteArray =
        ByteBuffer.allocate(HEADER + len).put(1).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            .putLong(offset).put(data, 0, len).array()

    fun parse(b: ByteArray): Chunk? {
        if (b.size < HEADER || b[0].toInt() != 1) return null
        val bb = ByteBuffer.wrap(b, 1, b.size - 1)
        val id = UUID(bb.long, bb.long)
        val off = bb.long
        return Chunk(id, off, b.copyOfRange(HEADER, b.size))
    }
}

private fun hex(d: ByteArray) = d.joinToString("") { "%02x".format(it) }

/**
 * Phone → PC upload. Announces the file, waits for the host's resume offset ([awaitReady]),
 * streams from there, and ends with the SHA-256 of the whole file.
 */
class FileSender(
    val id: UUID,
    val name: String,
    val size: Long,
    private val open: () -> InputStream,
    private val send: (Msg) -> Unit,
    private val sendBinary: (ByteArray) -> Boolean,
    private val onProgress: (Float) -> Unit = {},
    /** Bytes still queued on the socket; uploads pause above [MAX_BACKLOG] so the socket never overflows. */
    private val backlog: () -> Long = { 0 },
) {
    suspend fun run(awaitReady: suspend () -> Long) {
        send(Msg.FileBegin(id.toString(), name, size))
        val offset = awaitReady().coerceIn(0, size)
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(FileFraming.MAX_CHUNK)
        var pos = 0L
        open().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
                val end = pos + n
                if (end > offset) {
                    val skip = (offset - pos).coerceAtLeast(0).toInt() // bytes the host already has
                    while (backlog() > MAX_BACKLOG) delay(10)
                    if (!sendBinary(FileFraming.chunk(id, pos + skip, buf.copyOfRange(skip, n), n - skip))) {
                        throw java.io.IOException("connection closed")
                    }
                    onProgress(end.toFloat() / size.coerceAtLeast(1))
                }
                pos = end
            }
        }
        send(Msg.FileEnd(id.toString(), hex(md.digest())))
    }

    private companion object { const val MAX_BACKLOG = 4L * 1024 * 1024 }
}

/** PC → phone download: writes chunks in order and checks the final hash. */
class FileReceiver(val id: UUID, private val size: Long, private val out: OutputStream) {
    private val md = MessageDigest.getInstance("SHA-256")
    private var written = 0L

    val progress get() = if (size == 0L) 1f else written.toFloat() / size

    /** Chunks arrive in order over one socket; anything else is ignored. */
    fun onChunk(c: FileFraming.Chunk) {
        if (c.id != id || c.offset != written) return
        out.write(c.data); md.update(c.data)
        written += c.data.size
    }

    fun finish(sha256: String): Boolean {
        out.flush()
        return written == size && hex(md.digest()) == sha256.lowercase()
    }
}
