package com.payabli.sdk.payin.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.CardBrand
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldInput
import com.payabli.sdk.payin.form.PayInFieldRules
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.schemeName

/**
 * One field, whichever kind it is.
 *
 * The caller holds the text; this reports what was typed. It keeps only whether a secret is revealed
 * and whether the picker is open.
 *
 * Two error sources, one path. A rule reads the value in the box and a rejection is about the value that was
 * sent, and the rule wins where both speak: a payer editing a marked field is answering the rule.
 */
@Composable
internal fun PayInFieldBox(
    field: PayInField,
    value: String,
    context: PayInFormContext,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = PayInFieldRules.error(field, value, context.today) ?: context.rejectedFields[field]
    val label = PayInStrings.label(field, context.labels)
    val message = error?.let { PayInStrings.error(it) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(context.style.spacing.label)) {
        if (context.configuration.showsLabelFor(field)) {
            Text(text = label, style = context.style.label)
        }

        // The message is a sibling Text, which is its own semantics node, so isError alone tells a
        // screen reader that something is wrong and never what. It goes on the control as well.
        val naming = Modifier.fieldName(context, field, label).invalid(message)
        val invalid = error != null

        when (field.input) {
            PayInFieldInput.MonthYear -> ExpiryField(field, value, invalid, context, onValueChange, naming)
            PayInFieldInput.Choice -> ChoiceField(field, value, label, invalid, context, onValueChange, naming)
            else -> TypedField(field, value, label, invalid, context, onValueChange, naming)
        }

        if (message != null) {
            Text(text = message, style = context.style.error)
        }
    }
}

@Composable
private fun TypedField(
    field: PayInField,
    value: String,
    label: String,
    invalid: Boolean,
    context: PayInFormContext,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Masked, not merely secret. With masksAccountNumber off the account number is always in clear
    // text, and a reveal control over it toggles nothing.
    val isMasked = context.configuration.masks(field)

    // Keyed on the field. A configuration that puts a different secret field in this slot would
    // otherwise inherit the reveal state and show what the payer types next in clear text.
    var revealed by remember(field) { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(PayInFieldRules.filter(field, it)) },
        modifier = modifier.fillMaxWidth(),
        enabled = context.enabled,
        isError = invalid,
        singleLine = true,
        shape = context.style.fieldShape,
        colors = context.fieldColors(),
        label = context.floatingLabel(field, label),
        placeholder = PayInStrings.placeholder(field, context.labels)?.let { { Text(it) } },
        visualTransformation = field.transformation(context.configuration, revealed),
        keyboardOptions = field.keyboardOptions(),
        trailingIcon =
            if (!isMasked) {
                // The scheme the digits name, once they name one. The slot is the reveal control's wherever a
                // field is masked, and a card number under a mask is the payer's own to reveal first.
                brandBadge(field, value, context)
            } else {
                {
                    IconButton(onClick = { revealed = !revealed }, enabled = context.enabled) {
                        Icon(
                            imageVector = if (revealed) RevealOffIcon else RevealIcon,
                            contentDescription =
                                stringResource(
                                    if (revealed) R.string.payabli_payin_hide else R.string.payabli_payin_reveal,
                                    label,
                                ),
                        )
                    }
                }
            },
    )
}

