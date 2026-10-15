package com.pcremote.modes

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import com.pcremote.agent.AgentConnection
import com.pcremote.agent.AgentState
import com.pcremote.agent.Msg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

data class Transfer(val id: String, val name: String, val outgoing: Boolean, val progress: Float = 0f, val status: Status = Status.ACTIVE) {
    enum class Status { ACTIVE, DONE, FAILED }
}

/** Runs uploads one at a time and accepts incoming files into Downloads/PC Remote. */
class FilesController(private val ctx: Context, private val conn: AgentConnection, private val scope: CoroutineScope) {
    val transfers = mutableStateListOf<Transfer>()
    private val queue = Channel<Uri>(Channel.UNLIMITED)
    private val replies = mutableMapOf<String, CompletableDeferred<Msg>>()
    private val receivers = mutableMapOf<String, Triple<FileReceiver, OutputStream, () -> Unit>>()

    init {
        scope.launch { for (u in queue) upload(u) }
        scope.launch {
            conn.incoming.collect { m ->
                when (m) {
                    is Msg.FileReady -> replies.remove(m.id + ":ready")?.complete(m)
                    is Msg.FileDone -> replies.remove(m.id + ":done")?.complete(m)
                    is Msg.FileError -> { replies.remove(m.id + ":ready")?.complete(m); replies.remove(m.id + ":done")?.complete(m); fail(m.id) }
                    is Msg.FileOffer -> accept(m)
                    is Msg.FileEnd -> finishIncoming(m)
                    else -> {}
                }
            }
        }
        scope.launch {
            conn.binary.collect { b ->
                val c = FileFraming.parse(b) ?: return@collect
                val r = receivers[c.id.toString()] ?: return@collect
                withContext(Dispatchers.IO) { r.first.onChunk(c) }
                update(c.id.toString()) { it.copy(progress = r.first.progress) }
            }
        }
    }

    fun send(uris: List<Uri>) = uris.forEach { queue.trySend(it) }

    private fun update(id: String, f: (Transfer) -> Transfer) {
        val i = transfers.indexOfFirst { it.id == id }
        if (i >= 0) transfers[i] = f(transfers[i])
    }

    private fun fail(id: String) = update(id) { it.copy(status = Transfer.Status.FAILED) }

    private fun meta(u: Uri): Pair<String, Long> {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: "file") to c.getLong(1)
        }
        return (u.lastPathSegment ?: "file") to -1L
    }

    private suspend fun upload(u: Uri) {
        conn.state.first { it is AgentState.Online }
        val (name, size) = meta(u)
        val id = UUID.randomUUID()
        transfers.add(0, Transfer(id.toString(), name, outgoing = true))
        val ready = CompletableDeferred<Msg>().also { replies[id.toString() + ":ready"] = it }
        val done = CompletableDeferred<Msg>().also { replies[id.toString() + ":done"] = it }
        try {
            withContext(Dispatchers.IO) {
                FileSender(id, name, size, { ctx.contentResolver.openInputStream(u)!! }, conn::send, conn::sendBinary,
                    onProgress = { p -> scope.launch { update(id.toString()) { it.copy(progress = p) } } },
                ).run {
                    when (val r = withTimeoutOrNull(15_000) { ready.await() }) {
                        is Msg.FileReady -> r.offset
                        else -> throw IllegalStateException("not ready")
                    }
                }
            }
            val r = withTimeoutOrNull(60_000) { done.await() }
            update(id.toString()) { it.copy(progress = 1f, status = if (r is Msg.FileDone) Transfer.Status.DONE else Transfer.Status.FAILED) }
        } catch (e: Exception) {
            fail(id.toString())
        }
    }

    private fun accept(m: Msg.FileOffer) {
        val (out, finalize) = openDownload(m.name) ?: run { conn.send(Msg.FileReject(m.id)); return }
        receivers[m.id] = Triple(FileReceiver(UUID.fromString(m.id), m.size, out), out, finalize)
        transfers.add(0, Transfer(m.id, m.name, outgoing = false))
        conn.send(Msg.FileAccept(m.id, 0))
    }

    private fun finishIncoming(m: Msg.FileEnd) {
        val (r, out, finalize) = receivers.remove(m.id) ?: return
        val ok = r.finish(m.sha256)
        out.close()
        if (ok) finalize()
        update(m.id) { it.copy(progress = 1f, status = if (ok) Transfer.Status.DONE else Transfer.Status.FAILED) }
    }

    /** Opens Downloads/PC Remote/<name>; the returned action publishes the file when complete. */
    private fun openDownload(name: String): Pair<OutputStream, () -> Unit>? = runCatching {
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PC Remote")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            ctx.contentResolver.openOutputStream(uri)!! to {
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                Unit
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PC Remote").apply { mkdirs() }
            FileOutputStream(File(dir, safe)) to {}
        }
    }.getOrNull()
}
