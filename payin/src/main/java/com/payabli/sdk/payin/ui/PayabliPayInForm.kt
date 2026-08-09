package com.payabli.sdk.payin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldRules
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInFormStyle
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSectionStyle

/**
 * The Payabli payment form.
 *
 * Collects a card or a bank account. It does not submit: [onSubmit] fires when the payer asks to,
 * carrying what they entered, and the host does the rest.
 *
 * **It looks like the app it is in.** With no [style] it takes its colours, type and shapes from the
 * host's `MaterialTheme`, so light, dark and dynamic colour arrive with nothing passed. Use
 * `PayInFormStyleOverrides` to change one value, or [LocalPayInFormStyle] for every form in a tree.
 *
 * @param configuration what to collect and how to arrange it.
 * @param labels wording decided at runtime; anything left out or blank comes from string resources.
 * @param style null takes [LocalPayInFormStyle], then the host's theme.
 * @param onValuesChanged every edit, and every instrument change.
 * @param onSubmit the payer asked to submit, and every required field is filled and valid.
 */
@Composable
public fun PayabliPayInForm(
    configuration: PayInFormConfiguration,
    modifier: Modifier = Modifier,
    labels: PayInFormLabels = PayInFormLabels(),
    style: PayInFormStyle? = null,
    isSubmitting: Boolean = false,
    onValuesChanged: (PayInFormValues) -> Unit = {},
    onSubmit: (PayInFormValues) -> Unit = {},
) {
    // Read on every composition, so a form left open across the turn of a month validates against
    // the new one.
    val today = ExpiryValue.today()

    var method by remember(configuration) { mutableStateOf(configuration.startingMethod) }
    val typed = remember(configuration) { mutableStateMapOf<PayInField, String>() }

    // `enabled` only stops the second tap once the host has set isSubmitting and the button has
    // recomposed, which is a frame away. Two taps inside that frame are two payments. The latch
    // clears itself on the next frame, so a host that never sets isSubmitting cannot wedge it.
    var justSubmitted by remember { mutableStateOf(false) }
    LaunchedEffect(justSubmitted) {
        if (justSubmitted) {
            withFrameNanos { }
            justSubmitted = false
        }
    }

    val sections = configuration.sectionsFor(method)

    // Both read the state rather than closing over a list from the composition that built them. A
    // click or an edit lands before the next composition, and `method` is state while a captured
    // field list is not, so the two could describe different tabs.
    fun collect(chosen: PayInMethodType): PayInFormValues =
        PayInFormValues(chosen, configuration.inputFieldsFor(chosen).associateWith { typed[it].orEmpty() })

    fun isComplete(
        chosen: PayInMethodType,
        at: ExpiryValue,
    ): Boolean {
        val fields = configuration.inputFieldsFor(chosen)
        return fields.none { configuration.isRequired(it) && PayInFieldRules.missing(it, typed[it].orEmpty()) } &&
            fields.none { PayInFieldRules.error(it, typed[it].orEmpty(), at) != null }
    }

    val context =
        PayInFormContext(
            configuration = configuration,
            labels = labels,
            style = rememberResolvedStyle(style),
            today = today,
            enabled = !isSubmitting,
        )

    val complete = isComplete(method, today)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(context.style.spacing.content),
    ) {
        FormHeader(labels, context.style)

        if (configuration.methodsOffered.size > 1) {
            MethodSelector(configuration.methodsOffered, method, isSubmitting) { chosen ->
                method = chosen
                // Whatever the new instrument does not ask for goes, so a card number is neither
                // reported with a bank submission nor held behind that form.
                typed.keys.retainAll(configuration.inputFieldsFor(chosen).toSet())
                onValuesChanged(collect(chosen))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(context.style.spacing.section)) {
            sections.forEach { section ->
                FormSection(section, method, typed, context) { field, value ->
                    typed[field] = value
                    onValuesChanged(collect(method))
                }
            }
        }

        PayInSubmitButton(
            text = labels.submitButtonOrNull() ?: stringResource(R.string.payabli_payin_submit),
            busyText = stringResource(R.string.payabli_payin_submitting),
            enabled = complete && !isSubmitting,
            isSubmitting = isSubmitting,
            style = context.style,
            onClick = {
                // Everything again, against the state as it is now. `enabled` reflects the last
                // composition, so a field cleared or a tab switched in the frame before this click
                // would otherwise submit on a gate that no longer holds. The clock is re-read for
                // the same reason: an untouched form is not recomposing.
                val now = ExpiryValue.today()
                if (!justSubmitted && isComplete(method, now)) {
                    justSubmitted = true
                    onSubmit(collect(method))
                }
            },
        )
    }
}

