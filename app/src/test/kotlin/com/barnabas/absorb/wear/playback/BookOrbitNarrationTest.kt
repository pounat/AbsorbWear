package com.barnabas.absorb.wear.playback

import com.barnabas.absorb.wear.api.RemoteZip
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class BookOrbitNarrationTest {

    @Test
    fun escapesBookOrbitUndoesItselfAreLeftToIt() {
        assertTrue(BookOrbitPlayback.narrationHrefServable("Audio/00000-00001.mp4"))
        assertTrue(BookOrbitPlayback.narrationHrefServable("Audio/Two Necromancers.m4b"))
        assertTrue(BookOrbitPlayback.narrationHrefServable("Audio/Book%205.m4b"))
        assertFalse(BookOrbitPlayback.narrationHrefServable("Audio/Rock%26Roll.m4b"))
        assertFalse(BookOrbitPlayback.narrationHrefServable("Audio/A%2cB.m4b"))
        assertFalse(BookOrbitPlayback.narrationHrefServable("OPS/audio/Part Two%2C Book 5.5.m4b"))
    }

    @Test
    fun aFileUnderItsEscapedNameIsFoundAndReadAsARange() {
        val zip = RemoteZip.open(url) { mapOf("Authorization" to "Bearer tok") }
        val entry = AbsNarration.entryFor(zip, "OPS/audio/Part Two%2C Book 5.5.m4b")
        assertEquals("OPS/audio/Part Two, Book 5.5.m4b", entry?.name)
        assertTrue(entry!!.stored)
        val at = zip.dataOffset(entry)
        assertArrayEquals(commaAudio.copyOfRange(1000, 2000), get(at + 1000, at + 1999))
    }

    @Test
    fun aNameRelativeToThePackageStillFindsItsFile() {
        val zip = RemoteZip.open(url) { mapOf("Authorization" to "Bearer tok") }
        assertEquals("OPS/audio/two.mp3", AbsNarration.entryFor(zip, "audio/two.mp3")?.name)
        assertNull(AbsNarration.entryFor(zip, "OPS/audio/gone%2C.mp3"))
    }

    private fun get(start: Long, end: Long): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer tok")
        conn.setRequestProperty("Range", "bytes=$start-$end")
        return conn.inputStream.use { it.readBytes() }
    }

    companion object {
        private val commaAudio = ByteArray(50_000) { (it * 7 % 256).toByte() }
        private val packed = ByteArray(30_000) { (it / 40 % 7).toByte() }
        private lateinit var epub: ByteArray
        private lateinit var server: ServerSocket
        private lateinit var url: String

        @BeforeClass
        @JvmStatic
        fun serveEpub() {
            epub = ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { z ->
                    z.putNextEntry(stored("mimetype", "application/epub+zip".toByteArray()))
                    z.write("application/epub+zip".toByteArray())
                    z.putNextEntry(stored("OPS/audio/Part Two, Book 5.5.m4b", commaAudio))
                    z.write(commaAudio)
                    z.putNextEntry(ZipEntry("OPS/audio/two.mp3"))
                    z.write(packed)
                }
            }.toByteArray()
            // BookOrbit's file download as far as the watch reads it: ranges
            // of the EPUB behind the account token.
            server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use { answer(it) }
                }
            }
            url = "http://127.0.0.1:${server.localPort}/api/v1/books/files/55/download"
        }

        private fun answer(socket: Socket) {
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val out = socket.getOutputStream()
            val range = headers["range"]
            if (!requestLine.contains("/api/v1/books/files/55/download") || headers["authorization"] != "Bearer tok" || range == null) {
                out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val (a, b) = range.removePrefix("bytes=").split('-')
            val start = a.toInt()
            val end = minOf(b.toInt(), epub.size - 1)
            out.write(
                ("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${epub.size}\r\n" +
                    "Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(epub, start, end - start + 1)
            out.flush()
        }

        @AfterClass
        @JvmStatic
        fun stop() = server.close()

        private fun stored(name: String, bytes: ByteArray) = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().also { it.update(bytes) }.value
        }
    }
}
