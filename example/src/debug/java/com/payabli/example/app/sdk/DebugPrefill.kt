package com.payabli.example.app.sdk

import android.view.View
import androidx.annotation.StringRes
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.R as PayInR

/**
 * Fills the payment form's boxes, debug builds only.
 *
 * The SDK owns the form's field state and offers no way to seed it, so each box is found in the composition
 * this app hosts and fed through its own text-setting action, as if typed: the value goes through the field's
 * filter and formatter exactly as a keystroke does. The form is asked for nothing and told nothing.
 *
 * [view] is the composition to fill, read as `LocalView.current` where the button is drawn. The sheet is a
 * composition of its own, which is what lets a button inside it reach the form it draws.
 *
 * Boxes are matched the way this repository's instrumented tests match them: on the merged node, which is
 * where the field's label and its text-setting action meet. Whichever way the label is drawn, floating or on
 * its own line, one of the two properties read below carries it.
 *
 * **Two controls are not filled: the card expiry and the account type.** Both are pickers rather than text
 * boxes, and a picker publishes no text-setting action, so there is nothing to feed. They are chosen by hand
 * after the tap, as the expiry is on iOS.
 */
fun fillTestData(
    view: View,
    identity: SampleIdentity,
) {
    val owner = (view as? ViewRootForTest)?.semanticsOwner ?: return
    val boxes =
        owner
            .getAllSemanticsNodes(mergingEnabled = true)
            .mapNotNull { node ->
                node.config
                    .getOrNull(SemanticsActions.SetText)
                    ?.action
                    ?.let { node to it }
            }

    PayInPrefill.valuesFor(identity).forEach { (field, value) ->
        val label = view.context.getString(field.labelResource)
        boxes.firstOrNull { (node, _) -> node.isNamed(label) }?.second?.invoke(AnnotatedString(value))
    }
}

/** Whether this is the box a payer reads [label] on, under either of the form's label layouts. */
private fun SemanticsNode.isNamed(label: String): Boolean {
    val floating = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    val described = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    return label in floating || label in described
}

/**
 * The resource carrying the label this form draws for a field.
 *
 * This app's own table, as `PayInPrefillField` is iOS's: the SDK's equivalent is internal to it. Whatever this
 * names has to be what is on screen, which holds while the demo passes no field labels of its own.
 */
@get:StringRes
private val PayInField.labelResource: Int
    get() =
        when (this) {
            PayInField.CardholderName -> PayInR.string.payabli_payin_field_cardholder_name
            PayInField.CardNumber -> PayInR.string.payabli_payin_field_card_number
            PayInField.CardSecurityCode -> PayInR.string.payabli_payin_field_card_security_code
            PayInField.CardPostalCode -> PayInR.string.payabli_payin_field_card_postal_code
            PayInField.AccountHolder -> PayInR.string.payabli_payin_field_account_holder
            PayInField.RoutingNumber -> PayInR.string.payabli_payin_field_routing_number
            PayInField.AccountNumber -> PayInR.string.payabli_payin_field_account_number
            PayInField.FirstName -> PayInR.string.payabli_payin_field_first_name
            PayInField.LastName -> PayInR.string.payabli_payin_field_last_name
            PayInField.CustomerNumber -> PayInR.string.payabli_payin_field_customer_number
            PayInField.BillingEmail -> PayInR.string.payabli_payin_field_billing_email
            // Picked rather than typed, or not a box this app fills. Exhaustive, so a field added to
            // PayInPrefill with no label here fails to compile instead of filling nothing.
            PayInField.CardExpiration,
            PayInField.AccountType,
            PayInField.AccountHolderType,
            PayInField.SecCode,
            PayInField.DeviceId,
            PayInField.MethodDescription,
            PayInField.BillingPostalCode,
            PayInField.Amount,
            PayInField.ServiceFee,
            -> error("$this is not a box the prefill fills")
        }
