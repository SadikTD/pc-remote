package com.pcremote.prs

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/** Opus (48 kHz stereo, 10 ms packets) → MediaCodec → low-latency AudioTrack, with a 20–60 ms buffer. */
class AudioPlayer {
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "pcr-audio").apply { priority = Thread.MAX_PRIORITY } }
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var written = 0L // frames written to the track
    private val info = MediaCodec.BufferInfo()
    private var pcm = ByteArray(0)
    @Volatile private var dead = false

    fun play(a: AudioPacket) {
        if (!dead) exec.execute { runCatching { decode(a.opus) }.onFailure { Log.w(TAG, "audio", it); shutdown() } }
    }

    fun release() {
        dead = true
        exec.execute { shutdown() }
        exec.shutdown()
    }

    private fun start() {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 2).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
            setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).flip() as ByteBuffer)
            setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).flip() as ByteBuffer)
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply { configure(fmt, null, null, 0); start() }
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(bufferBytes(min))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply { play() }
    }

    private fun decode(opus: ByteArray) {
        if (dead) return
        if (codec == null) start()
        val c = codec!!
        val t = track!!
        val inIdx = c.dequeueInputBuffer(5_000)
        if (inIdx >= 0) {
            c.getInputBuffer(inIdx)!!.apply { clear(); put(opus) }
            c.queueInputBuffer(inIdx, 0, opus.size, 0, 0)
        }
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            if (outIdx < 0) break
            val buf = c.getOutputBuffer(outIdx)!!
            if (pcm.size < info.size) pcm = ByteArray(info.size)
            buf.position(info.offset); buf.get(pcm, 0, info.size)
            c.releaseOutputBuffer(outIdx, false)
            // keep at most 60 ms queued: drop rather than let latency grow
            val queued = written - t.playbackHeadPosition.toLong()
            if (queued > RATE * 60 / 1000) continue
            t.write(pcm, 0, info.size, AudioTrack.WRITE_NON_BLOCKING)
            written += info.size / 4
        }
    }

    private fun shutdown() {
        dead = true
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
    }

    companion object {
        private const val TAG = "PcrAudio"
        const val RATE = 48_000
        private const val BYTES_PER_MS = RATE / 1000 * 4

        /** Track buffer: the platform minimum, at least 20 ms (queueing is capped at 60 ms in [decode]). */
        fun bufferBytes(min: Int) = maxOf(min, 20 * BYTES_PER_MS) // the minimum wins over the 60 ms cap: a smaller track stutters

        /** RFC 7845 identification header: stereo, 48 kHz, no pre-skip, mapping family 0. */
        fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray()).put(1).put(2).putShort(0).putInt(RATE).putShort(0).put(0).array()
    }
}
