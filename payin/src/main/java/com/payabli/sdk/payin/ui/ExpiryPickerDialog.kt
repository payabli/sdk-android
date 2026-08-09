package com.payabli.sdk.payin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    val startingYear = remember(today) { initial?.year?.takeIf { it in years } ?: years.first() }
    var year by remember(today) { mutableIntStateOf(startingYear) }
    val months = remember(today, year) { ExpiryChoices.months(today, year) }

    // Keyed on the current month, so the selected month survives a year change; onSelect coerces it.
    var month by
        remember(today) {
            // An expired value can arrive: 03/26 opened in August 2026 lands on a year whose months
            // start at 08.
            mutableIntStateOf(
                ExpiryChoices.coerceMonth(
                    initial?.month ?: today.month,
                    ExpiryChoices.months(today, startingYear),
                ),
            )
        }

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
    val listState = rememberLazyListState()

    // The initial index alone only holds for the first list. Changing the year swaps 08..12 for
    // 01..12 under a selection that stays put, and the row it points at moves out of the viewport.
    LaunchedEffect(values, selected) {
        val index = values.indexOf(selected)
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem(index)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(style.spacing.label)) {
        Text(heading, style = style.label)
        LazyColumn(
            // Bounded, so a dialog holding twenty-one years still fits. A maximum and not a fixed
            // height, so a large font scale can still make each row taller.
            modifier = Modifier.heightIn(max = PICKER_MAX_HEIGHT).selectableGroup(),
            state = listState,
        ) {
            items(values) { value ->
                val isSelected = value == selected
                Text(
                    text = label(value),
                    style =
                        if (isSelected) {
                            style.sectionTitle.copy(color = style.selectedContent)
                        } else {
                            style.supporting
                        },
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = ROW_MIN_HEIGHT)
                            // selectable, not clickable: a bare click exposes an action and no
                            // selected state, so a screen reader cannot say which month is chosen.
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(value) },
                            ).then(
                                if (isSelected) {
                                    Modifier.background(style.selectedContainer, style.fieldShape)
                                } else {
                                    Modifier
                                },
                            ).wrapContentHeight(Alignment.CenterVertically),
                )
            }
        }
    }
}

/** Tall enough for several rows and short enough that the dialog still fits a small screen. */
private val PICKER_MAX_HEIGHT = 200.dp

/** Android's minimum touch target. */
private val ROW_MIN_HEIGHT = 48.dp
