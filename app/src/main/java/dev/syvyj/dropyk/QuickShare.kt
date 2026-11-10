package dev.syvyj.dropyk

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.google.android.gms.nearby.sharing.Protocol
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionResponseFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.DisconnectionFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.KeepAliveFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OsInfo
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.protobuf.ByteString
import com.google.security.cryptauth.lib.securegcm.DeviceToDeviceMessagesProto.DeviceToDeviceMessage
import com.google.security.cryptauth.lib.securegcm.SecureGcmProto.GcmMetadata
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2Alert
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2ClientFinished
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2ClientInit
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2HandshakeCipher
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2Message
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2ServerInit
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.EcP256PublicKey
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.GenericPublicKey
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.HeaderAndBody
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SecureMessage
import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.Semaphore
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Quick Share receiver: the TV shows up in the Share → Quick Share sheet of Android phones on the
 * same network, like a Mac running Dropyk. A port of NearDrop's receiving side (by Grishka, who
 * reverse engineered the protocol; see PROTOCOL.md in dropyk-mac): mDNS advertisement, UKEY2
 * key exchange, AES-256-CBC + HMAC-SHA256 messages, then the transfer frames.
 *
 * Files are saved by [Drop], to the same folders as from the phone page, and the TV asks to
 * accept each transfer with the PIN the phone shows. Wi‑Fi LAN only, while Dropyk is open or
 * receiving in background.
 */
// Holds only the application context, which lives as long as the process: no leak.
@SuppressLint("StaticFieldLeak")
object QuickShare {
    private const val TAG = "QuickShare"
    private const val SERVICE_TYPE = "_FC9F5ED42C8A._tcp"
    private lateinit var context: Context
    private var server: ServerSocket? = null
    @Volatile private var registration: NsdManager.RegistrationListener? = null
    /** Phones at once; more are turned away, so a flood of connections can't eat the box's memory. */
    private val connections = Semaphore(8)

