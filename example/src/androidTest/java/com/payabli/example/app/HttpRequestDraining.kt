package com.payabli.example.app

import java.io.BufferedReader

/**
 * Reads a whole HTTP request, so a reply is never written while the client is still sending.
 *
 * Answering after only the request line lets the close race the client's write, which the app reports as a
 * reset rather than as the response it was actually sent. Shared by the two socket-backed token servers in
 * this source set, which is the only reason it is not private to one of them.
 *
 * `Content-Length` counts bytes and this reads characters. They agree for the requests these servers answer,
 * which are ASCII, and the value is discarded either way.
 */
internal fun drainHttpRequest(reader: BufferedReader) {
    var length = 0
    while (true) {
        val line = reader.readLine() ?: return
        if (line.isEmpty()) break
        if (line.substringBefore(':').equals("Content-Length", ignoreCase = true)) {
            length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
    }

    var remaining = length
    val chunk = CharArray(DRAIN_CHUNK)
    while (remaining > 0) {
        val read = reader.read(chunk, 0, minOf(chunk.size, remaining))
        if (read < 0) return
        remaining -= read
    }
}

private const val DRAIN_CHUNK = 1024
