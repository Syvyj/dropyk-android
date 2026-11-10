package dev.syvyj.dropyk

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the TV screen shows. */
sealed interface DropState {
    /** QR on screen, the phone hasn't opened the page yet. */
    data object Waiting : DropState
    data object Connected : DropState
    /** [cancel] stops the transfer from the TV; the partial file is removed. */
    data class Receiving(val name: String, val done: Long, val total: Long, val cancel: (() -> Unit)? = null) : DropState
    /**
     * [uri] is the saved file, handed to the installer and viewers; [path] goes to video and audio
     * players (see MainActivity.watch); [folder] is shown to the person.
     */
    data class Received(val uri: Uri, val path: String, val name: String, val mime: String, val folder: String, val count: Int) : DropState
    data class Link(val url: String) : DropState
    /** Text that isn't a link — a Wi‑Fi password, a code, a search — shown big, not saved. */
    data class Text(val text: String) : DropState
    /** Quick Share from [device] asks to send [what] ([count] items); the TV shows [pin] to compare with the phone. */
    data class Incoming(val device: String, val pin: String, val what: String, val count: Int, val accept: () -> Unit, val decline: () -> Unit) : DropState
    data class Failed(val name: String) : DropState
}

/**
 * Receives files and links from a phone with no app on it: the TV runs a tiny HTTP server on the
 * home network, the QR leads to `http://<ip>:<port>/<token>/`, and the phone's browser POSTs each
 * file as a raw body. Bytes go to disk in chunks, so a 4 GB film fits through a 1 GB box.
 *
 * Files land in Download/Dropyk, or — when the phone asks for `sorted` — videos in Movies/Dropyk,
 * photos in Pictures/Dropyk and music in Music/Dropyk, where players and galleries look first.
 * MediaStore needs no permission for that on Android 10+.
 * The token is stable, so a bookmark on the phone keeps working across restarts.
 *
 * ponytail: one file at a time; the phone page sends a batch one by one.
 */
// Holds only the application context, which lives as long as the process: no leak.
@SuppressLint("StaticFieldLeak")
object Drop {
    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences("drop", Context.MODE_PRIVATE)
    @Volatile private var key: String? = null
    /** The secret in the page's address; stable, so a bookmark keeps working, until [newToken]. */
    private val token: String get() = key ?: synchronized(this) { key ?: (prefs.getString("token", null) ?: freshToken()).also { key = it } }