/** The caller's heading and standfirst, each shown only when they gave one. */
@Composable
private fun FormHeader(
    labels: PayInFormLabels,
    style: PayInFormStyle,
) {
    val title = labels.titleOrNull()
    val subtitle = labels.subtitleOrNull()
    if (title == null && subtitle == null) return

    Column(verticalArrangement = Arrangement.spacedBy(style.spacing.header)) {
        title?.let { Text(text = it, style = style.title) }
        subtitle?.let { Text(text = it, style = style.subtitle) }
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
    typed: Map<PayInField, String>,
    context: PayInFormContext,
    onValueChange: (PayInField, String) -> Unit,
) {
    val style = context.style
    Column(verticalArrangement = Arrangement.spacedBy(style.spacing.sectionTitle)) {
        Text(text = section.title ?: defaultSectionTitle(section, method), style = style.sectionTitle)

        if (section.style == PayInSectionStyle.Summary) {
            SummaryRows(section, context)
            return@Column
        }

        InputRows(section.fields, typed, context, onValueChange)
    }
}

/**
 * Lays out a section's fields, pairing two short ones onto a row.
 *
 * Two share a row only where each would still be at least [PAIRED_FIELD_MIN_WIDTH], grown by the
 * font scale. That reads the width the form was actually given as well as the type size, so a form
 * in a narrow container un-pairs for the same reason a large font scale does.
 */
@Composable
private fun InputRows(
    fields: List<PayInField>,
    typed: Map<PayInField, String>,
    context: PayInFormContext,
    onValueChange: (PayInField, String) -> Unit,
) {
    val gap = context.style.spacing.pairedField
    BoxWithConstraints {
        val fontScale = LocalDensity.current.fontScale
        val fitsTwo = maxWidth >= (PAIRED_FIELD_MIN_WIDTH * fontScale) * 2 + gap
        val pairs = remember(fields, fitsTwo) { fields.intoRows(fitsTwo) }

        Column(verticalArrangement = Arrangement.spacedBy(context.style.spacing.fieldGroup)) {
            pairs.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { field ->
                        PayInFieldBox(
                            field = field,
                            value = typed[field].orEmpty(),
                            context = context,
                            onValueChange = { onValueChange(field, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRows(
    section: PayInFormSection,
    context: PayInFormContext,
) {
    Column(verticalArrangement = Arrangement.spacedBy(context.style.spacing.label)) {
        section.fields.forEach { field ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // The label yields. Two unconstrained children let a long one take the row and
                // leave the amount at zero width, which is the half a payer needs.
                Text(
                    text = PayInStrings.label(field, context.labels),
                    style = context.style.label,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(text = context.configuration.summaryValueFor(field), style = context.style.supporting)
            }
        }
    }
}

/** Two narrow fields share a row while there is room for both. */
private fun List<PayInField>.intoRows(fitsTwo: Boolean): List<List<PayInField>> {
    if (!fitsTwo) return map { listOf(it) }

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

/** Narrower than this a field's own label starts to wrap, so it takes the row to itself. */
private val PAIRED_FIELD_MIN_WIDTH = 148.dp

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
    PayabliPayInForm(configuration = PayInFormConfiguration())
}
