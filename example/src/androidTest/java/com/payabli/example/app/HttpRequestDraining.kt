package com.payabli.example.app

import java.io.IOException
import java.io.InputStream

/**
 * Reads a whole HTTP request, so a reply is never written while the client is still sending.
 *
 * Answering after only the request line lets the close race the client's write, which the app reports as a
 * reset rather than as the response it was actually sent.
 *
 * Bytes throughout, because `Content-Length` counts bytes.
 *
 * Bounded in both directions, and the caller sets a read timeout. This runs on the accept thread, so a client
 * that never terminates its headers, or declares more body than it sends, would wedge the server rather than
 * fail: the run then times out somewhere else entirely.
 */
internal fun drainHttpRequest(stream: InputStream) {
    var length = 0
    var headerBytes = 0
    while (true) {
        val line = readLine(stream, MAX_HEADER_BYTES - headerBytes) ?: return
        headerBytes += line.length + 2
        if (headerBytes > MAX_HEADER_BYTES) throw IOException("request headers exceeded $MAX_HEADER_BYTES bytes")
        if (line.isEmpty()) break
        if (line.substringBefore(':').equals("Content-Length", ignoreCase = true)) {
            length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
    }

    var remaining = minOf(length, MAX_BODY_BYTES)
    val chunk = ByteArray(DRAIN_CHUNK)
    while (remaining > 0) {
        val read = stream.read(chunk, 0, minOf(chunk.size, remaining))
        if (read < 0) return
        remaining -= read
    }
}

/**
 * One header line, without its terminator, or null at end of stream.
 *
 * A byte at a time, so the stream is left positioned exactly after the blank line for the body read above.
 */
private fun readLine(
    stream: InputStream,
    limit: Int,
): String? {
    val line = StringBuilder()
    while (true) {
        if (line.length > limit) throw IOException("header line exceeded $MAX_HEADER_BYTES bytes")
        val byte = stream.read()
        if (byte < 0) return if (line.isEmpty()) null else line.toString()
        if (byte == '\n'.code) return line.toString().removeSuffix("\r")
        line.append(byte.toChar())
    }
}

private const val DRAIN_CHUNK = 1024

/** Generous next to the requests these servers answer, and far below anything that would look like a hang. */
private const val MAX_HEADER_BYTES = 16 * 1024
private const val MAX_BODY_BYTES = 64 * 1024
