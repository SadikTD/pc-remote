package com.pcremote.modes

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.pcremote.agent.AgentConnection
import com.pcremote.agent.AgentState
import com.pcremote.agent.FsEntry
import com.pcremote.agent.Msg
import com.pcremote.agent.PcStore
import com.pcremote.settings.prefsKv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Transfer(
    val id: String,
    /** Relative path for folder transfers ("Trip/a.jpg"). */
    val name: String,
    val outgoing: Boolean,
    val size: Long,
    val done: Long = 0,
    val status: Status = Status.QUEUED,
    /** Bytes per second, smoothed. */
    val speed: Double = 0.0,
) {
    enum class Status { QUEUED, ACTIVE, PAUSED, DONE, FAILED }
    val progress get() = if (size <= 0) (if (status == Status.DONE) 1f else 0f) else (done.toFloat() / size).coerceIn(0f, 1f)
    /** Seconds left, or null while unknown. */
    val eta: Long? get() = if (status == Status.ACTIVE && speed > 1) ((size - done) / speed).toLong() else null
    val finished get() = status == Status.DONE || status == Status.FAILED
}

/** Exponentially smoothed transfer rate. */
class Rate(private val now: () -> Long = System::nanoTime) {
    private var lastT = 0L
    private var lastBytes = 0L
    var bytesPerSec = 0.0
        private set

    fun update(bytes: Long): Double {
        val t = now()
        if (lastT == 0L) { lastT = t; lastBytes = bytes; return bytesPerSec }
        val dt = (t - lastT) / 1e9
        if (dt < 0.25) return bytesPerSec
        val inst = (bytes - lastBytes) / dt
        bytesPerSec = if (bytesPerSec == 0.0) inst else bytesPerSec * 0.7 + inst * 0.3
        lastT = t; lastBytes = bytes
        return bytesPerSec
    }
}

/**
 * Every transfer with one PC, on its own agent connection, alive while anything is moving
 * (kept running in the background by [TransferService]). Uploads go one at a time and resume
 * with the same id; downloads land in Downloads/PC Remote keeping their folders.
 */
