package slop.miho.chumpy.sub

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

class PhoneImportServer internal constructor(
    address: InetAddress,
    private val page: String,
    private val lifetimeMillis: Long = 300_000,
    private val imageDirectory: File? = null,
) : Closeable {
    data class State(val url: String, val pin: String, val pending: String? = null, val expired: Boolean = false)

    companion object {
        const val MAX_BODY_BYTES = 256 * 1024
        const val MAX_IMAGE_BYTES = 40 * 1024 * 1024

        private val active = java.util.concurrent.atomic.AtomicReference<PhoneImportServer?>(null)

        fun open(context: Context, page: String, image: Boolean = false): PhoneImportServer {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val address = connectivity.allNetworks.asSequence().filter { network ->
                connectivity.getNetworkCapabilities(network)?.let { caps ->
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                } == true
            }.flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
                .map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?: throw IOException("No local network")
            active.getAndSet(null)?.close()
            context.cacheDir.listFiles()?.filter {
                it.isFile && it.name.startsWith("phone-wallpaper-") &&
                    it.lastModified() < System.currentTimeMillis() - 300_000
            }?.forEach { it.delete() }
            return PhoneImportServer(address, page, imageDirectory = if (image) context.cacheDir else null)
                .also { active.getAndSet(it)?.close() }
        }
    }

    private val random = SecureRandom()
    private val session = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private val pin = (100_000 + random.nextInt(900_000)).toString()
    private val listener = ServerSocket().apply {
        try {
            reuseAddress = true
            bind(InetSocketAddress(address, 0), 2)
            soTimeout = 1_000
        } catch (failure: Exception) { close(); throw failure }
    }
    private val authority = "${address.hostAddress}:${listener.localPort}"
    private val origin = "http://$authority"
    private val mutableState = MutableStateFlow(State("$origin/", pin))
    val state = mutableState.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var client: Socket? = null
    @Volatile private var receivedImage: File? = null
    private var invalidAttempts = 0
    private val startedAt = System.nanoTime()
    private val expiresAt = startedAt + lifetimeMillis * 1_000_000
    private val expiry = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "phone-import-expiry").apply { isDaemon = true }
    }
    private val worker = thread(name = "phone-import", isDaemon = true) {
        try {
            while (!closed && mutableState.value.pending == null) {
                if (System.nanoTime() >= expiresAt) { expire(); break }
                try {
                    listener.accept().use { socket ->
                        client = socket
                        socket.soTimeout = 5_000
                        try { if (!closed) serve(socket) } catch (_: Exception) {
                            runCatching { respond(socket, 400, "Bad request") }
                        } finally { client = null }
                    }
                } catch (_: SocketTimeoutException) { }
            }
        } catch (_: IOException) {
            if (!closed && mutableState.value.pending == null) expire()
        } finally { expiry.shutdownNow(); runCatching { listener.close() } }
    }

    init {
        synchronized(this) {
            if (!closed && mutableState.value.pending == null) {
                expiry.schedule({ expire() }, lifetimeMillis.coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    @Synchronized
    private fun expire() {
        if (closed || mutableState.value.pending != null) return
        mutableState.value = mutableState.value.copy(expired = true)
        close()
    }

    @Synchronized
    private fun accept(socket: Socket, payload: String) {
        if (closed || System.nanoTime() >= expiresAt) {
            expire()
            throw IOException("Session closed")
        }
        listener.close()
        expiry.shutdownNow()
        mutableState.value = mutableState.value.copy(pending = payload)
        runCatching { respond(socket, 202, "Accepted") }
    }

    private fun serve(socket: Socket) {
        if (closed || System.nanoTime() >= expiresAt) { expire(); return }
        val input = socket.getInputStream().buffered()
        val deadline = System.nanoTime() + 5_000_000_000L
        var consumed = 0
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                if (++consumed > 8192 || System.nanoTime() > deadline) throw IOException("Header limit")
                val next = input.read()
                if (next < 0) throw IOException("Incomplete request")
                if (next == 10) break
                bytes.write(next)
            }
            return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
        }
        val request = line().split(' ')
        if (request.size != 3 || request[2] !in setOf("HTTP/1.0", "HTTP/1.1")) throw IOException("Request")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator <= 0) throw IOException("Header")
            val key = header.substring(0, separator).trim().lowercase(java.util.Locale.ROOT)
            if (headers.put(key, header.substring(separator + 1).trim()) != null) throw IOException("Duplicate header")
        }
        if (headers["host"] != authority || headers["origin"]?.let { it != origin } == true) {
            respond(socket, 403, "Forbidden")
            return
        }
        if (request[0] == "GET" && request[1] == "/") {
            respond(socket, 200, page.replace("__CHUMPY_SESSION__", session), "text/html; charset=utf-8")
            return
        }
        if (request[0] != "POST" || request[1] != "/import") {
            respond(socket, 404, "Not found")
            return
        }
        if (headers["origin"] != origin || headers["x-chumpy-session"] != session) {
            respond(socket, 403, "Invalid session")
            return
        }
        val authorization = headers["authorization"].orEmpty()
        val credential = if (authorization.startsWith("Bearer ")) authorization.removePrefix("Bearer ") else ""
        if (!credential.matches(Regex("[0-9]{6}")) ||
            !MessageDigest.isEqual(credential.toByteArray(Charsets.US_ASCII), pin.toByteArray(Charsets.US_ASCII))) {
            invalidAttempts++
            val locked = invalidAttempts >= 8
            respond(socket, if (locked) 429 else 401, if (locked) "Session locked" else "Invalid code")
            if (locked) expire()
            return
        }
        val length = headers["content-length"]?.toIntOrNull()
        if (headers.containsKey("transfer-encoding") || length == null || length !in 1..(if (imageDirectory != null) MAX_IMAGE_BYTES else MAX_BODY_BYTES) ||
            headers["content-type"]?.substringBefore(';')?.trim() !=
                (if (imageDirectory != null) "application/octet-stream" else "text/plain")) {
            respond(socket, 413, "Invalid payload")
            return
        }
        if (imageDirectory != null) {
            val image = File.createTempFile("phone-wallpaper-", ".upload", imageDirectory)
            var accepted = false
            try {
                image.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    val bodyDeadline = minOf(expiresAt, System.nanoTime() + 60_000_000_000L)
                    while (remaining > 0) {
                        if (closed || System.nanoTime() > bodyDeadline) throw IOException("Body deadline")
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count <= 0) throw IOException("Incomplete payload")
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                synchronized(this) {
                    if (closed) throw IOException("Closed")
                    receivedImage = image
                }
                accept(socket, image.absolutePath)
                accepted = true
            } finally {
                if (!accepted) { image.delete(); receivedImage = null }
            }
            return
        }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            if (System.nanoTime() > deadline) throw IOException("Body deadline")
            val count = input.read(bytes, offset, length - offset)
            if (count <= 0) throw IOException("Incomplete payload")
            offset += count
        }
        val body = bytes.toString(Charsets.UTF_8).trim()
        if (body.isBlank()) { respond(socket, 400, "Empty payload"); return }
        accept(socket, body)
    }

    private fun respond(socket: Socket, status: Int, body: String, type: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status Response\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src blob:; connect-src 'self'; form-action 'none'; frame-ancestors 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    @Synchronized
    override fun close() {
        closed = true
        expiry.shutdownNow()
        active.compareAndSet(this, null)
        runCatching { listener.close() }
        runCatching { client?.close() }
        receivedImage?.delete()
    }
}

internal class PhoneImportSession : Closeable {
    private var closed = false
    private var receiver: PhoneImportServer? = null

    @Synchronized
    fun open(factory: () -> PhoneImportServer): PhoneImportServer? {
        if (closed) return null
        return receiver ?: factory().also { receiver = it }
    }

    @Synchronized
    override fun close() {
        closed = true
        receiver?.close()
        receiver = null
    }
}
