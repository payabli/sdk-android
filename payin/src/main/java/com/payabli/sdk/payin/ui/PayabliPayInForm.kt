package com.payabli.sdk.payin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldRules
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInFormStyle
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSectionStyle

/**
 * The Payabli payment form.
 *
 * Collects a card or a bank account. It does not submit: [onSubmit] fires when the payer asks to,
 * and the host does the rest.
 *
 * **It looks like the app it is in.** With no [style] it takes its colours, type and shapes from the
 * host's `MaterialTheme`, so light, dark and dynamic colour arrive with nothing passed. Use
 * [PayInFormStyleOverrides] to change one value, or [LocalPayInFormStyle] for every form in a tree.
 *
 * @param configuration what to collect and how to arrange it.
 * @param labels wording decided at runtime; anything left out comes from string resources.
 * @param style null takes [LocalPayInFormStyle], then the host's theme.
 * @param onValuesChanged every field's current value, keyed by field, on each edit.
 * @param onSubmit the payer asked to submit, and every required field is filled and valid.
 */
@Composable
public fun PayabliPayInForm(
    configuration: PayInFormConfiguration = PayInFormConfiguration(),
    labels: PayInFormLabels = PayInFormLabels(),
    style: PayInFormStyle? = null,
    modifier: Modifier = Modifier,
    isSubmitting: Boolean = false,
    onValuesChanged: (Map<PayInField, String>) -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    val resolved = rememberResolvedStyle(style)
    val today = remember { ExpiryValue.today() }
    var method by remember(configuration) { mutableStateOf(configuration.startingMethod) }
    val typed = remember(configuration) { mutableStateMapOf<PayInField, String>() }

    val sections = configuration.sectionsFor(method)
    val inputs = configuration.inputFieldsFor(method)
    val complete =
        inputs.none { configuration.isRequired(it) && PayInFieldRules.missing(it, typed[it].orEmpty()) } &&
            inputs.none { PayInFieldRules.error(it, typed[it].orEmpty(), today) != null }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(resolved.spacing.content),
    ) {
        if (configuration.methodsOffered.size > 1) {
            MethodSelector(configuration.methodsOffered, method, isSubmitting) { method = it }
        }

        sections.forEach { section ->
            FormSection(section, method, typed, today, configuration, labels, resolved, !isSubmitting) { field, value ->
                typed[field] = value
                onValuesChanged(typed.toMap())
            }
        }

        PayInSubmitButton(
            text = labels.submitButton ?: stringResource(R.string.payabli_payin_submit),
            busyText = stringResource(R.string.payabli_payin_submitting),
            enabled = complete && !isSubmitting,
            isSubmitting = isSubmitting,
            style = resolved,
            onClick = onSubmit,
        )
    }
}

@Composable
private fun MethodSelector(
    methods: List<PayInMethodType>,
    selected: PayInMethodType,
    isSubmitting: Boolean,
    onSelect: (PayInMethodType) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        methods.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = !isSubmitting,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = methods.size),
            ) {
                Text(PayInStrings.method(option))
            }
        }
    }
}

@Composable
private fun FormSection(
    section: PayInFormSection,
    method: PayInMethodType,
    typed: MutableMap<PayInField, String>,
    today: ExpiryValue,
    configuration: PayInFormConfiguration,
    labels: PayInFormLabels,
    style: PayInFormStyle,
    enabled: Boolean,
    onValueChange: (PayInField, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(style.spacing.sectionTitle)) {
        Text(text = section.title ?: defaultSectionTitle(section, method), style = style.sectionTitle)

        if (section.style == PayInSectionStyle.Summary) {
            SummaryRows(section, configuration, labels, style)
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(style.spacing.fieldGroup)) {
            InputRows(section.fields, typed, today, configuration, labels, style, enabled, onValueChange)
        }
    }
}

/**
 * Lays out a section's fields, pairing two short ones onto a row.
 *
 * Pairing stops at a large font scale, where two fields side by side would truncate.
 */
@Composable
private fun InputRows(
    fields: List<PayInField>,
    typed: MutableMap<PayInField, String>,
    today: ExpiryValue,
    configuration: PayInFormConfiguration,
    labels: PayInFormLabels,
    style: PayInFormStyle,
    enabled: Boolean,
    onValueChange: (PayInField, String) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val pairs = remember(fields, fontScale) { fields.intoRows(fontScale) }

    pairs.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(style.spacing.pairedField)) {
            row.forEach { field ->
                PayInFieldBox(
                    field = field,
                    value = typed[field].orEmpty(),
                    today = today,
                    configuration = configuration,
                    labels = labels,
                    style = style,
                    enabled = enabled,
                    onValueChange = { onValueChange(field, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryRows(
    section: PayInFormSection,
    configuration: PayInFormConfiguration,
    labels: PayInFormLabels,
    style: PayInFormStyle,
) {
    Column(verticalArrangement = Arrangement.spacedBy(style.spacing.label)) {
        section.fields.forEach { field ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = PayInStrings.label(field, labels), style = style.label)
                Text(text = configuration.summaryValues[field].orEmpty(), style = style.supporting)
            }
        }
    }
}

/** Two narrow fields share a row while the text is small enough for both to fit. */
private fun List<PayInField>.intoRows(fontScale: Float): List<List<PayInField>> {
    if (fontScale > PAIRING_FONT_SCALE_LIMIT) return map { listOf(it) }

    val rows = mutableListOf<List<PayInField>>()
    var index = 0
    while (index < size) {
        val field = this[index]
        val next = getOrNull(index + 1)
        if (field.isNarrow && next?.isNarrow == true) {
            rows += listOf(field, next)
            index += 2
        } else {
            rows += listOf(field)
            index += 1
        }
    }
    return rows
}

/** Above this the two halves of a paired row truncate, so each field takes the full width. */
private const val PAIRING_FONT_SCALE_LIMIT = 1.3f

@Composable
private fun defaultSectionTitle(
    section: PayInFormSection,
    method: PayInMethodType,
): String =
    stringResource(
        when {
            section.style == PayInSectionStyle.Summary -> R.string.payabli_payin_section_summary
            method == PayInMethodType.Card -> R.string.payabli_payin_section_card
            else -> R.string.payabli_payin_section_bank
        },
    )

@PreviewLightDark
@Composable
private fun PayabliPayInFormPreview() {
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current) {
        PayabliPayInForm(configuration = PayInFormConfiguration())
    }
}
