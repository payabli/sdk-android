package com.payabli.example.app.payment

/** Which instrument the payer is using. */
enum class PaymentMethodType(
    val label: String,
) {
    Card("Card"),
    BankAccount("Bank account"),
}

/**
 * What kind of input a field needs, which is what decides the keyboard and the masking.
 *
 * [label] is words, because the placeholder shows it on screen beside the field name and the enum
 * name would read as "monthyear" there.
 */
enum class FieldInput(
    val label: String,
) {
    Text("text"),
    Number("digits"),
    Email("email"),

    /** Obscured as it is typed. */
    Secret("hidden"),

    /** Chosen from a fixed set. */
    Choice("choice"),

    /** Month and year, chosen from a picker. */
    MonthYear("month / year"),
}

/**
 * A field the form can render.
 *
 * The labels belong to the app and are ordinary words: "Card number", not "cardNumber". When the SDK
 * form arrives this enum becomes the mapping into its own field type, and the labels either carry
 * across or give way to the SDK's.
 */
enum class PaymentField(
    val label: String,
    val input: FieldInput,
    val isNarrow: Boolean = false,
) {
    CardholderName("Name on card", FieldInput.Text),
    CardNumber("Card number", FieldInput.Number),

    // Narrow fields sit two to a row. An expiry and a security code are three or four characters
    // each, and a full-width box for either one leaves the form looking padded out.
    CardExpiration("Expiry", FieldInput.MonthYear, isNarrow = true),

    // Masked, like the account number below. A security code is the credential that proves the card
    // is in the payer's hand, and it is the one value on this form that must never be stored at all.
    // Showing it in the clear over someone's shoulder is the everyday way it leaks.
    CardSecurityCode("CVV", FieldInput.Secret, isNarrow = true),
    CardPostalCode("Postal code", FieldInput.Text),

    AccountHolder("Account holder", FieldInput.Text),
    RoutingNumber("Routing number", FieldInput.Number),

    // Obscured: it is an account credential, and this app is read by people copying its patterns.
    AccountNumber("Account number", FieldInput.Secret),
    AccountType("Account type", FieldInput.Choice),

    FirstName("First name", FieldInput.Text, isNarrow = true),
    LastName("Last name", FieldInput.Text, isNarrow = true),
    Email("Billing email", FieldInput.Email),

    Amount("Amount", FieldInput.Number),
    ServiceFee("Fee", FieldInput.Number),
    ;

    /**
     * The field's identity, camelCase.
     *
     * What the Setup screen lists when it reads the form's configuration back. The label is what a
     * payer reads and can be reworded; this is what the field *is*, and it is how a reader matches a
     * row on that screen against a field in the form.
     */
    val fieldName: String get() = name.replaceFirstChar { it.lowercase() }
}