class TransferHub private constructor(private val ctx: Context, val pcId: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers
    private val pending = PendingUploads(prefsKv(ctx), pcId)
    private val queue = Channel<PendingUpload>(Channel.UNLIMITED)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val replies = ConcurrentHashMap<String, CompletableDeferred<Msg>>()
    private val offers = ConcurrentHashMap.newKeySet<String>()
    private var listing: CompletableDeferred<Msg.FsList>? = null
    private var current: Pair<String, Job>? = null
    private var conn: AgentConnection? = null

    private class Incoming(val receiver: FileReceiver, val out: OutputStream, val publish: () -> Unit, val discard: () -> Unit, val rate: Rate)
    private val receivers = ConcurrentHashMap<String, Incoming>()

    val online: Boolean get() = conn?.state?.value is AgentState.Online

    /** Opens the connection on first use. */
    @Synchronized
    private fun connection(): AgentConnection {
        conn?.let { return it }
        val pc = PcStore.get(ctx).all().first { it.id == pcId }
        val c = AgentConnection.forPc(pc, scope)
        conn = c
        c.binaryListener = { b -> FileFraming.parse(b)?.let { onChunk(it) } }
        scope.launch { c.incoming.collect(::onMsg) }
        scope.launch {
            c.state.collect { st ->
                if (st is AgentState.Online) pending.all().filter { it.id !in paused }.forEach(::enqueue)
                else receivers.keys.toList().forEach { id -> receivers.remove(id)?.let { runCatching { it.out.close() }; it.discard() }; set(id) { t -> t.copy(status = Transfer.Status.FAILED) } }
            }
        }
        scope.launch { for (u in queue) { runUpload(u); queued.remove(u.id) } }
        scope.launch { c.hello(pc.token, Build.MODEL) }
        TransferService.watch(ctx, this)
        return c
    }

    /** Closes the connection once nothing is moving (the service calls this). */
    @Synchronized
    fun idleClose() {
        if (busy()) return
        conn?.close(); conn = null
    }

    fun busy() = _transfers.value.any { it.status == Transfer.Status.ACTIVE || it.status == Transfer.Status.QUEUED }

    private fun set(id: String, f: (Transfer) -> Transfer) = _transfers.update { l -> l.map { if (it.id == id) f(it) else it } }
    private fun add(t: Transfer) = _transfers.update { l -> listOf(t) + l.filterNot { it.id == t.id } }

    // ---- uploads ----

    /** Files picked in the system picker (or shared from another app). */
    fun send(uris: List<Uri>) = scope.launch(Dispatchers.IO) {
        for (u in uris) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val (name, size) = meta(u)
            queueUpload(u.toString(), name, size)
        }
    }

    /** A whole folder (from the folder picker), keeping its layout on the PC. */
    fun sendTree(tree: Uri) = scope.launch(Dispatchers.IO) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val root = DocumentsContract.getTreeDocumentId(tree)
        val rootName = queryName(DocumentsContract.buildDocumentUriUsingTree(tree, root)) ?: "Folder"
        walk(tree, root, rootName) { uri, rel, size -> queueUpload(uri.toString(), rel, size) }
    }

    private fun walk(tree: Uri, docId: String, prefix: String, emit: (Uri, String, Long) -> Unit) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) walk(tree, id, "$prefix/$name", emit)
                else emit(DocumentsContract.buildDocumentUriUsingTree(tree, id), "$prefix/$name", if (c.isNull(3)) -1 else c.getLong(3))
            }
        }
    }

    private fun queueUpload(uri: String, name: String, size: Long) {
        val id = UUID.randomUUID().toString()
        if (size < 0) { add(Transfer(id, name, true, 0, status = Transfer.Status.FAILED)); return }
        val p = PendingUpload(id, uri, name, size)
        pending.add(p)
        add(Transfer(id, name, true, size))
        scope.launch { connection(); enqueue(p) }
    }

    private fun enqueue(p: PendingUpload) {
        if (_transfers.value.none { it.id == p.id }) add(Transfer(p.id, p.name, true, p.size))
        if (queued.add(p.id)) queue.trySend(p)
    }

    private suspend fun runUpload(p: PendingUpload) {
        if (p.id in paused) return
        val c = connection()
        c.state.first { it is AgentState.Online }
        set(p.id) { it.copy(status = Transfer.Status.ACTIVE) }
        val ready = CompletableDeferred<Msg>().also { replies[p.id + ":ready"] = it }
        val done = CompletableDeferred<Msg>().also { replies[p.id + ":done"] = it }
        val rate = Rate()
        val job = scope.launch(Dispatchers.IO) {
            FileSender(UUID.fromString(p.id), p.name, p.size, { ctx.contentResolver.openInputStream(Uri.parse(p.uri))!! }, { c.send(it) }, c::sendBinary,
                onProgress = { pr -> val bytes = (pr * p.size).toLong(); val s = rate.update(bytes); set(p.id) { it.copy(done = bytes, speed = s) } },
                backlog = c::backlog,
            ).run {
                when (val r = withTimeoutOrNull(15_000) { ready.await() }) {
                    is Msg.FileReady -> r.offset
                    else -> throw IllegalStateException("not ready")
                }
            }
        }
        current = p.id to job
        try {
            job.join()
            if (job.isCancelled) return // paused or cancelled: state already set
            val r = withTimeoutOrNull(120_000 + p.size / 20_000) { done.await() }
            if (r is Msg.FileDone) {
                pending.remove(p.id)
                set(p.id) { it.copy(done = it.size, status = Transfer.Status.DONE, speed = 0.0) }
            } else {
                if (r is Msg.FileError) pending.remove(p.id) // refused; resuming won't help
                set(p.id) { it.copy(status = Transfer.Status.FAILED, speed = 0.0) }
            }
        } catch (e: Exception) {
            set(p.id) { it.copy(status = Transfer.Status.FAILED, speed = 0.0) } // stays pending: resumes on reconnect
        } finally {
            current = null
            replies.remove(p.id + ":ready"); replies.remove(p.id + ":done")
        }
    }

    /** Stops an upload; the PC keeps what it has, so [resume] continues from there. */
    fun pause(id: String) {
        paused += id
        if (current?.first == id) current?.second?.cancel()
        set(id) { it.copy(status = Transfer.Status.PAUSED, speed = 0.0) }
    }

    fun resume(id: String) {
        paused -= id
        val p = pending.all().firstOrNull { it.id == id } ?: return
        set(id) { it.copy(status = Transfer.Status.QUEUED) }
        scope.launch { connection(); enqueue(p) }
    }

    /** Stops a transfer for good and drops its partial file on both sides. */
    fun cancel(id: String) {
        paused += id
        if (current?.first == id) current?.second?.cancel()
        pending.remove(id)
        receivers.remove(id)?.let { runCatching { it.out.close() }; it.discard() }
        conn?.sendIfOnline(Msg.FileCancel(id))
        _transfers.update { l -> l.filterNot { it.id == id } }
    }

    fun clearFinished() = _transfers.update { l -> l.filterNot { it.finished } }

    // ---- downloads ----

    /** A `file.offer` seen by any connection to this PC: accept it here (once). */
    fun offer(m: Msg.FileOffer) {
        if (!offers.add(m.id)) return
        scope.launch {
            val c = connection()
            c.state.first { it is AgentState.Online }
            val target = withContext(Dispatchers.IO) { openDownload(m.name) } ?: run { c.send(Msg.FileReject(m.id)); return@launch }
            val (out, publish, discard) = target
            receivers[m.id] = Incoming(FileReceiver(UUID.fromString(m.id), m.size, out), out, publish, discard, Rate())
            add(Transfer(m.id, m.name, outgoing = false, size = m.size, status = Transfer.Status.ACTIVE))
            c.send(Msg.FileAccept(m.id, 0))
        }
    }

    private fun onChunk(c: FileFraming.Chunk) {
        val id = c.id.toString()
        val inc = receivers[id] ?: return
        inc.receiver.onChunk(c)
        val bytes = (inc.receiver.progress * (_transfers.value.firstOrNull { it.id == id }?.size ?: 0)).toLong()
        val s = inc.rate.update(bytes)
        set(id) { it.copy(done = bytes, speed = s) }
    }

    private fun onMsg(m: Msg) {
        when (m) {
            is Msg.FileReady -> replies.remove(m.id + ":ready")?.complete(m)
            is Msg.FileDone -> replies.remove(m.id + ":done")?.complete(m)
            is Msg.FileError -> {
                replies.remove(m.id + ":ready")?.complete(m); replies.remove(m.id + ":done")?.complete(m)
                receivers.remove(m.id)?.let { runCatching { it.out.close() }; it.discard() }
                set(m.id) { it.copy(status = Transfer.Status.FAILED, speed = 0.0) }
            }
            is Msg.FileOffer -> offer(m)
            is Msg.FileEnd -> scope.launch(Dispatchers.IO) {
                val inc = receivers.remove(m.id) ?: return@launch
                val ok = inc.receiver.finish(m.sha256)
                runCatching { inc.out.close() }
                if (ok) inc.publish() else inc.discard()
                set(m.id) { it.copy(done = if (ok) it.size else it.done, status = if (ok) Transfer.Status.DONE else Transfer.Status.FAILED, speed = 0.0) }
            }
            is Msg.FsList -> listing?.complete(m)
            is Msg.Error -> if (m.of == "fs.list") listing?.complete(Msg.FsList("", emptyList()))
            else -> {}
        }
    }

    // ---- browsing the PC ----

    /** A folder on the PC ("" = its folders and drives); null if unreachable. */
    suspend fun list(path: String): List<FsEntry>? {
        val c = connection()
        withTimeoutOrNull(10_000) { c.state.first { it is AgentState.Online } } ?: return null
        val d = CompletableDeferred<Msg.FsList>()
        listing = d
        c.send(Msg.FsList(path))
        return withTimeoutOrNull(10_000) { d.await() }?.entries
    }

    /** Downloads a file or a whole folder from the PC. */
    fun download(path: String) = scope.launch {
        val c = connection()
        c.state.first { it is AgentState.Online }
        c.send(Msg.FsGet(path))
    }

    // ---- storage ----

    private fun meta(u: Uri): Pair<String, Long> {
        var name = u.lastPathSegment ?: "file"; var size = -1L
        runCatching {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size <= 0) size = runCatching { ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } }.getOrNull() ?: -1L
        return name to size
    }

    private fun queryName(u: Uri): String? = runCatching {
        ctx.contentResolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Downloads/PC Remote/<relative path>; app-specific Downloads before Android 10 (no permission needed). */
    private fun openDownload(name: String): Triple<OutputStream, () -> Unit, () -> Unit>? = runCatching {
        val parts = safeParts(name)
        val file = parts.last()
        val sub = parts.dropLast(1).joinToString("/")
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PC Remote" + (if (sub.isEmpty()) "" else "/$sub"))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            Triple(ctx.contentResolver.openOutputStream(uri)!!,
                { ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null); Unit },
                { ctx.contentResolver.delete(uri, null, null); Unit })
        } else {
            val dir = File(File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "PC Remote"), sub).apply { mkdirs() }
            var f = File(dir, file); var n = 1
            while (f.exists()) f = File(dir, "${file.substringBeforeLast('.')} (${n++})" + (if ('.' in file) "." + file.substringAfterLast('.') else ""))
            val out = f
            Triple(FileOutputStream(out), {}, { out.delete(); Unit })
        }
    }.getOrNull()

    companion object {
        private val hubs = ConcurrentHashMap<String, TransferHub>()
        fun get(ctx: Context, pcId: String): TransferHub = hubs.getOrPut(pcId) { TransferHub(ctx.applicationContext, pcId) }
        fun all(): Collection<TransferHub> = hubs.values

        /** A PC-supplied relative path as safe folder/file names (no `..`, no empty parts). */
        fun safeParts(name: String): List<String> =
            name.split('/', '\\').filter { it.isNotBlank() && it != "." && it != ".." }.map { it.replace(Regex("""[\x00-\x1f:*?"<>|]"""), "_") }
                .ifEmpty { listOf("file") }
    }
}
