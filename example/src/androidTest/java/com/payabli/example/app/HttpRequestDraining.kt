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
    var headerBudget = MAX_HEADER_BYTES
    while (true) {
        val line = readLine(stream, headerBudget) ?: return
        headerBudget -= line.bytesRead
        if (line.value.isEmpty()) break
        if (line.value.substringBefore(':').equals("Content-Length", ignoreCase = true)) {
            length = line.value
                .substringAfter(':')
                .trim()
                .toIntOrNull() ?: 0
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

/** A header line and what it cost, so the caller's budget is spent in the units it is denominated in. */
private class HeaderLine(
    val value: String,
    val bytesRead: Int,
)

/**
 * One header line, without its terminator, or null at end of stream.
 *
 * A byte at a time, so the stream is left positioned exactly after the blank line for the body read above,
 * and every byte is counted as it arrives rather than inferred from the string afterwards.
 */
private fun readLine(
    stream: InputStream,
    budget: Int,
): HeaderLine? {
    val line = StringBuilder()
    var bytesRead = 0
    while (true) {
        val byte = stream.read()
        if (byte < 0) return if (bytesRead == 0) null else HeaderLine(line.toString(), bytesRead)
        bytesRead++
        if (bytesRead > budget) throw IOException("request headers exceeded $MAX_HEADER_BYTES bytes")
        if (byte == '\n'.code) return HeaderLine(line.toString().removeSuffix("\r"), bytesRead)
        line.append(byte.toChar())
    }
}

private const val DRAIN_CHUNK = 1024

/** Generous next to the requests these servers answer, and far below anything that would look like a hang. */
private const val MAX_HEADER_BYTES = 16 * 1024
private const val MAX_BODY_BYTES = 64 * 1024
