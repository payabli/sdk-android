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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInFormStyle
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSectionStyle
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.telemetry.reportRefusedFields

/**
 * The form itself, which knows a submission state and nothing about where one comes from.
 *
 * Split from `PayabliPayInForm` so rendering does not depend on the flow: a preview and every on-device test
 * drive this with a state they own, and neither can build a session. [onSubmit] answers whether the submission
 * was accepted, because a refused one leaves nothing pending.
 *
 * [draft] has no default: where it is held decides whether the payer keeps what they typed across a rotation.
 * One draft draws one form, so two forms sharing it fill each other's boxes.
 *
 * Everything that draws is here rather than beside the public entry, which is what keeps it under
 * `NoHardCodedAppearanceTest`'s reading of this package.
 */
@Composable
internal fun PayInFormContent(
    submission: PayInSubmissionState,
    draft: PayInFormDraft,
    configuration: PayInFormConfiguration,
    modifier: Modifier = Modifier,
    labels: PayInFormLabels = PayInFormLabels(),
    style: PayInFormStyle? = null,
    initialValues: PayInFormValues? = null,
    onSubmit: (PayInFormValues) -> Boolean = { false },
    onCompleted: (PayInSubmissionState.Succeeded) -> Unit = {},
    onFailed: (PayInSubmissionState.Failed) -> Unit = {},
    onMethodChanged: (PayInMethodType) -> Unit = {},
) {
    // The newest lambdas without re-registering anything. A host that writes them inline passes new objects
    // on every recomposition, and the effect below is keyed on the submission rather than on them.
    val completed by rememberUpdatedState(onCompleted)
    val failed by rememberUpdatedState(onFailed)

    val isSubmitting = submission is PayInSubmissionState.Submitting

    // State, so refreshing it recomposes. Read on every composition instead, an idle form across
    // the turn of a month kept the month it opened in: the button stayed enabled, the expired field
    // showed nothing, and only the click knew better, so tapping did nothing and said nothing.
    var today by remember { mutableStateOf(ExpiryValue.today()) }

    // Before anything below reads the draft, and on every composition: a caller replacing the configuration or
    // the values starts the form again from what they handed over, and re-entering a composition with the same
    // pair keeps what the payer typed.
    draft.seed(configuration, initialValues)

    val method = draft.method
    val typed = draft.typed

    // `enabled` only stops the second tap once the state has reached Submitting and the button has recomposed,
    // which is a frame away. Two taps inside that frame are two payments. The latch clears itself on the next
    // frame, so nothing here can be left stuck by an outcome that never arrives.
    var justSubmitted by remember { mutableStateOf(false) }
    LaunchedEffect(justSubmitted) {
        if (justSubmitted) {
            withFrameNanos { }
            justSubmitted = false
        }
    }

    val sections = configuration.sectionsFor(method)

    /**
     * Whether a box still holds a value the service rejected.
     *
     * Only fields this instrument draws: a rejection naming one that is not on screen would leave a form nobody
     * can complete.
     */
    fun anyRejectedFieldStands(chosen: PayInMethodType): Boolean =
        draft.rejectedFields.keys.any { it in configuration.inputFieldsFor(chosen) }

    // Keyed on the state itself, so a second rejection of the same field marks it again. `Succeeded` and
    // `Failed` are not data classes, so two identical consecutive rejections are two instances and the
    // StateFlow publishes both; `PayInSubmissionStateIdentityTest` pins that.
    LaunchedEffect(submission) {
        // Only an outcome this form sent: a shared flow would clear a payer's boxes mid-typing.
        val outcome = submission.takeIf { draft.submissionPending } ?: return@LaunchedEffect
        val rejected = (outcome as? PayInSubmissionState.Failed)?.fieldErrors.orEmpty()

        reportRefusedFields(rejected)
        draft.rejectedFields = rejected
        // On the composition's dispatcher; moving either call to the flow's coroutine needs withContext(Main).
        draft.submissionPending = outcome.deliver(draft::clearInstrument, completed, failed)
    }

    val context =
        PayInFormContext(
            configuration = configuration,
            labels = labels,
            style = rememberResolvedStyle(style),
            today = today,
            enabled = !isSubmitting,
            rejectedFields = draft.rejectedFields,
            refreshClock = { today = ExpiryValue.today() },
        )

    val complete = !anyRejectedFieldStands(method) && configuration.isComplete(typed, method, today)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(context.style.spacing.content),
    ) {
        FormHeader(labels, context.style)

        if (configuration.methodsOffered.size > 1) {
            MethodSelector(configuration.methodsOffered, method, isSubmitting) { chosen ->
                draft.switchTo(chosen, configuration)
                onMethodChanged(chosen)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(context.style.spacing.section)) {
            sections.forEach { section ->
                FormSection(section, method, typed, context, draft::enter)
            }
        }

        PayInSubmitButton(
            text = labels.submitButtonOrNull() ?: stringResource(R.string.payabli_payin_submit),
            busyText = stringResource(R.string.payabli_payin_submitting),
            enabled = complete && !isSubmitting,
            isSubmitting = isSubmitting,
            style = context.style,
            onClick = {
                // Checked again here, and read from the draft: `enabled` reflects the last composition, not
                // the state at the tap.
                today = ExpiryValue.today()
                val chosen = draft.method
                val readyToSend =
                    !justSubmitted &&
                        !anyRejectedFieldStands(chosen) &&
                        configuration.isComplete(typed, chosen, today)
                if (readyToSend) {
                    justSubmitted = true
                    // Only once it was accepted. Refused, nothing was sent, so nothing is pending.
                    draft.submissionPending = onSubmit(configuration.valuesFor(chosen, typed))
                }
            },
        )
    }
}

