package com.payabli.example.app.demo.ui.customize

/**
 * What the Simple Capture screen does with what the payer enters.
 *
 * [takesAmount] is whether it charges or holds an amount, which is what the amount box, the summary and the
 * retry key follow.
 */
enum class FormOperation(
    val label: String,
    val takesAmount: Boolean,
) {
    Capture("Capture", takesAmount = true),
    Authorize("Authorize", takesAmount = true),
    Tokenize("Tokenize", takesAmount = false),
}

/** Which payment methods the form offers. */
enum class FormMethods(
    val label: String,
) {
    Both("Card and bank"),
    Card("Card only"),
    Bank("Bank only"),
}

/** Which method the form opens on when it offers both. */
enum class FormStart(
    val label: String,
) {
    Card("Card"),
    Bank("Bank"),
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
    val startOn: FormStart = FormStart.Card,
    val labelsInside: Boolean = false,
    val hideLabels: Boolean = false,
    val customWording: Boolean = false,
    val customerSection: Boolean = false,
    val customerFirst: Boolean = false,
    val requireCustomerNumber: Boolean = false,
    val groupCardNumber: Boolean = true,
    val dashExpirySeparator: Boolean = false,
    val maskAccountNumber: Boolean = true,
    val offerAuthorize: Boolean = false,
) {
    /** The operations the screen offers. Authorize only when [offerAuthorize] is on. */
    val operations: List<FormOperation>
        get() = FormOperation.entries.filter { it != FormOperation.Authorize || offerAuthorize }
}

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
            customerSection = true,
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
            groupCardNumber = false,
        ),
    ),
}
