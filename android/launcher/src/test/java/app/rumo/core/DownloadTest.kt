package app.rumo.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Servidor local mínimo (só sockets: o `com.sun.net.httpserver` não existe no classpath dos testes do Android) que
 * entrega [data] em /file (com ou sem Range), um redirecionamento em /shortcut e um texto em /text.
 */
private class Fake(val data: ByteArray, val supportsRange: Boolean = true) {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val ranges = Collections.synchronizedList(mutableListOf<String?>())

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true) { socket.use { answer(it) } }
            }
        }
    }

    private fun answer(socket: Socket) {
        try {
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
            var range: String? = null
            while (true) {
                val line = input.readLine()
                if (line.isNullOrEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            val out = socket.getOutputStream()
            fun head(status: String, vararg headers: String, length: Long) {
                val text = (listOf("HTTP/1.1 $status") + headers + "Content-Length: $length" + "Connection: close" + "" + "")
                    .joinToString("\r\n")
                out.write(text.toByteArray(Charsets.ISO_8859_1))
            }
            when (path) {
                "/file" -> {
                    ranges += range
                    val start = if (supportsRange && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
                    if (start > 0) {
                        head("206 Partial Content", "Content-Range: bytes $start-${data.size - 1}/${data.size}", length = (data.size - start).toLong())
                    } else {
                        head("200 OK", length = data.size.toLong())
                    }
                    out.write(data, start, data.size - start)
                }
                "/shortcut" -> head("302 Found", "Location: /file", length = 0)
                "/text" -> {
                    val b = "olá, manifesto".toByteArray()
                    head("200 OK", length = b.size.toLong())
                    out.write(b)
                }
                else -> head("404 Not Found", length = 0)
            }
            out.flush()
        } catch (e: IOException) {
            // o cliente cancelou no meio: esperado nos testes de cancelamento
        }
    }

    val base: String get() = "http://127.0.0.1:${server.localPort}"

    fun stop() = server.close()
}

class DownloadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val http = Http(allowHttp = true)
    private lateinit var fake: Fake

    @Before
    fun setUp() {
        fake = Fake(data)
    }

    @After
    fun tearDown() = fake.stop()

    private fun dest() = File(tmp.root, "folder/file.bin")
    private fun part() = File(tmp.root, "folder/file.bin.part")

    @Test
    fun downloadsAndVerifies() {
        var last = 0L
        http.download("${fake.base}/file", dest(), data.size.toLong(), sha, onProgress = { last = it })
        assertArrayEquals(data, dest().readBytes())
        assertFalse(part().exists())
        assertEquals(data.size.toLong(), last)
    }

    @Test
    fun resumesFromWhatAlreadyCame() {
        part().parentFile.mkdirs()
        part().writeBytes(data.copyOfRange(0, 100_000))
        http.download("${fake.base}/file", dest(), data.size.toLong(), sha)
        assertArrayEquals(data, dest().readBytes())
        assertEquals("bytes=100000-", fake.ranges.last())
    }

    @Test
    fun serverWithoutRangeRestartsFromScratch() {
        fake.stop()
        fake = Fake(data, supportsRange = false)
        part().parentFile.mkdirs()
        part().writeBytes(data.copyOfRange(0, 100_000))
        http.download("${fake.base}/file", dest(), data.size.toLong(), sha)
        assertArrayEquals(data, dest().readBytes())
    }

    @Test
    fun followsRedirect() {
        http.download("${fake.base}/shortcut", dest(), data.size.toLong(), sha)
        assertArrayEquals(data, dest().readBytes())
    }

    @Test
    fun wrongHashLeavesNoFile() {
        try {
            http.download("${fake.base}/file", dest(), data.size.toLong(), "0".repeat(64))
            throw AssertionError("devia falhar")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("hash"))
        }
        assertFalse(dest().exists())
        assertFalse(part().exists())
    }

    @Test
    fun cancelKeepsWhatCame() {
        var done = 0L
        try {
            http.download(
                "${fake.base}/file", dest(), data.size.toLong(), sha,
                onProgress = { done = it }, cancelled = { done >= 50_000 },
            )
            throw AssertionError("devia cancelar")
        } catch (e: DownloadCancelled) {
            // esperado
        }
        assertFalse(dest().exists())
        assertTrue(part().length() in 50_000 until data.size.toLong())
    }

    @Test
    fun completePartOnlyVerifiesWithoutNewRequest() {
        part().parentFile.mkdirs()
        part().writeBytes(data)
        http.download("${fake.base}/file", dest(), data.size.toLong(), sha)
        assertArrayEquals(data, dest().readBytes())
        assertTrue(fake.ranges.isEmpty())
    }

    @Test
    fun httpErrorBecomesException() {
        try {
            http.download("${fake.base}/missing", dest(), 10, sha)
            throw AssertionError("devia falhar")
        } catch (e: HttpFailure) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun readsText() {
        assertEquals("olá, manifesto", http.getText("${fake.base}/text"))
    }

    @Test
    fun withoutPermissionOnlyAcceptsHttps() {
        try {
            Http().getText("${fake.base}/text")
            throw AssertionError("devia recusar http")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun fileHash() {
        val f = tmp.newFile("x.bin")
        f.writeBytes(data)
        assertEquals(sha, sha256Of(f))
    }
}
