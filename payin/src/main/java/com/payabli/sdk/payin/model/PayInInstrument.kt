package com.payabli.sdk.payin.model

import com.payabli.sdk.payin.form.ExpiryValue

/**
 * A bank account's kind, as the service spells it.
 *
 * Capitalised on the wire, which the service's own payloads confirm, so the wire name is carried here rather
 * than derived from the constant.
 */
public enum class PayInAccountType(
    public val wireName: String,
) {
    Checking("Checking"),
    Savings("Savings"),
}

/** Whose account it is. Lower case on the wire, unlike [PayInAccountType]. */
public enum class PayInAccountHolderType(
    public val wireName: String,
) {
    Personal("personal"),
    Business("business"),
}

/**
 * The NACHA authorization the payer gave, upper case on the wire.
 *
 * [Web] is the default when none is sent, and it is the right one here: a payer typing their details into a
 * screen authorized it over the internet.
 */
public enum class PayInSecCode(
    public val wireName: String,
) {
    Ppd("PPD"),
    Web("WEB"),
    Tel("TEL"),
    Ccd("CCD"),
    Boc("BOC"),
}

/**
 * What a stored method stands for, lower case on the wire.
 *
 * An identifier does not say what is being charged, and a charge is well formed only when the method
 * travels with it.
 */
public enum class PayInStoredMethodType(
    public val wireName: String,
) {
    Card("card"),
    BankAccount("ach"),
    Wallet("wallet"),
}

/**
 * A card, as the payer entered it.
 *
 * [cardNumber] and [securityCode] are [SensitiveDigits] rather than strings, so the SDK holds no copy it
 * cannot erase. **This type does not own them**: a caller that built them closes them, and the usual shape is
 * a `use` block around the call that sends them.
 *
 * [expiry] is an [ExpiryValue] rather than text, so `MM/YY` is produced in one place and a caller cannot
 * supply something that parses two ways.
 */
public class PayInCardData(
    public val cardNumber: SensitiveDigits,
    public val expiry: ExpiryValue,
    public val securityCode: SensitiveDigits,
    public val holderName: String,
    public val postalCode: String,
) {
    /** No field of a card belongs in a message, the expiry included. */
    override fun toString(): String = "PayInCardData"
}

/**
 * A bank account, as the payer entered it.
 *
 * [accountNumber] is a [SensitiveDigits] and [routingNumber] is not: a routing number identifies a bank and
 * is published, so buffering it would suggest a secrecy it does not have.
 */
public class PayInAchData(
    public val accountNumber: SensitiveDigits,
    public val routingNumber: String,
    public val accountType: PayInAccountType,
    public val holderName: String,
    public val holderType: PayInAccountHolderType? = null,
    public val secCode: PayInSecCode? = null,
    public val deviceId: String? = null,
) {
    override fun toString(): String = "PayInAchData(accountType=$accountType)"
}

/**
 * What a stored method may be created from: entered card or bank details, and nothing else.
 *
 * The store endpoint takes entered details only. A stored method, cash and a terminal reading are transaction
 * shapes, and live in [PayInPaymentMethod].
 */
public sealed class PayInInstrument {
    /** A card the payer entered. */
    public class Card(
        public val data: PayInCardData,
    ) : PayInInstrument()

    /** A bank account the payer entered. */
    public class BankAccount(
        public val data: PayInAchData,
    ) : PayInInstrument()
}

/**
 * How a transaction is being paid for.
 *
 * Wider than [PayInInstrument]: a transaction may also be charged to something already stored, taken on a
 * cloud terminal, or recorded as a check or as cash.
 */
public sealed class PayInPaymentMethod {
    /** A card the payer entered. */
    public class Card(
        public val data: PayInCardData,
    ) : PayInPaymentMethod()

    /** A bank account the payer entered. */
    public class BankAccount(
        public val data: PayInAchData,
    ) : PayInPaymentMethod()

    /** A method stored earlier, charged by its identifier and the method that identifier stands for. */
    public class Stored(
        public val method: PayInStoredMethodType,
        public val storedMethodId: String,
    ) : PayInPaymentMethod()

    /** A reading taken by a cloud-connected terminal. */
    public class CloudDevice(
        public val deviceId: String,
    ) : PayInPaymentMethod()

    /** A check, recorded rather than read. */
    public class Check(
        public val holderName: String,
    ) : PayInPaymentMethod()

    /** Cash, which carries no instrument at all. */
    public object Cash : PayInPaymentMethod()

    /**
     * True when this method can be authorized as well as captured.
     *
     * A card, a cloud device, and a stored method standing for a card or a wallet. Read before anything is
     * sent, so a caller is answered here rather than by a round trip.
     */
    internal val isAuthorizable: Boolean
        get() =
            when (this) {
                is Card, is CloudDevice -> true
                is Stored -> method == PayInStoredMethodType.Card || method == PayInStoredMethodType.Wallet
                else -> false
            }
}
