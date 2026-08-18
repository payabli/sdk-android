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
 * A request past the caps below throws rather than being partly read, so the caller closes without answering.
 * Answering a request that was only partly read is the race this exists to prevent.
 *
 * This runs on the accept thread, so a client that never terminates its headers would wedge the server rather
 * than fail it. The caps and the caller's read timeout are what bound that.
 */
internal fun drainHttpRequest(stream: InputStream) {
    var declared: String? = null
    var chunked = false
    var headerBudget = MAX_HEADER_BYTES
    var read = 0
    while (true) {
        // End of stream with nothing read is a client that connected and said nothing, which is not a request
        // and needs no reply. End of stream part way through the headers is a request that stopped, and
        // returning there would report it as fully drained and let the caller answer it, which is the race
        // this exists to prevent arriving by the one path that looks like success.
        val line =
            readLine(stream, headerBudget)
                ?: if (read == 0) return else throw IOException("request ended before its headers did")
        read += line.bytesRead
        headerBudget -= line.bytesRead
        if (line.value.isEmpty()) break
        val name = line.value.substringBefore(':')
        if (name.equals("Content-Length", ignoreCase = true)) {
            declared = line.value.substringAfter(':').trim()
        }
        if (name.equals("Transfer-Encoding", ignoreCase = true)) {
            chunked =
                line.value
                    .substringAfter(':')
                    .split(',')
                    .any { it.trim().equals("chunked", ignoreCase = true) }
        }
    }

    // A body is declared by one header or the other. Neither means there is none, which is the ordinary case
    // for the request these servers answer.
    // Read as a Long so a number too large for an Int is refused as the oversized body it is. Parsed as an
    // Int, `Content-Length: 3000000000` came back null and was reported as not a number, which sends whoever
    // reads that message looking for a typo.
    val length = declared?.toLongOrNull()
    when {
        chunked -> drainChunked(stream)
        length != null -> {
            // A negative parses, and left alone it reaches drainExactly as a count that reads nothing, so the
            // body would stay on the socket and be answered over.
            if (length < 0) throw IOException("Content-Length is negative: $declared")
            if (length > MAX_BODY_BYTES) throw IOException("request body exceeded $MAX_BODY_BYTES bytes")
            drainExactly(stream, length.toInt())
        }
        declared != null -> throw IOException("Content-Length is not a number: $declared")
    }
}

private fun drainExactly(
    stream: InputStream,
    length: Int,
) {
    var remaining = length
    val chunk = ByteArray(DRAIN_CHUNK)
    while (remaining > 0) {
        val read = stream.read(chunk, 0, minOf(chunk.size, remaining))
        if (read < 0) throw IOException("request body ended $remaining bytes early")
        remaining -= read
    }
}

/**
 * A chunked body, read by its framing: a hex size, that many bytes, and a zero size to finish.
 *
 * Decoded rather than read to a cap, which could not tell a finished small body from a stalled large one and
 * so waited out the socket timeout on both.
 */
private fun drainChunked(stream: InputStream) {
    var total = 0
    while (true) {
        val header = readLine(stream, MAX_HEADER_BYTES) ?: throw IOException("chunked body ended early")
        val size =
            header.value
                .substringBefore(';')
                .trim()
                .toIntOrNull(radix = 16)
                ?: throw IOException("chunk size is not hexadecimal: ${header.value}")

        if (size < 0) throw IOException("chunk size is negative: ${header.value}")
        if (size == 0) break
        // Compared before the addition, and against what is left rather than against the running total. A
        // chunk of 0x7fffffff added to any non-zero total wraps it negative, which reads as under the cap and
        // hands drainExactly a two gigabyte read. Both sides here are between zero and the cap, so neither
        // the subtraction nor the addition below can wrap.
        if (size > MAX_BODY_BYTES - total) throw IOException("request body exceeded $MAX_BODY_BYTES bytes")
        total += size
        drainExactly(stream, size)
        readLine(stream, MAX_HEADER_BYTES) ?: throw IOException("chunked body ended early")
    }

    // Trailers, then the blank line that ends them.
    while (true) {
        val line = readLine(stream, MAX_HEADER_BYTES) ?: return
        if (line.value.isEmpty()) return
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