    /** Listen and show up in phones' Share sheets; again after [stop] or a failed registration. */
    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        this.context = context.applicationContext
        val s = runCatching { ServerSocket(0) }.getOrElse { Log.w(TAG, "listen", it); return }
        server = s
        advertise(s.localPort)
        thread(name = "quickshare", isDaemon = true) {
            while (true) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                if (!connections.tryAcquire()) { runCatching { socket.close() }; continue }
                thread(isDaemon = true) { try { Connection(socket).run() } finally { connections.release() } }
            }
        }
    }

    /** Gone from the Share sheets: Dropyk closed with Receive in background off. A transfer under way finishes. */
    @Synchronized
    fun stop() {
        registration?.let { r -> runCatching { (context.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(r) } }
        registration = null
        server?.let { runCatching { it.close() } }
        server = null
    }

    /** What phones see in the sheet: the TV's name; the instance name and the "n" record are NearDrop's format. */
    private fun advertise(port: Int) {
        val id = ByteArray(4).also { r -> val a = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"; r.indices.forEach { r[it] = a[SecureRandom().nextInt(a.length)].code.toByte() } }
        // PCP 0x23, endpoint ID, service ID hash FC9F5E, two reserved bytes.
        val name = byteArrayOf(0x23) + id + byteArrayOf(0xFC.toByte(), 0x9F.toByte(), 0x5E, 0, 0)
        val info = NsdServiceInfo().apply {
            serviceName = b64(name)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("n", b64(endpointInfo(deviceName())))
        }
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                // Not visible to phones: let the next start (opening Dropyk) try again.
                Log.w(TAG, "mDNS registration failed: $code")
                registration = null
                stop()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** The name set in the TV's settings (Android 7.1+), else the model. */
    private fun deviceName(): String =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) else null)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Version/visibility/type byte (type 3 — a computer, the closest icon phones have), 16 random bytes, the name. */
    internal fun endpointInfo(name: String): ByteArray {
        val chars = name.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        return byteArrayOf((3 shl 1).toByte()) + ByteArray(16).also { SecureRandom().nextBytes(it) } + byteArrayOf(chars.size.toByte()) + chars
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private enum class State { INITIAL, GOT_REQUEST, SENT_SERVER_INIT, GOT_CLIENT_FINISH, SENT_RESPONSE, SENT_PAIRED_RESULT, GOT_PAIRED_RESULT, WAITING_CONSENT, RECEIVING, DONE }

    private class Item(val name: String, val mime: String, val size: Long, var target: Drop.Target? = null, var sink: OutputStream? = null, var done: Long = 0)

    /** One phone, one transfer. Reads on its own thread; sends are serialized. */
    private class Connection(private val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream().buffered(256 * 1024))
        private val output = socket.getOutputStream()
        // Read on this connection's thread, set by accept() on another.
        @Volatile private var state = State.INITIAL
        /** Holds the TV's one transfer slot (see Drop.claim) from the request on the screen until the end. */
        private var claimed = false
        private var device = "Android"
        private var keys: KeyPair? = null
        private var clientInit: ByteArray? = null
        private var serverInit: ByteArray? = null
        private var commitment: ByteArray? = null
        private var encrypted = false
        private var decryptKey: ByteArray? = null
        private var encryptKey: ByteArray? = null
        private var recvHmac: ByteArray? = null
        private var sendHmac: ByteArray? = null
        private var serverSeq = 0
        private var clientSeq = 0
        private var pin = ""
        private val buffers = HashMap<Long, java.io.ByteArrayOutputStream>()
        private val files = LinkedHashMap<Long, Item>()
        private var textId = 0L
        private var textIsUrl = false
        private var total = 0L
        private var received = 0L
        private var shownAt = 0L
        @Volatile private var closed = false
        /** Cancel pressed on the TV: back to the QR afterwards, not "didn't arrive in full". */
        @Volatile private var canceledHere = false
        private val cancel: () -> Unit = { thread(isDaemon = true) { cancelHere() } }

        fun run() {
            try {
                // Phones send a keep-alive every 10 s, even while the TV asks: silence this long is a dead or idle connection.
                socket.soTimeout = 120_000
                socket.use {
                    while (!closed) {
                        val length = input.readInt()
                        if (length < 0 || length > SANE_FRAME_LENGTH) error("frame length $length")
                        val frame = ByteArray(length).also { input.readFully(it) }
                        process(frame)
                    }
                }
            } catch (e: Exception) {
                if (!closed) Log.w(TAG, "connection", e)
            } finally {
                closed = true
                // Anything not finished is a partial file: remove it and say so.
                val partial = files.values.filter { it.target != null }
                partial.forEach { f -> runCatching { f.sink?.close() }; f.target?.let(Drop::discard) }
                if (claimed) {
                    if (state == State.WAITING_CONSENT) Drop.dismiss()
                    if (canceledHere) Drop.show(DropState.Waiting)
                    else if (partial.isNotEmpty()) Drop.show(DropState.Failed(partial.first().name))
                    Drop.release()
                }
            }
        }

        private fun process(frame: ByteArray) {
            when (state) {
                State.INITIAL -> connectionRequest(OfflineFrame.parseFrom(frame))
                State.GOT_REQUEST -> { clientInit = frame; ukeyClientInit(Ukey2Message.parseFrom(frame)) }
                State.SENT_SERVER_INIT -> ukeyClientFinish(Ukey2Message.parseFrom(frame), frame)
                State.GOT_CLIENT_FINISH -> connectionResponse(OfflineFrame.parseFrom(frame))
                else -> secureMessage(SecureMessage.parseFrom(frame))
            }
        }

        private fun connectionRequest(frame: OfflineFrame) {
            val info = frame.v1.connectionRequest.endpointInfo.toByteArray()
            if (info.size > 17) {
                val n = info[17].toInt() and 0xFF
                if (info.size >= 18 + n) device = String(info, 18, n, Charsets.UTF_8)
            }
            state = State.GOT_REQUEST
        }

        private fun ukeyClientInit(msg: Ukey2Message) {
            if (msg.messageType != Ukey2Message.Type.CLIENT_INIT) return alert(Ukey2Alert.AlertType.BAD_MESSAGE_TYPE)
            val init = Ukey2ClientInit.parseFrom(msg.messageData)
            if (init.version != 1) return alert(Ukey2Alert.AlertType.BAD_VERSION)
            if (init.random.size() != 32) return alert(Ukey2Alert.AlertType.BAD_RANDOM)
            commitment = init.cipherCommitmentsList.firstOrNull { it.handshakeCipher == Ukey2HandshakeCipher.P256_SHA512 }?.commitment?.toByteArray()
                ?: return alert(Ukey2Alert.AlertType.BAD_HANDSHAKE_CIPHER)
            if (init.nextProtocol != "AES_256_CBC-HMAC_SHA256") return alert(Ukey2Alert.AlertType.BAD_NEXT_PROTOCOL)

            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            keys = pair
            val w = (pair.public as ECPublicKey).w
            val key = GenericPublicKey.newBuilder()
                .setType(SecureMessageProto.PublicKeyType.EC_P256)
                .setEcP256PublicKey(
                    EcP256PublicKey.newBuilder()
                        .setX(ByteString.copyFrom(w.affineX.toByteArray()))
                        .setY(ByteString.copyFrom(w.affineY.toByteArray())),
                )
            val reply = Ukey2Message.newBuilder()
                .setMessageType(Ukey2Message.Type.SERVER_INIT)
                .setMessageData(
                    Ukey2ServerInit.newBuilder()
                        .setVersion(1)
                        .setRandom(ByteString.copyFrom(random(32)))
                        .setHandshakeCipher(Ukey2HandshakeCipher.P256_SHA512)
                        .setPublicKey(key.build().toByteString())
                        .build().toByteString(),
                ).build().toByteArray()
            serverInit = reply
            send(reply)
            state = State.SENT_SERVER_INIT
        }

        private fun ukeyClientFinish(msg: Ukey2Message, raw: ByteArray) {
            if (msg.messageType != Ukey2Message.Type.CLIENT_FINISH) error("UKEY2: no client finish")
            if (!MessageDigest.getInstance("SHA-512").digest(raw).contentEquals(commitment)) error("UKEY2: commitment mismatch")
            val peer = GenericPublicKey.parseFrom(Ukey2ClientFinished.parseFrom(msg.messageData).publicKey).ecP256PublicKey
            val pair = keys!!
            val params = (pair.public as ECPublicKey).params
            val point = ECPoint(BigInteger(1, peer.x.toByteArray()), BigInteger(1, peer.y.toByteArray()))
            val peerKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
            val shared = KeyAgreement.getInstance("ECDH").apply { init(pair.private); doPhase(peerKey, true) }.generateSecret()
            deriveKeys(MessageDigest.getInstance("SHA-256").digest(shared))
            state = State.GOT_CLIENT_FINISH
        }

        /** UKEY2 → the auth string (and its PIN) and the four SecureMessage keys, as in NearDrop. */
        private fun deriveKeys(secret: ByteArray) {
            val info = clientInit!! + serverInit!!
            val auth = hkdf(secret, "UKEY2 v1 auth".toByteArray(), info, 32)
            val next = hkdf(secret, "UKEY2 v1 next".toByteArray(), info, 32)
            pin = pinCode(auth)
            val d2dSalt = byteArrayOf(
                0x82.toByte(), 0xAA.toByte(), 0x55, 0xA0.toByte(), 0xD3.toByte(), 0x97.toByte(), 0xF8.toByte(), 0x83.toByte(), 0x46, 0xCA.toByte(), 0x1C,
                0xEE.toByte(), 0x8D.toByte(), 0x39, 0x09, 0xB9.toByte(), 0x5F, 0x13, 0xFA.toByte(), 0x7D, 0xEB.toByte(), 0x1D,
                0x4A, 0xB3.toByte(), 0x83.toByte(), 0x76, 0xB8.toByte(), 0x25, 0x6D, 0xA8.toByte(), 0x55, 0x10,
            )
            val client = hkdf(next, d2dSalt, "client".toByteArray(), 32)
            val server = hkdf(next, d2dSalt, "server".toByteArray(), 32)
            val smsgSalt = MessageDigest.getInstance("SHA-256").digest("SecureMessage".toByteArray())
            // We are the server: read with the client's keys, write with ours.
            decryptKey = hkdf(client, smsgSalt, "ENC:2".toByteArray(), 32)
            recvHmac = hkdf(client, smsgSalt, "SIG:1".toByteArray(), 32)
            encryptKey = hkdf(server, smsgSalt, "ENC:2".toByteArray(), 32)
            sendHmac = hkdf(server, smsgSalt, "SIG:1".toByteArray(), 32)
        }

        private fun connectionResponse(frame: OfflineFrame) {
            if (frame.v1.type != V1Frame.FrameType.CONNECTION_RESPONSE) return
            val accept = OfflineFrame.newBuilder().setVersion(OfflineFrame.Version.V1).setV1(
                V1Frame.newBuilder().setType(V1Frame.FrameType.CONNECTION_RESPONSE).setConnectionResponse(
                    ConnectionResponseFrame.newBuilder()
                        .setResponse(ConnectionResponseFrame.ResponseStatus.ACCEPT)
                        .setStatus(0)
                        .setOsInfo(OsInfo.newBuilder().setType(OsInfo.OsType.LINUX)),
                ),
            ).build()
            send(accept.toByteArray())
            encrypted = true
            // The phone-number pairing nobody uses: random bytes, as NearDrop does.
            sendSharing(
                Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION).setPairedKeyEncryption(
                    Protocol.PairedKeyEncryptionFrame.newBuilder()
                        .setSecretIdHash(ByteString.copyFrom(random(6)))
                        .setSignedData(ByteString.copyFrom(random(72))),
                ),
            )
            state = State.SENT_RESPONSE
        }

        private fun secureMessage(msg: SecureMessage) {
            if (!hmac(recvHmac!!, msg.headerAndBody.toByteArray()).contentEquals(msg.signature.toByteArray())) error("bad HMAC")
            val hb = HeaderAndBody.parseFrom(msg.headerAndBody)
            val plain = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(decryptKey, "AES"), IvParameterSpec(hb.header.iv.toByteArray()))
            }.doFinal(hb.body.toByteArray())
            val d2d = DeviceToDeviceMessage.parseFrom(plain)
            if (d2d.sequenceNumber != ++clientSeq) error("sequence ${d2d.sequenceNumber}, expected $clientSeq")
            val frame = OfflineFrame.parseFrom(d2d.message)
            when (frame.v1.type) {
                V1Frame.FrameType.PAYLOAD_TRANSFER -> payload(frame.v1.payloadTransfer)
                V1Frame.FrameType.KEEP_ALIVE -> sendOffline(V1Frame.newBuilder().setType(V1Frame.FrameType.KEEP_ALIVE).setKeepAlive(KeepAliveFrame.newBuilder().setAck(true)))
                V1Frame.FrameType.DISCONNECTION -> closed = true
                else -> Unit
            }
        }

        private fun payload(transfer: PayloadTransferFrame) {
            val header = transfer.payloadHeader
            val chunk = transfer.payloadChunk
            if (header.type == PayloadTransferFrame.PayloadHeader.PayloadType.FILE) return fileChunk(header.id, chunk)
            if (header.type != PayloadTransferFrame.PayloadHeader.PayloadType.BYTES) return
            if (header.totalSize > SANE_FRAME_LENGTH) error("payload too large")
            val buffer = buffers.getOrPut(header.id) { java.io.ByteArrayOutputStream() }
            if (chunk.offset != buffer.size().toLong()) error("bytes payload offset")
            // Checked against what came, not only what was announced: otherwise the buffer grows as long as chunks come.
            if (buffer.size() + chunk.body.size() > header.totalSize) error("bytes payload larger than announced")
            buffer.write(chunk.body.toByteArray())
            if (chunk.flags and 1 == 0) return
            buffers.remove(header.id)
            val bytes = buffer.toByteArray()
            if (header.id == textId && textId != 0L) {
                // Only after Accept on the TV: before, any device on the network could fill the disk with .txt files.
                if (state != State.RECEIVING) error("text before the TV accepted")
                return text(bytes)
            }
            sharing(Protocol.Frame.parseFrom(bytes))
        }

        private fun sharing(frame: Protocol.Frame) {
            if (frame.v1.type == Protocol.V1Frame.FrameType.CANCEL) return disconnect()
            when (state) {
                State.SENT_RESPONSE -> {
                    sendSharing(
                        Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.PAIRED_KEY_RESULT).setPairedKeyResult(
                            Protocol.PairedKeyResultFrame.newBuilder().setStatus(Protocol.PairedKeyResultFrame.Status.UNABLE),
                        ),
                    )
                    state = State.SENT_PAIRED_RESULT
                }
                State.SENT_PAIRED_RESULT -> state = State.GOT_PAIRED_RESULT
                State.GOT_PAIRED_RESULT -> introduction(frame.v1.introduction)
                else -> Unit
            }
        }

        /** What the phone wants to send: ask on the TV, with the PIN to compare. */
        private fun introduction(intro: Protocol.IntroductionFrame) {
            // One transfer at a time on one screen: a second phone, or the page mid-upload, would hide this request.
            if (!Drop.claim()) {
                runCatching { sendSharing(Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.RESPONSE).setConnectionResponse(Protocol.ConnectionResponseFrame.newBuilder().setStatus(Protocol.ConnectionResponseFrame.Status.REJECT))) }
                return disconnect()
            }
            claimed = true
            state = State.WAITING_CONSENT
            val what: String
            val count: Int
            when {
                intro.fileMetadataCount > 0 && intro.textMetadataCount == 0 -> {
                    intro.fileMetadataList.forEach { m ->
                        val name = Drop.safeName(m.name)
                        files[m.payloadId] = Item(name, Drop.mimeOf(name), m.size)
                    }
                    total = files.values.sumOf { it.size }
                    if (total + Drop.MARGIN > Drop.free()) return respond(Protocol.ConnectionResponseFrame.Status.NOT_ENOUGH_SPACE)
                    what = files.values.first().name
                    count = files.size
                }
                intro.textMetadataCount == 1 -> {
                    val meta = intro.getTextMetadata(0)
                    textId = meta.payloadId
                    textIsUrl = meta.type == Protocol.TextMetadata.Type.URL
                    what = meta.textTitle
                    count = 1
                }
                else -> return respond(Protocol.ConnectionResponseFrame.Status.UNSUPPORTED_ATTACHMENT_TYPE)
            }
            Drop.show(DropState.Incoming(device, pin, what, count, accept = { thread(isDaemon = true) { accept() } }, decline = { thread(isDaemon = true) { respond(Protocol.ConnectionResponseFrame.Status.REJECT) } }))
        }

        /** Files are created as they arrive, not here: hundreds of photos opened at once would eat a TV box's memory. */
        @Synchronized private fun accept() {
            if (state != State.WAITING_CONSENT || closed) return
            state = State.RECEIVING
            Drop.show(DropState.Receiving(files.values.firstOrNull()?.name ?: device, 0, total.coerceAtLeast(1), cancel))
            runCatching {
                sendSharing(
                    Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.RESPONSE).setConnectionResponse(
                        Protocol.ConnectionResponseFrame.newBuilder().setStatus(Protocol.ConnectionResponseFrame.Status.ACCEPT),
                    ),
                )
            }.onFailure { Log.w(TAG, "accept", it); disconnect() }
        }

        private fun respond(status: Protocol.ConnectionResponseFrame.Status) {
            runCatching {
                sendSharing(Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.RESPONSE).setConnectionResponse(Protocol.ConnectionResponseFrame.newBuilder().setStatus(status)))
            }
            Drop.dismiss()
            disconnect()
        }

        private fun fileChunk(id: Long, chunk: PayloadTransferFrame.PayloadChunk) {
            if (state != State.RECEIVING) error("file data before the TV accepted")
            val f = files[id] ?: error("unknown file payload $id")
            if (chunk.offset != f.done) error("file offset ${chunk.offset}, expected ${f.done}")
            val body = chunk.body
            if (f.done + body.size() > f.size) error("file larger than announced")
            val sink = f.sink ?: Drop.create(f.name, f.mime, Drop.sorted).let { target ->
                f.target = target
                (context.contentResolver.openOutputStream(target.uri) ?: error("no stream")).buffered(256 * 1024).also { f.sink = it }
            }
            if (!body.isEmpty) {
                body.writeTo(sink)
                f.done += body.size()
                received += body.size()
                // A few redraws a second at most, as for the phone page.
                val now = System.currentTimeMillis()
                if (now - shownAt >= Drop.PROGRESS_EVERY_MS) {
                    shownAt = now
                    Drop.show(DropState.Receiving(f.name, received, total.coerceAtLeast(1), cancel))
                }
            }
            if (chunk.flags and 1 == 1) {
                // Ended short of its announced size: a broken file, not a received one.
                if (f.done != f.size) error("file ended at ${f.done} of ${f.size}")
                sink.close()
                f.sink = null
                Drop.publish(f.target!!)
                Drop.received(f.target!!, f.mime)
                f.target = null
                files.remove(id)
                if (files.isEmpty()) disconnect()
            }
        }

        /** Like text from the phone page: a link to open, short text on screen; long text is saved as a .txt file. */
        private fun text(bytes: ByteArray) {
            val text = String(bytes, Charsets.UTF_8).trim()
            if (text.isEmpty()) return disconnect()
            if (bytes.size <= Drop.MAX_TEXT) {
                // A link marked as plain text still opens; text marked as a link but not one is shown.
                Drop.show(if (textIsUrl) Drop.textState(text) else DropState.Text(text))
            } else {
                val name = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.ROOT).format(java.util.Date()) + ".txt"
                val target = Drop.create(name, "text/plain", Drop.sorted)
                context.contentResolver.openOutputStream(target.uri)!!.use { it.write(bytes) }
                Drop.publish(target)
                Drop.received(target, "text/plain")
            }
            disconnect()
        }

        /** Tells the phone the TV canceled, then hangs up; run() removes the partial files. */
        private fun cancelHere() {
            if (closed) return
            canceledHere = true
            runCatching { sendSharing(Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.CANCEL)) }
            disconnect()
        }

        private fun disconnect() {
            runCatching { sendOffline(V1Frame.newBuilder().setType(V1Frame.FrameType.DISCONNECTION).setDisconnection(DisconnectionFrame.getDefaultInstance())) }
            closed = true
            runCatching { socket.shutdownInput() }
        }

        private fun alert(type: Ukey2Alert.AlertType) {
            send(Ukey2Message.newBuilder().setMessageType(Ukey2Message.Type.ALERT).setMessageData(Ukey2Alert.newBuilder().setType(type).build().toByteString()).build().toByteArray())
            error("UKEY2 alert $type")
        }

        private fun sendSharing(v1: Protocol.V1Frame.Builder) {
            val bytes = Protocol.Frame.newBuilder().setVersion(Protocol.Frame.Version.V1).setV1(v1).build().toByteArray()
            val id = SecureRandom().nextLong()
            fun chunk(body: ByteArray, offset: Long, flags: Int) = V1Frame.newBuilder().setType(V1Frame.FrameType.PAYLOAD_TRANSFER).setPayloadTransfer(
                PayloadTransferFrame.newBuilder()
                    .setPacketType(PayloadTransferFrame.PacketType.DATA)
                    .setPayloadHeader(
                        PayloadTransferFrame.PayloadHeader.newBuilder().setId(id)
                            .setType(PayloadTransferFrame.PayloadHeader.PayloadType.BYTES)
                            .setTotalSize(bytes.size.toLong()).setIsSensitive(false),
                    )
                    .setPayloadChunk(PayloadTransferFrame.PayloadChunk.newBuilder().setOffset(offset).setFlags(flags).setBody(ByteString.copyFrom(body))),
            )
            // The body, then an empty last chunk.
            sendOffline(chunk(bytes, 0, 0))
            sendOffline(chunk(ByteArray(0), bytes.size.toLong(), 1))
        }

        @Synchronized private fun sendOffline(v1: V1Frame.Builder) {
            val frame = OfflineFrame.newBuilder().setVersion(OfflineFrame.Version.V1).setV1(v1).build().toByteArray()
            if (!encrypted) return send(frame)
            val d2d = DeviceToDeviceMessage.newBuilder().setSequenceNumber(++serverSeq).setMessage(ByteString.copyFrom(frame)).build().toByteArray()
            val iv = random(16)
            val body = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptKey, "AES"), IvParameterSpec(iv))
            }.doFinal(d2d)
            val headerAndBody = HeaderAndBody.newBuilder()
                .setHeader(
                    SecureMessageProto.Header.newBuilder()
                        .setEncryptionScheme(SecureMessageProto.EncScheme.AES_256_CBC)
                        .setSignatureScheme(SecureMessageProto.SigScheme.HMAC_SHA256)
                        .setIv(ByteString.copyFrom(iv))
                        .setPublicMetadata(GcmMetadata.newBuilder().setType(com.google.security.cryptauth.lib.securegcm.SecureGcmProto.Type.DEVICE_TO_DEVICE_MESSAGE).setVersion(1).build().toByteString()),
                )
                .setBody(ByteString.copyFrom(body)).build().toByteArray()
            send(SecureMessage.newBuilder().setHeaderAndBody(ByteString.copyFrom(headerAndBody)).setSignature(ByteString.copyFrom(hmac(sendHmac!!, headerAndBody))).build().toByteArray())
        }

        @Synchronized private fun send(frame: ByteArray) {
            output.write(byteArrayOf((frame.size ushr 24).toByte(), (frame.size ushr 16).toByte(), (frame.size ushr 8).toByte(), frame.size.toByte()))
            output.write(frame)
            output.flush()
        }

        private val context get() = QuickShare.context
    }

    internal const val SANE_FRAME_LENGTH = 5 * 1024 * 1024

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** RFC 5869 with SHA-256. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        var t = ByteArray(0)
        val okm = java.io.ByteArrayOutputStream()
        var i = 1
        while (okm.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(i++.toByte()))
            okm.write(t)
        }
        return okm.toByteArray().copyOf(length)
    }

    /** The 4-digit PIN both screens show, from the UKEY2 auth string (NearDrop's formula). */
    internal fun pinCode(auth: ByteArray): String {
        var hash = 0
        var multiplier = 1
        for (b in auth) {
            hash = (hash + b.toInt() * multiplier) % 9973
            multiplier = (multiplier * 31) % 9973
        }
        return "%04d".format(kotlin.math.abs(hash))
    }
}
