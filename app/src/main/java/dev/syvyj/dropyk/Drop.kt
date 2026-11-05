package dev.syvyj.dropyk

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the TV screen shows. */
sealed interface DropState {
    /** QR on screen, the phone hasn't opened the page yet. */
    data object Waiting : DropState
    data object Connected : DropState
    data class Receiving(val name: String, val done: Long, val total: Long) : DropState
    /**
     * [uri] is the saved file, handed to the installer and viewers; [path] goes to video and audio
     * players (see MainActivity.watch); [folder] is shown to the person.
     */
    data class Received(val uri: Uri, val path: String, val name: String, val mime: String, val folder: String, val count: Int) : DropState
    data class Link(val url: String) : DropState
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
    private val token: String by lazy {
        val prefs = context.getSharedPreferences("drop", Context.MODE_PRIVATE)
        prefs.getString("token", null) ?: ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { prefs.edit { putString("token", it) } }
    }
    private val busy = AtomicBoolean(false)
    private var received = 0

    private val _state = MutableStateFlow<DropState>(DropState.Waiting)
    val state: StateFlow<DropState> = _state.asStateFlow()

    @Volatile private var address: String? = null

    private val legacyDir: File by lazy {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
        File(base, FOLDER).apply { mkdirs() }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /** Address for the QR; `null` when the TV isn't on a local network or no port is free. */
    @Synchronized
    fun start(): String? {
        if (_state.value is DropState.Failed) _state.value = DropState.Waiting
        address?.let { return it }
        val ip = lanAddress() ?: return null
        val server = PORTS.firstNotNullOfOrNull { runCatching { ServerSocket(it) }.getOrNull() } ?: return null
        thread(name = "drop", isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { socket.use(::serve) }.onFailure { Log.w(TAG, "request", it) } }
            }
        }
        return "http://$ip:${server.localPort}/$token/".also { address = it }
    }

    /** Where Quick Share saves: the phone page's last choice, Downloads until there is one. */
    internal val sorted: Boolean get() = context.getSharedPreferences("drop", Context.MODE_PRIVATE).getBoolean("sorted", false)

    /** For Quick Share: the same screen states the phone page drives. */
    internal fun show(state: DropState) { _state.value = state }

    /** A finished file from Quick Share, shown like one from the page. */
    internal fun received(target: Target, mime: String) {
        received++
        _state.value = DropState.Received(target.uri, target.path, target.name, mime, target.folder, received)
    }

    /** Back to the QR once the received file was dealt with. */
    fun dismiss() {
        if (_state.value !is DropState.Receiving) _state.value = DropState.Waiting
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 60_000
        val input = socket.getInputStream().buffered(256 * 1024)
        val out = socket.getOutputStream()
        val request = input.line() ?: return
        var length = -1L
        while (true) {
            val h = input.line() ?: break
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
            method == "POST" && route == "link" && length in 1..4096 -> {
                val url = String(input.readExactly(length.toInt()), Charsets.UTF_8).trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    _state.value = DropState.Link(url)
                    out.reply(200, "application/json", "{}")
                } else {
                    out.error(400, "not_a_link")
                }
            }
            method == "POST" && route == "upload" -> {
                val sorted = query["to"] == "sorted"
                // Quick Share has no page to choose on, so it follows the phone page's last choice.
                if ("to" in query) context.getSharedPreferences("drop", Context.MODE_PRIVATE).edit { putBoolean("sorted", sorted) }
                upload(input, out, query["name"].orEmpty(), length, sorted)
            }
            else -> out.reply(404, "text/plain", "Not found")
        }
    }

    private fun upload(input: InputStream, out: OutputStream, rawName: String, length: Long, sorted: Boolean) {
        if (length <= 0) return out.error(411, "unknown_size")
        if (length + MARGIN > free()) return out.error(507, "no_space")
        if (!busy.compareAndSet(false, true)) return out.error(409, "busy")
        val name = safeName(rawName)
        val mime = mimeOf(name)
        val target = runCatching { create(name, mime, sorted) }.getOrElse {
            busy.set(false)
            Log.w(TAG, "create $name", it)
            return out.error(500, "cant_create")
        }
        try {
            var done = 0L
            var shownAt = 0L
            (context.contentResolver.openOutputStream(target.uri) ?: error("no stream")).buffered(256 * 1024).use { sink ->
                val buf = ByteArray(256 * 1024)
                while (done < length) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), length - done).toInt())
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    done += n
                    // A few redraws a second at most: on a cheap TV box every frame drawn during
                    // a transfer cut Wi-Fi throughput from ~22 MB/s to ~1 MB/s (measured).
                    val now = System.currentTimeMillis()
                    if (now - shownAt >= PROGRESS_EVERY_MS || done == length) {
                        shownAt = now
                        _state.value = DropState.Receiving(target.name, done, length)
                    }
                }
            }
            if (done < length) {
                discard(target)
                _state.value = DropState.Failed(target.name)
                return out.error(400, "interrupted")
            }
            publish(target)
            received++
            _state.value = DropState.Received(target.uri, target.path, target.name, mime, target.folder, received)
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

    private fun InputStream.line(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
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

    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[\u0000-\u001f:*?"<>|]"""), "_").trim().take(120)
        return base.ifBlank { "file" }
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
