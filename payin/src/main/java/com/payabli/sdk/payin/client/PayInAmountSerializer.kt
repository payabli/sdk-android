@file:OptIn(ExperimentalSerializationApi::class)

package com.payabli.sdk.payin.client

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import java.math.RoundingMode

/** Amounts carry two decimal places on this wire, and the service reads them as a decimal. */
private const val AMOUNT_SCALE = 2

/**
 * Money on the wire: an unquoted JSON number with exactly two decimal places.
 *
 * `10` is sent as `10.00` and `10.005` as `10.01`. The service's own request payloads write these fields as a
 * decimal literal with two places, so the scale is the contract rather than a formatting preference.
 *
 * **Not a `Double`, at any point.** A binary floating-point value cannot hold `0.10`, which is not a property
 * a payment amount can afford, and a `Double` would also print `10.0` where the service writes `10.00`.
 * [BigDecimal] carries its own scale, so the value and its presentation stop being two separate problems.
 *
 * Reading is tolerant in one direction only: a number and a quoted number both decode, because a field that
 * changes representation should not empty the amount. Anything that is not a number in either form is a
 * decode failure.
 */
internal object PayInAmountSerializer : KSerializer<BigDecimal> {
    /**
     * Declared as a string, encoded as a literal.
     *
     * The descriptor names how the value is *read* — a quoted amount is accepted — while [serialize] writes
     * through [JsonUnquotedLiteral], which bypasses the descriptor's kind.
     */
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.payabli.sdk.payin.Amount", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: BigDecimal,
    ) {
        val json =
            encoder as? JsonEncoder
                ?: throw SerializationException("Amounts are only encodable to JSON")
        json.encodeJsonElement(JsonUnquotedLiteral(value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).toPlainString()))
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        val json = decoder as? JsonDecoder ?: return parse(decoder.decodeString())
        val element = json.decodeJsonElement()
        val primitive =
            element as? JsonPrimitive
                ?: throw SerializationException("An amount is a number, and this was not one")
        return parse(primitive.content)
    }

    /** The text is never in the message: an amount is not a secret, but a decode error is not the place. */
    private fun parse(text: String): BigDecimal =
        text.toBigDecimalOrNull() ?: throw SerializationException("An amount could not be read as a number")
}