@Composable
private fun ExpiryField(
    field: PayInField,
    value: String,
    invalid: Boolean,
    context: PayInFormContext,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = PayInStrings.label(field, context.labels)
    var picking by remember { mutableStateOf(false) }

    // The field goes flat when a submission starts; a dialog already over it does not, and its rows
    // and its Done button would keep calling onValueChange while the request is in flight.
    LaunchedEffect(context.enabled) { if (!context.enabled) picking = false }

    OutlinedTextField(
        value = value,
        onValueChange = { },
        modifier = modifier.fillMaxWidth(),
        enabled = context.enabled,
        readOnly = true,
        singleLine = true,
        shape = context.style.fieldShape,
        colors = context.fieldColors(),
        label = context.floatingLabel(field, label),
        placeholder = {
            Text(PayInStrings.placeholder(field, context.labels) ?: expiryHint(context.configuration))
        },
        isError = invalid,
        trailingIcon = {
            IconButton(
                onClick = {
                    // The form's clock is refreshed here as well as at submit. Opened on a stale
                    // month, the picker offers one that has already gone.
                    context.refreshClock()
                    picking = true
                },
                enabled = context.enabled,
            ) {
                Icon(
                    imageVector = ExpiryIcon,
                    contentDescription = stringResource(R.string.payabli_payin_expiry_choose),
                )
            }
        },
    )

    if (picking) {
        ExpiryPickerDialog(
            today = context.today,
            initial = ExpiryValue.parse(value),
            style = context.style,
            onPicked = {
                onValueChange(it.format(context.configuration.formatting.expirySeparator))
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(
    field: PayInField,
    value: String,
    label: String,
    invalid: Boolean,
    context: PayInFormContext,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = PayInStrings.choices(field)
    val shown = options.firstOrNull { it.first == value }?.second ?: value

    // Same as the picker: an open menu survives the field being disabled, and its items would keep
    // changing the value during a submission.
    LaunchedEffect(context.enabled) { if (!context.enabled) expanded = false }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (context.enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = shown,
            onValueChange = { },
            modifier =
                modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, context.enabled),
            enabled = context.enabled,
            isError = invalid,
            readOnly = true,
            singleLine = true,
            shape = context.style.fieldShape,
            colors = context.fieldColors(),
            label = context.floatingLabel(field, label),
            placeholder = PayInStrings.placeholder(field, context.labels)?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded && context.enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (wire, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    enabled = context.enabled,
                    onClick = {
                        onValueChange(wire)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * The scheme a card number names, drawn in the field.
 *
 * The scheme's mark where there is one, and its name where there is not: UnionPay has no artwork in this SDK,
 * and the web surfaces show a generic card glyph for it.
 *
 * Null for every other field, and for a number that names no scheme yet.
 */
private fun brandBadge(
    field: PayInField,
    value: String,
    context: PayInFormContext,
): (@Composable () -> Unit)? {
    if (field != PayInField.CardNumber) return null
    val brand = CardBrand.of(value).takeIf { it != CardBrand.Unknown } ?: return null
    val mark = brand.markResource()
    return {
        if (mark == null) {
            Text(text = brand.schemeName(), style = context.style.supporting)
        } else {
            // Every mark is a tile of the same shape, so one box holds any of them and a field's slot is
            // the same width whichever card a payer types.
            Image(
                painter = painterResource(mark),
                contentDescription = brand.schemeName(),
                modifier = Modifier.size(context.style.brandMark),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** The scheme's own artwork, or null where this SDK carries none. */
@DrawableRes
private fun CardBrand.markResource(): Int? =
    when (this) {
        CardBrand.Visa -> R.drawable.payabli_payin_brand_visa
        CardBrand.Mastercard -> R.drawable.payabli_payin_brand_mastercard
        CardBrand.AmericanExpress -> R.drawable.payabli_payin_brand_amex
        CardBrand.Discover -> R.drawable.payabli_payin_brand_discover
        CardBrand.DinersClub -> R.drawable.payabli_payin_brand_dinersclub
        CardBrand.Jcb -> R.drawable.payabli_payin_brand_jcb
        CardBrand.UnionPay, CardBrand.Unknown -> null
    }

/**
 * Material's floating label, or none.
 *
 * A hidden label is drawn in neither place.
 */
private fun PayInFormContext.floatingLabel(
    field: PayInField,
    label: String,
): (@Composable () -> Unit)? = if (configuration.showsFloatingLabelFor(field)) ({ Text(label) }) else null

/**
 * Names the control for a screen reader, where Material does not.
 *
 * Material takes a field's accessible name from its `label`, and this form draws that label as a
 * sibling `Text` under the default layout, or not at all when it is hidden. A sibling is its own
 * semantics node, so a screen reader lands on the box and announces no field name. Where Material
 * does supply the label, adding this would have it announced twice.
 */
private fun Modifier.fieldName(
    context: PayInFormContext,
    field: PayInField,
    label: String,
): Modifier = if (context.configuration.showsFloatingLabelFor(field)) this else semantics { contentDescription = label }

/** Carries the message a sighted payer reads under the field into the control's own semantics. */
private fun Modifier.invalid(message: String?): Modifier = if (message == null) this else semantics { error(message) }

/** Material's own colors unless the caller supplied a set. */
@Composable
private fun PayInFormContext.fieldColors() = style.fieldColors ?: OutlinedTextFieldDefaults.colors()

private fun PayInField.keyboardOptions(): KeyboardOptions =
    KeyboardOptions(
        keyboardType =
            when (input) {
                PayInFieldInput.Number -> KeyboardType.Number
                // NumberPassword, so the IME does not learn or suggest a security code or an
                // account number. PasswordVisualTransformation only masks the drawing.
                PayInFieldInput.Secret -> KeyboardType.NumberPassword
                PayInFieldInput.Email -> KeyboardType.Email
                else -> KeyboardType.Text
            },
    )

private fun PayInField.transformation(
    configuration: PayInFormConfiguration,
    revealed: Boolean,
): VisualTransformation =
    when {
        this == PayInField.CardNumber && configuration.formatting.groupsCardNumber -> CardNumberSpacing
        !revealed && configuration.masks(this) -> PasswordVisualTransformation()
        else -> VisualTransformation.None
    }

@Composable
@ReadOnlyComposable
private fun expiryHint(configuration: PayInFormConfiguration): String =
    stringResource(R.string.payabli_payin_expiry_hint, configuration.formatting.expirySeparator)

/** The box a scheme's mark is drawn into. Card-shaped, which is the shape each mark's tile is drawn to. */

private val RevealIcon: ImageVector get() = PayInIcons.Reveal
private val RevealOffIcon: ImageVector get() = PayInIcons.RevealOff
private val ExpiryIcon: ImageVector get() = PayInIcons.Expiry
