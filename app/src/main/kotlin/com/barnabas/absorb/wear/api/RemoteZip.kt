package com.barnabas.absorb.wear.api

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import java.util.zip.Inflater
import kotlin.math.max

/**
 * A zip (an EPUB) on a server, read with HTTP range requests: its index and
 * the files it holds, one at a time, without pulling the whole thing. A
 * narrated EPUB on Audiobookshelf can run to hundreds of megabytes of
 * audio, and the watch wants its package, its timing files, and then one
 * chapter's audio at a time.
 *
 * [headers] is read on every request so a refreshed token is used.
 */
class RemoteZip private constructor(
    private val url: String,
    private val headers: () -> Map<String, String>,
    val length: Long,
    val entries: Map<String, Entry>,
) {
    class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val headerOffset: Long,
    ) {
        val stored: Boolean get() = method == 0
    }

    class RemoteZipException(message: String) : IOException(message)

    fun entry(name: String): Entry? = entries[name]

    private val offsets = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The request headers a reader outside this class should send. */
    fun requestHeaders(): Map<String, String> = headers()

    /** A small file (a package or timing file) as bytes, in one request
     *  when the local header's extra field is of ordinary size. */
    fun read(e: Entry): ByteArray {
        val nameLen = e.name.toByteArray(Charsets.UTF_8).size
        val head = range(e.headerOffset, 30L + nameLen + 256 + e.compressedSize)
        if (head.limit() < 30 || head.getInt(0) != LOCAL_HEADER) throw RemoteZipException("local header not found for ${e.name}")
        val start = 30 + (head.getShort(26).toInt() and 0xFFFF) + (head.getShort(28).toInt() and 0xFFFF)
        offsets[e.name] = e.headerOffset + start
        val raw: InputStream = if (start + e.compressedSize <= head.limit()) {
            java.io.ByteArrayInputStream(head.array(), start, e.compressedSize.toInt())
        } else {
            openRange(e.headerOffset + start, e.headerOffset + start + e.compressedSize - 1)
        }
        raw.use { stream ->
            return when (e.method) {
                0 -> stream.readBytes()
                8 -> InflaterInputStream(stream, Inflater(true)).use { it.readBytes() }
                else -> throw RemoteZipException("unsupported compression ${e.method} for ${e.name}")
            }
        }
    }

    /** A file (a chapter's audio) written to [out], streamed through. */
    fun download(e: Entry, out: File) {
        val start = dataOffset(e)
        openRange(start, start + e.compressedSize - 1).use { raw ->
            val stream = when (e.method) {
                0 -> raw
                8 -> InflaterInputStream(raw, Inflater(true))
                else -> throw RemoteZipException("unsupported compression ${e.method} for ${e.name}")
            }
            FileOutputStream(out).use { stream.copyTo(it, 64 * 1024) }
        }
    }

    /** Where [e]'s bytes start in the zip, so a stored file can be read as
     *  a plain range of the zip itself. Blocking the first time per entry. */
    fun dataOffset(e: Entry): Long = offsets.getOrPut(e.name) {
        val local = range(e.headerOffset, 30)
        if (local.getInt(0) != LOCAL_HEADER) throw RemoteZipException("local header not found for ${e.name}")
        val nameLen = local.getShort(26).toInt() and 0xFFFF
        val extraLen = local.getShort(28).toInt() and 0xFFFF
        e.headerOffset + 30 + nameLen + extraLen
    }

    private fun range(start: Long, count: Long): ByteBuffer =
        openRange(start, start + count - 1).use { ByteBuffer.wrap(it.readBytes()).order(ByteOrder.LITTLE_ENDIAN) }

    private fun openRange(start: Long, end: Long): InputStream = openRange(url, headers(), start, end).first

    companion object {
        private const val EOCD = 0x06054b50
        private const val EOCD64_LOCATOR = 0x07064b50
        private const val EOCD64 = 0x06064b50
        private const val CENTRAL_HEADER = 0x02014b50
        private const val LOCAL_HEADER = 0x04034b50

        /** Read the zip's index. Blocking; call off the main thread. */
        fun open(url: String, headers: () -> Map<String, String>): RemoteZip {
            val (probe, total) = openRange(url, headers(), 0, 0)
            probe.close()
            if (total <= 0) throw RemoteZipException("server did not say how long the file is")
            val tailStart = max(0L, total - (22 + 65535))
            val tail = bytes(url, headers, tailStart, total - tailStart)
            val eocdAt = lastIndexOf(tail, EOCD)
            if (eocdAt < 0) throw RemoteZipException("not a zip (no end record)")
            var cdSize = (tail.getInt(eocdAt + 12).toLong() and 0xFFFFFFFFL)
            var cdOffset = (tail.getInt(eocdAt + 16).toLong() and 0xFFFFFFFFL)
            val count = tail.getShort(eocdAt + 10).toInt() and 0xFFFF
            if (cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL || count == 0xFFFF) {
                val locatorAt = eocdAt - 20
                if (locatorAt < 0 || tail.getInt(locatorAt) != EOCD64_LOCATOR) throw RemoteZipException("zip64 locator missing")
                val eocd64Offset = tail.getLong(locatorAt + 8)
                val eocd64 = if (eocd64Offset >= tailStart) {
                    tail.position((eocd64Offset - tailStart).toInt())
                    tail.slice().order(ByteOrder.LITTLE_ENDIAN)
                } else {
                    bytes(url, headers, eocd64Offset, 56)
                }
                if (eocd64.getInt(0) != EOCD64) throw RemoteZipException("zip64 end record missing")
                cdSize = eocd64.getLong(40)
                cdOffset = eocd64.getLong(48)
            }
            val cd = if (cdOffset >= tailStart && cdOffset + cdSize <= total) {
                tail.position((cdOffset - tailStart).toInt())
                tail.slice().order(ByteOrder.LITTLE_ENDIAN).also { it.limit(cdSize.toInt()) }
            } else {
                bytes(url, headers, cdOffset, cdSize)
            }
            return RemoteZip(url, headers, total, parseCentral(cd))
        }

        private fun bytes(url: String, headers: () -> Map<String, String>, start: Long, count: Long): ByteBuffer {
            val (stream, _) = openRange(url, headers(), start, start + count - 1)
            return stream.use { ByteBuffer.wrap(it.readBytes()).order(ByteOrder.LITTLE_ENDIAN) }
        }

        /** The body of a range request and the file's total length. */
        private fun openRange(url: String, headers: Map<String, String>, start: Long, end: Long): Pair<InputStream, Long> {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=$start-$end")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "AbsorbWear/0.2.0")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code != 206) {
                conn.disconnect()
                throw RemoteZipException("server answered $code to a range request")
            }
            val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
            return conn.inputStream to total
        }

        private fun lastIndexOf(b: ByteBuffer, signature: Int): Int {
            var i = b.limit() - 4
            while (i >= 0) {
                if (b.getInt(i) == signature) return i
                i--
            }
            return -1
        }

        private fun parseCentral(cd: ByteBuffer): Map<String, Entry> {
            val out = LinkedHashMap<String, Entry>()
            var p = 0
            while (p + 46 <= cd.limit() && cd.getInt(p) == CENTRAL_HEADER) {
                val method = cd.getShort(p + 10).toInt() and 0xFFFF
                var csize = cd.getInt(p + 20).toLong() and 0xFFFFFFFFL
                var usize = cd.getInt(p + 24).toLong() and 0xFFFFFFFFL
                val n = cd.getShort(p + 28).toInt() and 0xFFFF
                val m = cd.getShort(p + 30).toInt() and 0xFFFF
                val k = cd.getShort(p + 32).toInt() and 0xFFFF
                var offset = cd.getInt(p + 42).toLong() and 0xFFFFFFFFL
                val nameBytes = ByteArray(n)
                for (i in 0 until n) nameBytes[i] = cd.get(p + 46 + i)
                val name = String(nameBytes, Charsets.UTF_8)
                // Zip64 extra field: the 64-bit values replace whichever
                // 32-bit ones are maxed out, in this order.
                var q = p + 46 + n
                val extraEnd = q + m
                while (q + 4 <= extraEnd) {
                    val id = cd.getShort(q).toInt() and 0xFFFF
                    val size = cd.getShort(q + 2).toInt() and 0xFFFF
                    if (id == 1) {
                        var f = q + 4
                        if (usize == 0xFFFFFFFFL && f + 8 <= q + 4 + size) { usize = cd.getLong(f); f += 8 }
                        if (csize == 0xFFFFFFFFL && f + 8 <= q + 4 + size) { csize = cd.getLong(f); f += 8 }
                        if (offset == 0xFFFFFFFFL && f + 8 <= q + 4 + size) { offset = cd.getLong(f) }
                    }
                    q += 4 + size
                }
                out[name] = Entry(name, method, csize, usize, offset)
                p += 46 + n + m + k
            }
            return out
        }
    }
}
