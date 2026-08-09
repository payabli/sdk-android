package com.payabli.sdk.payin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.ExpiryChoices
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.form.PayInFormStyle
import java.util.Locale

/**
 * Picks a month and a year. Two lists, because Material has a date picker and no month picker.
 *
 * [ExpiryChoices] decides what they may contain.
 */
@Composable
internal fun ExpiryPickerDialog(
    today: ExpiryValue,
    initial: ExpiryValue?,
    style: PayInFormStyle,
    onPicked: (ExpiryValue) -> Unit,
    onDismiss: () -> Unit,
) {
    val years = remember(today) { ExpiryChoices.years(today) }
    var year by remember(today) { mutableIntStateOf(initial?.year?.takeIf { it in years } ?: years.first()) }
    val months = remember(today, year) { ExpiryChoices.months(today, year) }

    // Keyed on today alone. Keyed on year as well, changing the year re-initialised this, so picking
    // August and then moving to a later year silently came back as January: the coercion below ran
    // and was immediately overwritten. Measured on a device, which is the only place it shows.
    var month by remember(today) { mutableIntStateOf(initial?.month ?: months.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.payabli_payin_expiry_choose), style = style.sectionTitle) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(style.spacing.pairedField)) {
                PickerColumn(
                    heading = stringResource(R.string.payabli_payin_expiry_month),
                    values = months,
                    selected = month,
                    style = style,
                    label = { String.format(Locale.ROOT, "%02d", it) },
                    onSelect = { month = it },
                    modifier = Modifier.weight(1f),
                )
                PickerColumn(
                    heading = stringResource(R.string.payabli_payin_expiry_year),
                    values = years,
                    selected = year,
                    style = style,
                    label = { it.toString() },
                    onSelect = {
                        year = it
                        month = ExpiryChoices.coerceMonth(month, ExpiryChoices.months(today, it))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPicked(ExpiryValue(month, year)) }) {
                Text(stringResource(R.string.payabli_payin_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.payabli_payin_cancel)) }
        },
    )
}

@Composable
private fun <T> PickerColumn(
    heading: String,
    values: List<T>,
    selected: T,
    style: PayInFormStyle,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(style.spacing.label)) {
        Text(heading, style = style.label)
        LazyColumn(
            // Bounded so the dialog does not grow with twenty-one years in it. heightIn and not
            // height, so a large font scale is allowed to make each row taller.
            modifier = Modifier.heightIn(max = PICKER_MAX_HEIGHT),
        ) {
            items(values) { value ->
                val isSelected = value == selected
                Text(
                    text = label(value),
                    style = if (isSelected) style.sectionTitle else style.supporting,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                style.fieldShape,
                            ).padding(vertical = style.spacing.label),
                )
            }
        }
    }
}

/** Tall enough for several rows and short enough that the dialog still fits a small screen. */
private val PICKER_MAX_HEIGHT = 200.dp
