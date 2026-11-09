package slop.miho.chumpy.sub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files

/**
 * The phone import receiver's wire, exactly as the phone's page speaks it: the page and its
 * session token, the PIN's verdicts, the payload rules and the lockout. All pure JVM — the
 * server's instance is its own logic, only its companion [PhoneImportServer.open] touches
 * Android.
 */
class PhoneImportServerTest {
    private lateinit var temporary: File
    private val servers = mutableListOf<PhoneImportServer>()

    @Before
    fun setUp() {
        temporary = Files.createTempDirectory("phone-import-test").toFile()
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        temporary.deleteRecursively()
    }

    /** A receiver serving a page with the session placeholder, as the real pages carry it. */
    private fun receiver(image: Boolean): PhoneImportServer {
        val page = "<html><script>const session='__CHUMPY_SESSION__';</script></html>"
        return PhoneImportServer(InetAddress.getLoopbackAddress(), page, imageDirectory = if (image) temporary else null)
            .also { servers.add(it) }
    }

    private class Reply(val status: Int, val body: String)

    /** One raw HTTP exchange; the caller spells every header, the wire only frames it. */
    private fun speak(server: PhoneImportServer, method: String, path: String, headers: Map<String, String>, body: ByteArray = ByteArray(0)): Reply {
        val authority = server.state.value.url.removePrefix("http://").trimEnd('/')
        val port = authority.substringAfter(':').toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.getOutputStream().apply {
                write("$method $path HTTP/1.1\r\n".toByteArray())
                headers.forEach { (key, value) -> write("$key: $value\r\n".toByteArray()) }
                write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                if (body.isNotEmpty()) write(body)
                flush()
            }
            // Every answer closes the connection; read to the wire's end.
            val text = socket.getInputStream().use { it.readBytes() }.toString(Charsets.UTF_8)
            return Reply(text.substringAfter(" ").substringBefore(" ").toInt(), text.substringAfter("\r\n\r\n", ""))
        }
    }

    /** The headers a request of this server's page would naturally carry. */
    private fun speaking(server: PhoneImportServer, pin: String, session: String, type: String): Map<String, String> {
        val origin = server.state.value.url.trimEnd('/')
        return mapOf(
            "Host" to server.state.value.url.removePrefix("http://").trimEnd('/'),
            "Origin" to origin,
            "X-Chumpy-Session" to session,
            "Authorization" to "Bearer $pin",
            "Content-Type" to type,
        )
    }

    /** The session token, exactly where the page carries it. */
    private fun sessionOf(page: String): String {
        val match = Regex("const session='([0-9a-f]{48})'").find(page) ?: error("No session token in page")
        return match.groupValues[1]
    }

    @Test
    fun `the page answers its own host and session, strangers get nothing`() {
        val server = receiver(image = false)
        // A request that does not name this server gets nothing — the page stays ours.
        val stray = speak(server, "GET", "/", emptyMap())
        assertEquals(403, stray.status)
        assertEquals("Forbidden", stray.body)
        val page = speak(server, "GET", "/", mapOf("Host" to server.state.value.url.removePrefix("http://").trimEnd('/'))).let {
            assertEquals(200, it.status)
            it.body
        }
        // The placeholder token is replaced by the page's own, and the pin is six digits.
        assertFalse(page.contains("__CHUMPY_SESSION__"))
        sessionOf(page)
        assertEquals(6, server.state.value.pin.length)
        assertTrue(server.state.value.pin.all { it.isDigit() })
    }

    @Test
    fun `the wallpaper arrives as a file with exactly the bytes sent`() {
        val server = receiver(image = true)
        val session = sessionOf(speak(server, "GET", "/", mapOf("Host" to server.state.value.url.removePrefix("http://").trimEnd('/'))).body)
        val image = ByteArray(300_000) { (it * 31).toByte() }
        val reply = speak(server, "POST", "/import",
            speaking(server, server.state.value.pin, session, "application/octet-stream"), image)
        assertEquals(202, reply.status)
        assertFalse(server.state.value.expired)
        val received = File(server.state.value.pending ?: error("No pending path"))
        assertTrue(received.isFile)
        assertEquals(image.size.toLong(), received.length())
        assertTrue(image.contentEquals(received.readBytes()))
        // Closing the session must not touch the received file: the confirm may still be
        // reading it — this is the very race the receiver once lost its temp file to.
        server.close()
        assertTrue(received.isFile)
        assertTrue(image.contentEquals(received.readBytes()))
    }

    @Test
    fun `a wrong pin never gets in and the eighth locks the session`() {
        val server = receiver(image = false)
        val session = sessionOf(speak(server, "GET", "/", mapOf("Host" to server.state.value.url.removePrefix("http://").trimEnd('/'))).body)
        // A wrong pin answers 401 without a payload verdict.
        assertEquals(401, speak(server, "POST", "/import",
            speaking(server, "000000", session, "text/plain"), "x".toByteArray()).status)
        // A mismatched session is a stranger even with the right pin — and does not burn an attempt.
        assertEquals(403, speak(server, "POST", "/import",
            speaking(server, server.state.value.pin, "0".repeat(48), "text/plain"), "x".toByteArray()).status)
        repeat(6) {
            assertEquals(401, speak(server, "POST", "/import",
                speaking(server, "000000", session, "text/plain"), "x".toByteArray()).status)
        }
        // The eighth wrong pin (the one above plus these seven) locks the session for good.
        assertEquals(429, speak(server, "POST", "/import",
            speaking(server, "000000", session, "text/plain"), "x".toByteArray()).status)
        assertTrue(server.state.value.expired)
    }

    @Test
    fun `a subscription text arrives trimmed and the session dies with its payload spent`() {
        val server = receiver(image = false)
        val session = sessionOf(speak(server, "GET", "/", mapOf("Host" to server.state.value.url.removePrefix("http://").trimEnd('/'))).body)
        val reply = speak(server, "POST", "/import",
            speaking(server, server.state.value.pin, session, "text/plain"), "  vless://example  ".toByteArray())
        assertEquals(202, reply.status)
        assertEquals("vless://example", server.state.value.pending)
        // The payload spends the session: the receiver stops listening for a second helping.
        server.close()
        assertNotNull(server.state.value.pending)
    }
}
