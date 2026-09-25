package com.payabli.example.app.demo.ui.customize

/** What the Simple Capture screen does with what the payer enters. */
enum class FormOperation(
    val label: String,
) {
    Capture("Capture"),
    Tokenize("Tokenize"),
}

/** Which payment methods the form offers. */
enum class FormMethods(
    val label: String,
) {
    Card("Card"),
    Bank("Bank"),
    Both("Both"),
}

/** The visual theme the form is drawn in. */
enum class FormLook(
    val label: String,
) {
    Default("App theme"),
    Brand("Brand"),
    Compact("Compact"),
}

/**
 * Every customization the Simple Capture screen demonstrates, as values this app owns.
 *
 * `sdk/FormCustomization.kt` turns it into the SDK's configuration, labels and style.
 */
data class FormSettings(
    val look: FormLook = FormLook.Default,
    val methods: FormMethods = FormMethods.Both,
    val labelsInside: Boolean = false,
    val hideLabels: Boolean = false,
    val customWording: Boolean = false,
    val customerSection: Boolean = true,
    val customerFirst: Boolean = false,
    val requireCustomerNumber: Boolean = false,
    val summary: Boolean = true,
    val groupCardNumber: Boolean = true,
    val dashExpirySeparator: Boolean = false,
    val maskAccountNumber: Boolean = true,
)

/** Named combinations, so one tap shows several settings changing together. */
enum class FormPreset(
    val label: String,
    val settings: FormSettings,
) {
    Default("Default", FormSettings()),
    Brand(
        "Brand",
        FormSettings(
            look = FormLook.Brand,
            labelsInside = true,
            customWording = true,
            customerFirst = true,
            requireCustomerNumber = true,
            dashExpirySeparator = true,
        ),
    ),
    Minimal(
        "Minimal",
        FormSettings(
            look = FormLook.Compact,
            methods = FormMethods.Card,
            hideLabels = true,
            customerSection = false,
            summary = false,
            groupCardNumber = false,
        ),
    ),
}
