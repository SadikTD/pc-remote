package com.pcremote.prs

/**
 * Reed-Solomon over GF(2^8) (polynomial 0x11D, generator 2), byte-compatible with Backblaze
 * JavaReedSolomon and the Rust `reed-solomon-erasure` crate: systematic matrix =
 * Vandermonde(n×k) × inverse(top k×k).
 */
class ReedSolomon(private val k: Int, private val m: Int) {
    init { require(k in 1..255 && m in 1..255 && k + m <= 256) }

    private val matrix: Array<IntArray> = run {
        val v = Array(k + m) { r -> IntArray(k) { c -> pow(r, c) } }
        mul(v, invert(Array(k) { v[it].copyOf() }))
    }

    /** Fills `shards[k until k+m]` with parity computed from `shards[0 until k]` (all equal length). */
    fun encode(shards: Array<ByteArray>) {
        for (p in 0 until m) code(matrix[k + p], shards, 0 until k, shards[k + p])
    }

    /** Rebuilds missing data shards in place; false if fewer than `k` shards are present. */
    fun reconstructData(shards: Array<ByteArray?>): Boolean {
        if (shards.size != k + m) return false
        val missing = (0 until k).filter { shards[it] == null }
        if (missing.isEmpty()) return true
        val present = shards.indices.filter { shards[it] != null }.take(k)
        if (present.size < k) return false
        val len = shards[present[0]]!!.size
        val decode = invert(Array(k) { matrix[present[it]].copyOf() })
        val src = Array(k) { shards[present[it]]!! }
        for (d in missing) shards[d] = ByteArray(len).also { code(decode[d], src, 0 until k, it) }
        return true
    }

    private fun code(row: IntArray, src: Array<out ByteArray?>, cols: IntRange, out: ByteArray) {
        out.fill(0)
        for (c in cols) {
            val t = MUL[row[c - cols.first]]
            val s = src[c]!!
            for (i in out.indices) out[i] = (out[i].toInt() xor t[s[i].toInt() and 0xFF]).toByte()
        }
    }

    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<Int, ReedSolomon>()
        /** Shared instance per shape (building the matrix costs O(k³)). */
        fun of(k: Int, m: Int): ReedSolomon = cache.getOrPut(k shl 8 or m) { ReedSolomon(k, m) }

        private val EXP = IntArray(510)
        private val LOG = IntArray(256)
        init {
            var x = 1
            for (i in 0 until 255) { EXP[i] = x; LOG[x] = i; x = x shl 1; if (x >= 256) x = x xor 0x11D }
            for (i in 255 until 510) EXP[i] = EXP[i - 255]
        }
        private val MUL = Array(256) { a -> IntArray(256) { b -> gmul(a, b) } }

        private fun gmul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
        private fun gdiv(a: Int, b: Int) = if (a == 0) 0 else EXP[(LOG[a] - LOG[b] + 255) % 255]
        private fun pow(a: Int, n: Int) = when { n == 0 -> 1; a == 0 -> 0; else -> EXP[LOG[a] * n % 255] }

        private fun mul(a: Array<IntArray>, b: Array<IntArray>) = Array(a.size) { r ->
            IntArray(b[0].size) { c -> var s = 0; for (i in b.indices) s = s xor gmul(a[r][i], b[i][c]); s }
        }

        /** Gauss-Jordan inverse of a square matrix (mutates `a`). */
        private fun invert(a: Array<IntArray>): Array<IntArray> {
            val n = a.size
            val inv = Array(n) { r -> IntArray(n) { if (it == r) 1 else 0 } }
            for (c in 0 until n) {
                val p = (c until n).firstOrNull { a[it][c] != 0 } ?: error("singular matrix")
                a[c] = a[p].also { a[p] = a[c] }; inv[c] = inv[p].also { inv[p] = inv[c] }
                val d = a[c][c]
                for (j in 0 until n) { a[c][j] = gdiv(a[c][j], d); inv[c][j] = gdiv(inv[c][j], d) }
                for (r in 0 until n) if (r != c && a[r][c] != 0) {
                    val f = a[r][c]
                    for (j in 0 until n) { a[r][j] = a[r][j] xor gmul(f, a[c][j]); inv[r][j] = inv[r][j] xor gmul(f, inv[c][j]) }
                }
            }
            return inv
        }
    }
}
