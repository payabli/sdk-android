package com.payabli.example.app

import java.io.InputStream

/**
 * Reads a whole HTTP request, so a reply is never written while the client is still sending.
 *
 * Answering after only the request line lets the close race the client's write, which the app reports as a
 * reset rather than as the response it was actually sent.
 *
 * Bytes throughout, because `Content-Length` counts bytes.
 */
internal fun drainHttpRequest(stream: InputStream) {
    var length = 0
    while (true) {
        val line = readLine(stream) ?: return
        if (line.isEmpty()) break
        if (line.substringBefore(':').equals("Content-Length", ignoreCase = true)) {
            length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
    }

    var remaining = length
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
private fun readLine(stream: InputStream): String? {
    val line = StringBuilder()
    while (true) {
        val byte = stream.read()
        if (byte < 0) return if (line.isEmpty()) null else line.toString()
        if (byte == '\n'.code) return line.toString().removeSuffix("\r")
        line.append(byte.toChar())
    }
}

private const val DRAIN_CHUNK = 1024
