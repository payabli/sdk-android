package com.payabli.sdk.payin.ui

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormConfiguration.Companion.AMOUNT_FIELDS
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInSectionStyle
import com.payabli.sdk.payin.form.PayInSummaryRows
import com.payabli.sdk.payin.model.PayInPaymentDetails
import java.math.BigDecimal

/** A section as it is drawn, with the figures it shows when it is the summary, and its [total] row if any. */
internal class DrawnSection(
    val section: PayInFormSection,
    val amounts: List<Pair<PayInField, BigDecimal>> = emptyList(),
    val total: BigDecimal? = null,
)

/**
 * [sections] with every amount other than zero placed in one summary. A host's summary section
 * decides where the figures go and what it is called, never which figures appear; with no
 * figure to show, none is drawn.
 *
 * @param sections the form's sections.
 * @param amounts what is being charged, or null for none.
 * @param showsBaseAmount whether the Amount row is drawn.
 * @return the sections as drawn, the figures placed in one summary.
 */
internal fun placeAmounts(
    sections: List<PayInFormSection>,
    amounts: PayInPaymentDetails?,
    showsBaseAmount: Boolean = true,
): List<DrawnSection> {
    val inputs = sections.filter { it.style == PayInSectionStyle.Inputs }.map(::DrawnSection)
    val figures =
        AMOUNT_FIELDS
            .filter { showsBaseAmount || it != PayInField.Amount }
            .associateWith { PayInSummaryRows.rowAmount(it, amounts) }
    val total = PayInSummaryRows.totalRowAmount(amounts)
    if (total == null && figures.values.all { it == null }) return inputs

    val at = PayInFormConfiguration.drawnSummaryAt(sections)
    val summary = sections.getOrNull(at) ?: PayInFormConfiguration.DEFAULT_SUMMARY
    val order = (summary.fields.filter { it in AMOUNT_FIELDS } + AMOUNT_FIELDS).distinct()
    val drawn = DrawnSection(summary, order.mapNotNull { field -> figures[field]?.let { field to it } }, total)

    return if (at < 0) {
        inputs + drawn
    } else {
        // Where the host put it, among the inputs before and after it.
        val before = sections.take(at).count { it.style == PayInSectionStyle.Inputs }
        inputs.take(before) + drawn + inputs.drop(before)
    }
}
