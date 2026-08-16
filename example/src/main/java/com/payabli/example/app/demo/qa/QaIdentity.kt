package com.payabli.example.app.demo.qa

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What this device calls itself, for a QA run where several submit at once.
 *
 * Three phones and a simulator sending the sample's own test values produce rows nothing can tell apart: the
 * same customer, the same instrument, the same amount, minutes apart. Every value that distinguishes one
 * device from another comes from the model, so one build installs on all of them and each still names itself.
 * [firstName] is the one constant, because it is the half of a payer's name that says which run this was.
 *
 * The model is a parameter rather than a `Build` read, so the derivation runs on a host JVM against models no
 * machine here has.
 */
data class QaIdentity(
    val label: String,
    val slug: String,
) {
    /**
     * What a cardholder or account-holder box gets, which is what a stored method is listed under.
     *
     * Punctuation becomes a space, because the store route refuses an account holder name carrying any of
     * `` `~!@#$%^*()_+=-?>}{][ `` and answers "Bad Request: Account holder name cannot contain special
     * characters". A model code is full of it: `SM-S908U1` is refused and `SM S908U1` is stored. A card holder
     * name takes the same characters without complaint, which is why this only showed up on the bank account.
     */
    val holderName: String get() =
        "QA ${label.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")}"
            .replace(SPACES, " ")
            .trim()

    val firstName: String get() = "QA"

    val lastName: String get() = label

    val customerNumber: String get() = "qa-android-$slug"

    val billingEmail: String get() = "qa+$slug@example.com"

    /** The order's description, which is the note a transaction list shows. */
    fun note(flow: String): String = "QA $label - $flow"

    /**
     * An order identifier naming this device and the moment the attempt was made.
     *
     * To the second, because a walk through the flows submits several a minute apart and an identifier repeated
     * across them is one a reader cannot use. Local time, since the reader comparing it to a dashboard is in
     * front of the device.
     */
    fun orderId(atMillis: Long): String = "$slug-${SimpleDateFormat(STAMP, Locale.US).format(Date(atMillis))}"

    companion object {
        private const val UNKNOWN_LABEL = "Unknown device"
        private val SPACES = Regex(" +")
        private const val STAMP = "yyyyMMdd-HHmmss"

        /**
         * @param model as [com.payabli.example.app.demo.preflight.DeviceFacts.model] reports it, manufacturer
         *   first: `samsung SM-S908U1`.
         */
        fun from(model: String): QaIdentity {
            val label = labelOf(model).ifBlank { UNKNOWN_LABEL }
            val slug = slugOf(label)
            // A label carrying no letter or digit is not blank, so the check above passes it through, and
            // sanitising is what empties it. The slug is what the customer number, the billing email and the
            // order identifier are built from, so an empty one charges as `qa-android-` under
            // `qa+@example.com` and orders under a name that starts with the timestamp.
            if (slug.isEmpty()) return QaIdentity(label = UNKNOWN_LABEL, slug = slugOf(UNKNOWN_LABEL))
            return QaIdentity(label = label, slug = slug)
        }

        /**
         * A word already carrying a capital is left alone, because a model code is not a word: `SM-S908U1`
         * survives while `samsung` becomes `Samsung`.
         */
        private fun labelOf(model: String): String =
            model
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    if (word.none(Char::isUpperCase)) word.replaceFirstChar(Char::uppercaseChar) else word
                }

        private fun slugOf(label: String): String =
            label
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString("-")
    }
}
