package com.payabli.sdk.payin.model

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

/** What the service calls each payment method. */
internal object PayInMethodWireNames {
    const val CARD: String = "card"
    const val ACH: String = "ach"
    const val CLOUD: String = "cloud"
    const val CHECK: String = "check"
    const val CASH: String = "cash"
}

/**
 * What a stored method stands for, lower case on the wire.
 *
 * A charge carries this alongside the identifier.
 */
public enum class PayInStoredMethodType(
    public val wireName: String,
) {
    Card(PayInMethodWireNames.CARD),
    BankAccount(PayInMethodWireNames.ACH),
}

/**
 * A card, as the payer entered it. The card number and security code are copied into buffers the SDK overwrites,
 * and can't be read back; each call sends from its own copy. [expiry] is an [ExpiryValue], so `MM/YY` is produced
 * in one place.
 */
public class PayInCardData private constructor(
    cardNumber: SensitiveDigits,
    public val expiry: ExpiryValue,
    securityCode: SensitiveDigits,
    public val holderName: String,
    public val postalCode: String,
) {
    /**
     * The card the payer entered.
     *
     * @param cardNumber the card number, copied and not kept as a `String`.
     * @param expiry the month and year the card expires.
     * @param securityCode the security code, copied and not kept as a `String`.
     * @param holderName the cardholder's name.
     * @param postalCode the billing postal code.
     */
    public constructor(
        cardNumber: String,
        expiry: ExpiryValue,
        securityCode: String,
        holderName: String,
        postalCode: String,
    ) : this(
        SensitiveDigits.ofString(cardNumber),
        expiry,
        SensitiveDigits.ofString(securityCode),
        holderName,
        postalCode,
    )

    @get:JvmSynthetic
    internal val cardNumberDigits: SensitiveDigits = cardNumber

    @get:JvmSynthetic
    internal val securityCodeDigits: SensitiveDigits = securityCode

    /**
     * The same card over copies of its buffers, for one call to send from and close.
     *
     * @return the copy, which closing leaves this card intact.
     */
    @JvmSynthetic
    internal fun copyForCall(): PayInCardData =
        PayInCardData(cardNumberDigits.copy(), expiry, securityCodeDigits.copy(), holderName, postalCode)

    /** Overwrites both buffers. */
    @JvmSynthetic
    internal fun closeDigits() {
        cardNumberDigits.close()
        securityCodeDigits.close()
    }

    /**
     * No field of a card belongs in a message, the expiry included.
     *
     * @return the type name alone.
     */
    override fun toString(): String = "PayInCardData"
}

/**
 * A bank account, as the payer entered it. The account number is buffered as [PayInCardData]'s card number is;
 * the routing number identifies a bank and is published, so it stays a `String`.
 */
public class PayInBankAccountData private constructor(
    accountNumber: SensitiveDigits,
    public val routingNumber: String,
    public val accountType: PayInAccountType,
    public val holderName: String,
    public val holderType: PayInAccountHolderType?,
    public val secCode: PayInSecCode?,
    public val deviceId: String?,
) {
    /**
     * The bank account the payer entered.
     *
     * @param accountNumber the account number, copied and not kept as a `String`.
     * @param routingNumber the bank's routing number.
     * @param accountType checking or savings.
     * @param holderName the account holder's name.
     * @param holderType personal or business, or null to leave it to the paypoint.
     * @param secCode the authorization the payer gave, or null for the default.
     * @param deviceId the device the payer used, or null.
     */
    public constructor(
        accountNumber: String,
        routingNumber: String,
        accountType: PayInAccountType,
        holderName: String,
        holderType: PayInAccountHolderType? = null,
        secCode: PayInSecCode? = null,
        deviceId: String? = null,
    ) : this(
        SensitiveDigits.ofString(accountNumber),
        routingNumber,
        accountType,
        holderName,
        holderType,
        secCode,
        deviceId,
    )

    @get:JvmSynthetic
    internal val accountNumberDigits: SensitiveDigits = accountNumber

    /**
     * The same account over a copy of its buffer, for one call to send from and close.
     *
     * @return the copy, which closing leaves this account intact.
     */
    @JvmSynthetic
    internal fun copyForCall(): PayInBankAccountData =
        PayInBankAccountData(
            accountNumberDigits.copy(),
            routingNumber,
            accountType,
            holderName,
            holderType,
            secCode,
            deviceId,
        )

    /** Overwrites the buffer. */
    @JvmSynthetic
    internal fun closeDigits() {
        accountNumberDigits.close()
    }

    override fun toString(): String = "PayInBankAccountData(accountType=$accountType)"
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
        public val data: PayInBankAccountData,
    ) : PayInInstrument()

    /**
     * The same instrument over copies of its buffers, for one call to send from and close.
     *
     * @return the copy, which closing leaves this instrument intact.
     */
    @JvmSynthetic
    internal fun copyForCall(): PayInInstrument =
        when (this) {
            is Card -> Card(data.copyForCall())
            is BankAccount -> BankAccount(data.copyForCall())
        }

    /** Overwrites every buffer this instrument holds. */
    @JvmSynthetic
    internal fun closeDigits() {
        when (this) {
            is Card -> data.closeDigits()
            is BankAccount -> data.closeDigits()
        }
    }

    /** What this instrument is charged as once it is stored. */
    internal val storedMethodType: PayInStoredMethodType
        get() =
            when (this) {
                is Card -> PayInStoredMethodType.Card
                is BankAccount -> PayInStoredMethodType.BankAccount
            }
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
        public val data: PayInBankAccountData,
    ) : PayInPaymentMethod()

    /** A method stored earlier, charged by its identifier and the method it stands for. */
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
     * The same method over copies of any buffers it holds, for one call to send from and close. A method that
     * holds none is returned as it is.
     *
     * @return the copy, which closing leaves this method intact.
     */
    @JvmSynthetic
    internal fun copyForCall(): PayInPaymentMethod =
        when (this) {
            is Card -> Card(data.copyForCall())
            is BankAccount -> BankAccount(data.copyForCall())
            else -> this
        }

    /** Overwrites every buffer this method holds; a method holding none is left alone. */
    @JvmSynthetic
    internal fun closeDigits() {
        when (this) {
            is Card -> data.closeDigits()
            is BankAccount -> data.closeDigits()
            else -> Unit
        }
    }

    /**
     * True if this method can be authorized as well as captured; false otherwise.
     *
     * Read before the request goes out, so the answer is local.
     */
    internal val isAuthorizable: Boolean
        get() =
            when (this) {
                is Card, is CloudDevice -> true
                is Stored -> method == PayInStoredMethodType.Card
                else -> false
            }
}
