package com.payabli.sdk.payin.form

/**
 * Which instrument a payer is entering.
 *
 * @param wireName what the API calls it. A payer's word for it is a string resource.
 */
public enum class PayInMethodType(
    public val wireName: String,
) {
    Card("card"),
    BankAccount("ach"),
}

/**
 * What kind of input a field needs: the keyboard it asks for, and whether it is obscured.
 */
public enum class PayInFieldInput {
    Text,
    Number,
    Email,

    /** Obscured as it is typed, with a control to reveal it. */
    Secret,

    /** Chosen from a fixed set. */
    Choice,

    /** Month and year, chosen from a picker. */
    MonthYear,
}

/**
 * Every field the form can render. Labels come from string resources or [PayInFormLabels].
 *
 * @param isNarrow two of these fit side by side on one row.
 */
public enum class PayInField(
    public val input: PayInFieldInput,
    public val isNarrow: Boolean = false,
) {
    CardholderName(PayInFieldInput.Text),
    CardNumber(PayInFieldInput.Number),
    CardExpiration(PayInFieldInput.MonthYear, isNarrow = true),
    CardSecurityCode(PayInFieldInput.Secret, isNarrow = true),
    CardPostalCode(PayInFieldInput.Text),

    AccountHolder(PayInFieldInput.Text),
    RoutingNumber(PayInFieldInput.Number),
    AccountNumber(PayInFieldInput.Secret),
    AccountType(PayInFieldInput.Choice),
    AccountHolderType(PayInFieldInput.Choice),
    SecCode(PayInFieldInput.Choice),
    DeviceId(PayInFieldInput.Text),

    MethodDescription(PayInFieldInput.Text),
    FirstName(PayInFieldInput.Text, isNarrow = true),
    LastName(PayInFieldInput.Text, isNarrow = true),
    CustomerNumber(PayInFieldInput.Text),
    BillingEmail(PayInFieldInput.Email),
    BillingPostalCode(PayInFieldInput.Text),

    Amount(PayInFieldInput.Number),
    ServiceFee(PayInFieldInput.Number),
    ;

    /** The wire name, which is the enum name with its first letter lowered. */
    public val fieldName: String get() = name.replaceFirstChar { it.lowercase() }
}

/**
 * The field as it is reported: its own name in snake_case.
 *
 * A rename changes what the far side counts, so `PayInFieldTelemetryNameTest` pins the whole set.
 */
internal val PayInField.telemetryName: String
    get() = name.replace(Regex("(?<!^)([A-Z])"), "_$1").lowercase()