    private fun freshToken() = ByteArray(8).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }.also { prefs.edit { putString("token", it) } }

    /** A new address: bookmarks, the iPhone shortcut and anyone who saw the old code can't send any more. */
    @Synchronized
    fun newToken() { key = freshToken() }
    /** One transfer at a time for the whole TV: the page and Quick Share share one screen and one disk. */
    private val busy = AtomicBoolean(false)
    private val received = AtomicInteger()
    /** Requests at once; more are turned away, so a flood of connections can't eat the box's memory. */
    private val connections = Semaphore(16)

    private val _state = MutableStateFlow<DropState>(DropState.Waiting)
    val state: StateFlow<DropState> = _state.asStateFlow()

    private var server: ServerSocket? = null

    private val legacyDir: File by lazy {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
        File(base, FOLDER).apply { mkdirs() }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        thread(name = "drop-cleanup", isDaemon = true) { cleanUpPending(System.currentTimeMillis() / 1000) }
    }

    /**
     * Address for the QR; `null` when the TV isn't on a local network or no port is free. The
     * screen asks again every few seconds, so a new IP (or a network that came up late) shows up.
     */
    @Synchronized
    fun start(): String? {
        val ip = lanAddress() ?: return null
        val listening = server ?: (PORTS.firstNotNullOfOrNull { runCatching { ServerSocket(it) }.getOrNull() } ?: return null).also { s ->
            server = s
            thread(name = "drop", isDaemon = true) {
                while (true) {
                    val socket = runCatching { s.accept() }.getOrNull() ?: break
                    if (!connections.tryAcquire()) { runCatching { socket.close() }; continue }
                    thread(isDaemon = true) {
                        try { runCatching { socket.use(::serve) }.onFailure { Log.w(TAG, "request", it) } } finally { connections.release() }
                    }
                }
            }
        }
        return "http://$ip:${listening.localPort}/$token/"
    }

    /** Stops listening: Dropyk closed with Receive in background off. A transfer under way finishes. */
    @Synchronized
    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    /** Back to the QR from an old error, when Dropyk is opened again. */
    fun clearFailure() {
        if (_state.value is DropState.Failed) _state.value = DropState.Waiting
    }

    /** Takes the TV's one transfer slot; false while another transfer holds it. */
    internal fun claim() = busy.compareAndSet(false, true)
    internal fun release() = busy.set(false)

    /**
     * Half-written files left by a transfer the process didn't live through (killed, power cut):
     * hidden from everyone, so nothing else would ever clean them. Only rows older than [before]
     * (seconds), so a transfer that starts meanwhile is safe. ponytail: Android 11+ only, where
     * pending rows can be listed; on 7–9 a partial file looks whole and stays.
     */
    private fun cleanUpPending(before: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATE_ADDED} < ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(before.toString()))
        }
        val collections = listOf(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        )
        for (collection in collections) runCatching {
            // Without storage permission an app sees only its own rows: nothing of anyone else's is touched.
            val ids = context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)
                ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
            ids.forEach { context.contentResolver.delete(ContentUris.withAppendedId(collection, it), null, null) }
        }.onFailure { Log.w(TAG, "clean up $collection", it) }
    }

    /** Where Quick Share saves: the phone page's last choice, Downloads until there is one. */
    internal val sorted: Boolean get() = prefs.getBoolean("sorted", false)

    /** A link opens in a browser; anything else is shown as text. Both from the page and from Quick Share. */
    internal fun textState(text: String): DropState =
        if ((text.startsWith("http://") || text.startsWith("https://")) && text.none(Char::isWhitespace)) DropState.Link(text) else DropState.Text(text)

    /** For Quick Share: the same screen states the phone page drives. */
    internal fun show(state: DropState) { _state.value = state }

    /** A finished file from Quick Share, shown like one from the page. */
    internal fun received(target: Target, mime: String) {
        _state.value = DropState.Received(target.uri, target.path, target.name, mime, target.folder, received.incrementAndGet())
    }

    /** Back to the QR once the received file was dealt with. */
    fun dismiss() {
        if (_state.value !is DropState.Receiving) _state.value = DropState.Waiting
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 60_000
        val input = socket.getInputStream().buffered(256 * 1024)
        val out = socket.getOutputStream()
        // Bounded before the key is even checked: an endless line or endless headers can't fill memory.
        val request = input.line(MAX_LINE) ?: return
        var length = -1L
        for (i in 0..MAX_HEADERS) {
            if (i == MAX_HEADERS) return
            val h = input.line(MAX_LINE) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toLongOrNull() ?: -1
        }
        val (method, target) = request.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val prefix = "/$token/"
        if (!target.startsWith(prefix)) return out.reply(404, "text/plain", "Not found")
        val route = target.removePrefix(prefix).substringBefore('?')
        val query = target.substringAfter('?', "").split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        when {
            method == "GET" && route == "" -> {
                if (_state.value == DropState.Waiting) _state.value = DropState.Connected
                out.reply(200, "text/html", context.assets.open("phone.html").bufferedReader().readText())
            }
            method == "GET" && route == "space" -> out.reply(200, "application/json", """{"free":${free()}}""")
            // "link" is the old name: pages opened before 0.5 still post there.
            method == "POST" && (route == "text" || route == "link") && length > MAX_TEXT -> out.error(413, "too_long")
            method == "POST" && (route == "text" || route == "link") && length > 0 -> {
                val text = String(input.readExactly(length.toInt()), Charsets.UTF_8).trim()
                when {
                    text.isEmpty() -> out.error(400, "empty")
                    // A file or a Quick Share request is on the screen: text would hide it.
                    busy.get() -> out.error(409, "busy")
                    else -> {
                        _state.value = textState(text)
                        out.reply(200, "application/json", "{}")
                    }
                }
            }
            method == "POST" && route == "upload" -> {
                // No choice in the request (the iPhone shortcut, old bookmarks): the page's last one.
                val sorted = if ("to" in query) query["to"] == "sorted" else this.sorted
                // Quick Share has no page to choose on, so it follows the phone page's last choice.
                if ("to" in query) prefs.edit { putBoolean("sorted", sorted) }
                upload(input, out, query["name"].orEmpty(), length, sorted)
            }
            else -> out.reply(404, "text/plain", "Not found")
        }
    }

    private fun upload(input: InputStream, out: OutputStream, rawName: String, length: Long, sorted: Boolean) {
        if (length <= 0) return out.error(411, "unknown_size")
        if (length + MARGIN > free()) return out.error(507, "no_space")
        if (!claim()) return out.error(409, "busy")
        val name = safeName(rawName)
        val mime = mimeOf(name)
        val target = runCatching { create(name, mime, sorted) }.getOrElse {
            busy.set(false)
            Log.w(TAG, "create $name", it)
            return out.error(500, "cant_create")
        }
        // Cancel on the TV: the loop stops at the next chunk, the partial file goes, the QR comes back.
        val canceled = AtomicBoolean(false)
        val cancel = { canceled.set(true) }
        try {
            var done = 0L
            var shownAt = 0L
            (context.contentResolver.openOutputStream(target.uri) ?: error("no stream")).buffered(256 * 1024).use { sink ->
                val buf = ByteArray(256 * 1024)
                while (done < length && !canceled.get()) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), length - done).toInt())
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    done += n
                    // A few redraws a second at most: on a cheap TV box every frame drawn during
                    // a transfer cut Wi-Fi throughput from ~22 MB/s to ~1 MB/s (measured).
                    val now = System.currentTimeMillis()
                    if (now - shownAt >= PROGRESS_EVERY_MS || done == length) {
                        shownAt = now
                        _state.value = DropState.Receiving(target.name, done, length, cancel)
                    }
                }
            }
            if (done < length) {
                discard(target)
                if (canceled.get()) {
                    _state.value = DropState.Waiting
                    return out.error(409, "canceled")
                }
                _state.value = DropState.Failed(target.name)
                return out.error(400, "interrupted")
            }
            publish(target)
            received(target, mime)
            out.reply(200, "application/json", """{"folder":"${target.folder}"}""")
        } catch (e: Exception) {
            discard(target)
            _state.value = DropState.Failed(target.name)
            throw e
        } finally {
            busy.set(false)
        }
    }

    internal class Target(val uri: Uri, val name: String, val folder: String, val file: File? = null) {
        val path: String get() = file?.path ?: File(Environment.getExternalStorageDirectory(), "$folder/$name").path
    }

    /**
     * A new file in its folder; MediaStore renames duplicates itself ("name (1).jpg").
     * Each media collection only accepts its own top folder, hence the pairs below.
     */
    internal fun create(name: String, mime: String, sorted: Boolean): Target {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, top) = when {
                !sorted -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
                mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
                mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
                mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            }
            val folder = "$top/$FOLDER"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                // Hidden from other apps while writing, so a player can't grab half a file.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values) ?: error("MediaStore refused")
            val actual = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
            return Target(uri, actual, folder)
        }
        // ponytail: before Android 10 everything goes to the app's own folder, no sorting.
        val file = unique(name)
        file.createNewFile()
        return Target(FileProvider.getUriForFile(context, "${context.packageName}.files", file), file.name, legacyDir.path, file)
    }

    internal fun publish(target: Target) {
        if (target.file != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        context.contentResolver.update(target.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    internal fun discard(target: Target) {
        runCatching { if (target.file != null) target.file.delete() else context.contentResolver.delete(target.uri, null, null) }
    }

    internal fun free(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0)

    private fun unique(name: String): File {
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var f = File(legacyDir, name)
        var i = 2
        while (f.exists()) f = File(legacyDir, "$stem (${i++})$ext")
        return f
    }

    private fun OutputStream.error(code: Int, key: String) = reply(code, "application/json", """{"error":"$key"}""")

    private fun OutputStream.reply(code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        write("HTTP/1.1 $code X\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        write(bytes)
        flush()
    }

    /** One header line; longer than [max] is an error, not a bigger buffer. */
    internal fun InputStream.line(max: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= max) error("header line longer than $max")
            sb.append(c.toChar())
        }
    }

    private fun InputStream.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(out, off, n - off)
            if (r < 0) break
            off += r
        }
        return out.copyOf(off)
    }

    private const val TAG = "Drop"
    internal const val PROGRESS_EVERY_MS = 500L
    private val PORTS = 8776..8780
    /** Enough for a file name of 120 characters, percent-encoded twice over. */
    internal const val MAX_LINE = 8 * 1024
    private const val MAX_HEADERS = 64
    /** Text from the page or Quick Share shown on screen; longer Quick Share text is saved as a .txt. */
    internal const val MAX_TEXT = 4096
    /** Leave the system 200 MB: a box filled to the brim slows down and can't update. */
    internal const val MARGIN = 200L * 1024 * 1024
    const val FOLDER = "Dropyk"

    /** First private IPv4 on a real interface, wired first; VPN tunnels skipped. */
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && VPN.none(it.name::startsWith) }
            .sortedBy { if (it.name.startsWith("eth")) 0 else 1 }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
            .firstOrNull()?.hostAddress
    }.getOrNull()

    private val VPN = listOf("tun", "ppp", "wg", "tap", "ipsec")

    /**
     * The name's last path part, without characters file systems refuse; "." and ".." become
     * "file". A long name is cut before its extension, which picks the folder and the button.
     */
    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[\u0000-\u001f:*?"<>|]"""), "_").trim()
        if (base.all { it == '.' }) return "file"
        if (base.length <= 120) return base
        val ext = base.substringAfterLast('.', "")
        return if (ext.length in 1..16) base.take(119 - ext.length) + "." + ext else base.take(120)
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "apk" -> APK
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    const val APK = "application/vnd.android.package-archive"
}
