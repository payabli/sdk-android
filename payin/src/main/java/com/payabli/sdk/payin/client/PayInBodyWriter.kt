package com.payabli.sdk.payin.client

import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.payin.form.PayInFieldRules
import com.payabli.sdk.payin.model.PayInAchData
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInSecCode
import com.payabli.sdk.payin.model.SensitiveDigits
import kotlinx.serialization.builtins.serializer

/**
 * Builds a request body as bytes, so a card number never becomes a `String`.
 *
 * An encoder would: `encodeToString` produces one immutable `String` holding the whole body, and nothing can
 * erase it afterwards. So everything except `paymentMethod` is encoded normally, that object is written here
 * straight out of its buffers, and the two are joined as bytes.
 *
 * **Escaping is delegated, not hand-rolled.** Every ordinary string value is encoded through the JSON codec's
 * own string serializer, so quotes, backslashes and control characters are its problem. The buffered values
 * need no escaping at all: they are checked to be digits first, and a digit is never escaped. That check is an
 * assertion rather than validation — the client validates before it gets here — so failing it means a defect
 * in this module rather than bad input.
 *
 * The caller owns the bytes that come back and is expected to overwrite them once the request has been
 * written. Every intermediate buffer this file allocates is overwritten before it is dropped.
 */
internal object PayInBodyWriter {
    private const val OPEN_BRACE = '{'.code.toByte()
    private const val CLOSE_BRACE = '}'.code.toByte()
    private const val COMMA = ','.code.toByte()
    private const val COLON = ':'.code.toByte()
    private const val QUOTE = '"'.code.toByte()

    /**
     * Splices `paymentMethod` into an already-encoded body.
     *
     * [encodedBody] holds no sensitive value, so it arrives as a `String`. [methodFragment] does, so it
     * arrives as bytes and is overwritten here once copied.
     */
    fun withPaymentMethod(
        encodedBody: String,
        methodFragment: ByteArray,
    ): ByteArray {
        val outer = encodedBody.toByteArray(Charsets.UTF_8)
        require(outer.size >= 2 && outer[outer.size - 1] == CLOSE_BRACE) {
            "the encoded body is not a JSON object"
        }
        val buffer = ByteBuffer()
        // Everything but the closing brace, then the new member, then the brace back.
        buffer.write(outer, outer.size - 1)
        // An empty object takes no separator. The bodies here always carry an entry point, so this is a
        // guard rather than a live case.
        if (outer.size > 2) buffer.write(COMMA)
        buffer.write(quoted(PayInRoutes.FIELD_PAYMENT_METHOD))
        buffer.write(COLON)
        buffer.write(methodFragment, methodFragment.size)
        buffer.write(CLOSE_BRACE)
        methodFragment.fill(0)
        return buffer.finishAndWipe()
    }

    /** The `paymentMethod` object for a stored method being created. */
    fun instrumentFragment(instrument: PayInInstrument): ByteArray =
        when (instrument) {
            is PayInInstrument.Card -> cardFragment(instrument.data)
            is PayInInstrument.BankAccount -> achFragment(instrument.data)
        }

    /** The `paymentMethod` object for a transaction, which has four more shapes than a stored method. */
    fun methodFragment(method: PayInPaymentMethod): ByteArray =
        when (method) {
            is PayInPaymentMethod.Card -> cardFragment(method.data)
            is PayInPaymentMethod.BankAccount -> achFragment(method.data)
            is PayInPaymentMethod.Stored ->
                fragment {
                    field(PayInRoutes.FIELD_METHOD, PayInRoutes.METHOD_STORED)
                    field(PayInRoutes.FIELD_STORED_METHOD_ID, method.storedMethodId.trim())
                }

            is PayInPaymentMethod.CloudDevice ->
                fragment {
                    field(PayInRoutes.FIELD_METHOD, PayInRoutes.METHOD_CLOUD)
                    field(PayInRoutes.FIELD_DEVICE, method.deviceId.trim())
                }

            is PayInPaymentMethod.Check ->
                fragment {
                    field(PayInRoutes.FIELD_METHOD, PayInRoutes.METHOD_CHECK)
                    field(PayInRoutes.FIELD_CHECK_HOLDER, method.holderName.trim())
                }

            PayInPaymentMethod.Cash ->
                fragment {
                    field(PayInRoutes.FIELD_METHOD, PayInRoutes.METHOD_CASH)
                }
        }

