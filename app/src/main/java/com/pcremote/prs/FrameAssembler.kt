package com.pcremote.prs

/** A complete encoded video frame, ready for the decoder. */
class Frame(val id: Int, val header: FrameHeader, val data: ByteArray, val receivedUs: Long)

/**
 * Collects VIDEO/FEC shards into frames. A frame that can't be rebuilt within [timeoutUs] of its first
 * shard, or that a later frame overtakes, is dropped; then non-key frames are held back (they would
 * decode into smears) and a keyframe is requested, at most once per [idrGapUs], until one arrives.
 * Not thread-safe: feed it from one thread.
 */
class FrameAssembler(
    private val onFrame: (Frame) -> Unit,
    private val onIdr: () -> Unit,
    private val timeoutUs: Long = 50_000,
    private val idrGapUs: Long = 250_000,
) {
    private class Pending(val k: Int, val p: Int, val shards: Array<ByteArray?>, val firstUs: Long) {
        val blocks = Prs.layout(k, p)
        val have = IntArray(blocks.size)
        val blockOf = IntArray(shards.size).also { arr ->
            var at = 0
            blocks.forEachIndexed { b, (kb, mb) -> for (i in 0 until kb + mb) arr[at + i] = b; at += kb + mb }
        }
        fun ready() = blocks.indices.all { have[it] >= blocks[it].first }
    }

    private val pending = HashMap<Int, Pending>()
    private var lastDone = -1 // newest frame delivered or dropped; older shards are ignored
    private var started = false
    private var needKey = false
    private var lastIdrUs = Long.MIN_VALUE / 2

    var dropped = 0; private set
    var delivered = 0; private set

    fun onShard(h: Header, data: ByteArray, nowUs: Long) {
        if (h.typ != Prs.VIDEO && h.typ != Prs.FEC || data.size != Prs.SHARD) return
        if (started && h.frameId - lastDone <= 0) return
        val f = pending.getOrPut(h.frameId) {
            val n = Prs.layout(h.k, h.flags).sumOf { it.first + it.second }
            if (h.k == 0 || h.count != n || n > 4096) return
            Pending(h.k, h.flags, arrayOfNulls(n), nowUs)
        }
        if (h.k != f.k || h.flags != f.p || h.frag >= f.shards.size || f.shards[h.frag] != null) return
        f.shards[h.frag] = data
        f.have[f.blockOf[h.frag]]++
        if (!f.ready()) return
        pending.remove(h.frameId)
        val built = Frames.assemble(f.k, f.p, f.shards)
        // anything older than this frame is now hopeless
        val older = pending.keys.filter { it - h.frameId < 0 }
        older.forEach { pending.remove(it); drop(nowUs) }
        lastDone = h.frameId; started = true
        if (built == null) { drop(nowUs); return }
        val (fh, body) = built
        if (needKey && !fh.keyframe) { idr(nowUs); return }
        needKey = false
        delivered++
        onFrame(Frame(h.frameId, fh, body, nowUs))
    }

    /** Call every few ms: expires stale frames and repeats the keyframe request while waiting. */
    fun tick(nowUs: Long) {
        val stale = pending.filter { nowUs - it.value.firstUs > timeoutUs }.keys
        for (id in stale) {
            pending.remove(id)
            if (!started || id - lastDone > 0) { lastDone = id; started = true }
            drop(nowUs)
        }
        if (needKey) idr(nowUs)
    }

    private fun drop(nowUs: Long) { dropped++; needKey = true; idr(nowUs) }

    private fun idr(nowUs: Long) {
        if (nowUs - lastIdrUs < idrGapUs) return
        lastIdrUs = nowUs
        onIdr()
    }
}