/**
 * Hands a terminal outcome to the caller, and answers whether a submission is still this form's to wait for.
 *
 * Idle says the flow holds nothing of this form's, so the flag goes: a form keeping it would take the next
 * outcome on that flow as its own.
 */
private fun PayInSubmissionState.deliver(
    clearInstrument: () -> Unit,
    onCompleted: (PayInSubmissionState.Succeeded) -> Unit,
    onFailed: (PayInSubmissionState.Failed) -> Unit,
): Boolean =
    when (this) {
        is PayInSubmissionState.Succeeded -> {
            clearInstrument()
            onCompleted(this)
            false
        }

        is PayInSubmissionState.Failed -> {
            clearInstrument()
            onFailed(this)
            false
        }

        PayInSubmissionState.Idle -> false
        PayInSubmissionState.Submitting -> true
    }

/**
 * What the payer has typed for one instrument.
 *
 * Reads the live map rather than a captured list: a click or an edit lands before the next composition, and the
 * method is state while a field list taken earlier is not, so the two could describe different tabs.
 */
private fun PayInFormConfiguration.valuesFor(
    method: PayInMethodType,
    typed: Map<PayInField, String>,
): PayInFormValues = PayInFormValues(method, inputFieldsFor(method).associateWith { typed[it].orEmpty() })

/** Whether every field this instrument asks for is filled in and none of them is wrong. */
private fun PayInFormConfiguration.isComplete(
    typed: Map<PayInField, String>,
    method: PayInMethodType,
    at: ExpiryValue,
): Boolean {
    val fields = inputFieldsFor(method)
    return fields.none { isRequired(it) && PayInFieldRules.missing(it, typed[it].orEmpty()) } &&
        fields.none { PayInFieldRules.error(it, typed[it].orEmpty(), at) != null }
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
                        // Keyed, so what a box remembers belongs to the field rather than to the
                        // slot. A configuration that puts a different field here would otherwise
                        // hand it an open menu, an opened picker, or a revealed secret.
                        key(field) {
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
    PayInFormContent(
        submission = PayInSubmissionState.Idle,
        draft = remember { PayInFormDraft() },
        configuration = PayInFormConfiguration(),
    )
}