    private fun cardFragment(data: PayInCardData): ByteArray =
        fragment {
            field(PayInRoutes.FIELD_METHOD, PayInRoutes.METHOD_CARD)
            digits(PayInRoutes.FIELD_CARD_NUMBER, data.cardNumber)
            // MM/YY, produced by the expiry type so the format lives in one place.
            field(PayInRoutes.FIELD_CARD_EXPIRY, data.expiry.format())
            digits(PayInRoutes.FIELD_CARD_SECURITY_CODE, data.securityCode)
            field(PayInRoutes.FIELD_CARD_HOLDER, data.holderName.trim())
            field(PayInRoutes.FIELD_CARD_POSTAL_CODE, data.postalCode.trim())
        }

    private fun achFragment(data: PayInAchData): ByteArray =
        fragment {
            field(PayInRoutes.FIELD_METHOD, PayInRoutes.METHOD_ACH)
            digits(PayInRoutes.FIELD_ACH_ACCOUNT, data.accountNumber)
            field(PayInRoutes.FIELD_ACH_ACCOUNT_TYPE, data.accountType.wireName)
            field(PayInRoutes.FIELD_ACH_ROUTING, data.routingNumber.trim())
            field(PayInRoutes.FIELD_ACH_HOLDER, data.holderName.trim())
            // The service assumes an internet authorisation when none is sent, so sending it is what makes
            // the value the caller chose visible in the request rather than implied by its absence.
            field(PayInRoutes.FIELD_ACH_SEC_CODE, (data.secCode ?: PayInSecCode.Web).wireName)
            data.holderType?.let { field(PayInRoutes.FIELD_ACH_HOLDER_TYPE, it.wireName) }
            data.deviceId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { field(PayInRoutes.FIELD_DEVICE, it) }
        }

    private fun fragment(build: FragmentWriter.() -> Unit): ByteArray {
        val writer = FragmentWriter()
        writer.build()
        return writer.finish()
    }

    /** One JSON string, escaped by the codec rather than by this file. */
    private fun quoted(value: String): ByteArray =
        PayabliJson.format
            .encodeToString(String.serializer(), value)
            .toByteArray(Charsets.UTF_8)

    /** Assembles one flat JSON object. */
    private class FragmentWriter {
        private val buffer = ByteBuffer()
        private var empty = true

        init {
            buffer.write(OPEN_BRACE)
        }

        fun field(
            name: String,
            value: String,
        ) {
            key(name)
            buffer.write(quoted(value))
        }

        /**
         * A buffered value, written digit by digit so it is never a `String`.
         *
         * The digits are read into a local array, copied out as bytes, and that array is overwritten before
         * this returns.
         */
        fun digits(
            name: String,
            value: SensitiveDigits,
        ) {
            val read = value.read()
            check(PayInFieldRules.isAllDigits(read)) {
                // No value in the message: this reads a card number.
                "a buffered field reached the body writer holding something other than digits"
            }
            key(name)
            buffer.write(QUOTE)
            read.forEach { buffer.write(it.code.toByte()) }
            buffer.write(QUOTE)
            read.fill(SensitiveDigits.WIPED)
        }

        fun finish(): ByteArray {
            buffer.write(CLOSE_BRACE)
            return buffer.finishAndWipe()
        }

        private fun key(name: String) {
            if (!empty) buffer.write(COMMA)
            empty = false
            buffer.write(quoted(name))
            buffer.write(COLON)
        }
    }

    /**
     * A growable byte buffer whose working array can be overwritten.
     *
     * `ByteArrayOutputStream` cannot be: it owns its array, hands out a copy, and offers no way to clear what
     * it keeps. A body assembled through one would leave a card number in a buffer nothing can reach.
     */
    private class ByteBuffer {
        private var bytes = ByteArray(INITIAL_CAPACITY)
        private var size = 0

        fun write(value: Byte) {
            ensure(1)
            bytes[size] = value
            size += 1
        }

        fun write(
            source: ByteArray,
            count: Int = source.size,
        ) {
            ensure(count)
            source.copyInto(bytes, size, 0, count)
            size += count
        }

        /** The exact-size result, after which the working array holds zeros. */
        fun finishAndWipe(): ByteArray {
            val result = bytes.copyOf(size)
            bytes.fill(0)
            size = 0
            return result
        }

        private fun ensure(extra: Int) {
            if (size + extra <= bytes.size) return
            var capacity = bytes.size
            while (capacity < size + extra) capacity *= 2
            val grown = ByteArray(capacity)
            bytes.copyInto(grown, 0, 0, size)
            // The array being replaced is what would otherwise keep a copy of everything written so far.
            bytes.fill(0)
            bytes = grown
        }

        private companion object {
            const val INITIAL_CAPACITY = 256
        }
    }
}
