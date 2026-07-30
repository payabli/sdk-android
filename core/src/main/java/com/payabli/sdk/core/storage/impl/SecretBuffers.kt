package com.payabli.sdk.core.storage.impl

import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * Conversions between `CharArray` and `ByteArray` that never build an intermediate `String`.
 *
 * `String(chars)` and `String.toByteArray()` would be one line each and would defeat the purpose: every
 * such `String` is immutable, cannot be overwritten, and lives on the heap until a garbage collection that
 * may never come. Encoding through `CharBuffer` keeps plaintext in arrays that can be wiped.
 *
 * Both conversions wipe every buffer they allocate except the one they return, which belongs to the caller.
 */
internal object SecretBuffers {
    /** Zeroizing means zero. Written as an escape so the literal is visible in source rather than an unprintable byte. */
    private const val ZERO_CHAR = '\u0000'

    /** UTF-8 bytes of [chars]. Wipes its own working buffers; [chars] is left alone. */
    fun toBytes(chars: CharArray): ByteArray {
        val byteBuffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        return try {
            ByteArray(byteBuffer.remaining()).also { byteBuffer.get(it) }
        } finally {
            // The encoder's buffer holds a full copy of the plaintext, so it has to go too. `array()` is
            // safe because `encode` returns a heap buffer.
            if (byteBuffer.hasArray()) byteBuffer.array().fill(0)
        }
    }

    /** UTF-8 characters of [bytes]. Wipes its own working buffers; [bytes] is left to the caller. */
    fun toChars(bytes: ByteArray): CharArray {
        val charBuffer = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        return try {
            CharArray(charBuffer.remaining()).also { charBuffer.get(it) }
        } finally {
            if (charBuffer.hasArray()) charBuffer.array().fill(ZERO_CHAR)
        }
    }

    /** Overwrites [bytes] in place. Named for what it is, so a call site reads as a wipe. */
    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    /** Overwrites [chars] in place. */
    fun wipe(chars: CharArray?) {
        chars?.fill(ZERO_CHAR)
    }
}
