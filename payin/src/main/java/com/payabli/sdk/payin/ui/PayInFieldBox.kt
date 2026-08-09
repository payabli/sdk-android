package com.payabli.sdk.payin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldInput
import com.payabli.sdk.payin.form.PayInFieldRules
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormStyle

/**
 * One field, whichever kind it is.
 *
 * The caller holds the text; this reports what was typed. It keeps only whether a secret is revealed
 * and whether the picker is open.
 */
@Composable
internal fun PayInFieldBox(
    field: PayInField,
    value: String,
    today: ExpiryValue,
    configuration: PayInFormConfiguration,
    labels: PayInFormLabels,
    style: PayInFormStyle,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = PayInFieldRules.error(field, value, today)
    val label = PayInStrings.label(field, labels)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(style.spacing.label)) {
        if (configuration.showsLabelFor(field)) {
            Text(text = label, style = style.label)
        }

        when (field.input) {
            PayInFieldInput.MonthYear ->
                ExpiryField(field, value, today, label, configuration, labels, style, enabled, onValueChange)

            PayInFieldInput.Choice ->
                ChoiceField(field, value, label, configuration, labels, style, enabled, onValueChange)

            else ->
                TypedField(field, value, label, configuration, labels, style, enabled, onValueChange)
        }

        if (error != null) {
            Text(text = PayInStrings.error(error), style = style.error)
        }
    }
}

@Composable
private fun TypedField(
    field: PayInField,
    value: String,
    label: String,
    configuration: PayInFormConfiguration,
    labels: PayInFormLabels,
    style: PayInFormStyle,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val isSecret = field.input == PayInFieldInput.Secret
    var revealed by remember { mutableStateOf(false) }
    val hasError = PayInFieldRules.error(field, value) != null

    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(PayInFieldRules.filter(field, it)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = hasError,
        singleLine = true,
        shape = style.fieldShape,
        colors = configuration.fieldColorsOrDefault(style),
        label = if (configuration.showsLabelFor(field)) null else ({ Text(label) }),
        placeholder = PayInStrings.placeholder(field, labels)?.let { { Text(it) } },
        visualTransformation = field.transformation(configuration, revealed),
        keyboardOptions = field.keyboardOptions(),
        trailingIcon =
            if (!isSecret) {
                null
            } else {
                {
                    IconButton(onClick = { revealed = !revealed }, enabled = enabled) {
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
    today: ExpiryValue,
    label: String,
    configuration: PayInFormConfiguration,
    labels: PayInFormLabels,
    style: PayInFormStyle,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = { },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = true,
        singleLine = true,
        shape = style.fieldShape,
        colors = configuration.fieldColorsOrDefault(style),
        label = if (configuration.showsLabelFor(field)) null else ({ Text(label) }),
        placeholder = { Text(PayInStrings.placeholder(field, labels) ?: expiryHint(configuration)) },
        isError = PayInFieldRules.error(field, value, today) != null,
        trailingIcon = {
            IconButton(onClick = { picking = true }, enabled = enabled) {
                Icon(imageVector = ExpiryIcon, contentDescription = label)
            }
        },
    )

    if (picking) {
        ExpiryPickerDialog(
            today = today,
            initial = ExpiryValue.parse(value),
            style = style,
            onPicked = {
                onValueChange(it.format())
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
    configuration: PayInFormConfiguration,
    labels: PayInFormLabels,
    style: PayInFormStyle,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = PayInStrings.choices(field)
    val shown = options.firstOrNull { it.first == value }?.second ?: value

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = shown,
            onValueChange = { },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            shape = style.fieldShape,
            colors = configuration.fieldColorsOrDefault(style),
            label = if (configuration.showsLabelFor(field)) null else ({ Text(label) }),
            placeholder = PayInStrings.placeholder(field, labels)?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (wire, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onValueChange(wire)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Material's own colours unless the caller supplied a set. */
@Composable
private fun PayInFormConfiguration.fieldColorsOrDefault(style: PayInFormStyle) =
    style.fieldColors ?: OutlinedTextFieldDefaults.colors()

private fun PayInField.keyboardOptions(): KeyboardOptions =
    KeyboardOptions(
        keyboardType =
            when (input) {
                PayInFieldInput.Number, PayInFieldInput.Secret -> KeyboardType.Number
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
        input == PayInFieldInput.Secret && !revealed && shouldMask(configuration) -> PasswordVisualTransformation()
        else -> VisualTransformation.None
    }

private fun PayInField.shouldMask(configuration: PayInFormConfiguration): Boolean =
    this != PayInField.AccountNumber || configuration.formatting.masksAccountNumber

@Composable
private fun expiryHint(configuration: PayInFormConfiguration): String =
    "MM${configuration.formatting.expirySeparator}YY"

private val RevealIcon: ImageVector get() = PayInIcons.Reveal
private val RevealOffIcon: ImageVector get() = PayInIcons.RevealOff
private val ExpiryIcon: ImageVector get() = PayInIcons.Expiry
