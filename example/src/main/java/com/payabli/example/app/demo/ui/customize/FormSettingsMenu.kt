package com.payabli.example.app.demo.ui.customize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.payabli.example.app.demo.ui.components.DemoIcons
import com.payabli.example.app.demo.ui.components.SwitchRow
import com.payabli.example.app.demo.ui.theme.Dimens

/**
 * The host app's theme, as a look.
 *
 * The form takes its colours and shapes from `MaterialTheme`, so a brand is applied the way an integrator
 * would apply one: by theming the screen the form sits in.
 */
@Composable
fun FormLookTheme(
    look: FormLook,
    content: @Composable () -> Unit,
) {
    val base = MaterialTheme.colorScheme
    when (look) {
        FormLook.Default -> content()
        FormLook.Brand ->
            MaterialTheme(
                colorScheme =
                    base.copy(
                        primary = BrandViolet,
                        onPrimary = Color.White,
                        primaryContainer = BrandVioletLight,
                        secondaryContainer = BrandVioletLight,
                        onSecondaryContainer = BrandVioletDark,
                    ),
                shapes = Shapes(extraSmall = RoundedCornerShape(16.dp)),
                typography = MaterialTheme.typography,
                content = content,
            )

        FormLook.Compact ->
            MaterialTheme(
                colorScheme = base.copy(primary = CompactTeal, onPrimary = Color.White),
                shapes = Shapes(extraSmall = RoundedCornerShape(2.dp)),
                typography = MaterialTheme.typography,
                content = content,
            )
    }
}

/** The top bar's three-dots menu: a preset in one tap, or every setting in a sheet. */
@Composable
fun FormSettingsMenu(
    settings: FormSettings,
    onSettingsChange: (FormSettings) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }

    IconButton(onClick = { menuOpen = true }) {
        Icon(DemoIcons.More, contentDescription = "Customize the form")
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        FormPreset.entries.forEach { preset ->
            DropdownMenuItem(
                text = { Text(preset.label) },
                leadingIcon = {
                    if (preset.settings == settings) Icon(DemoIcons.Pass, contentDescription = "Selected")
                },
                onClick = {
                    onSettingsChange(preset.settings)
                    menuOpen = false
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("All settings…") },
            onClick = {
                menuOpen = false
                sheetOpen = true
            },
        )
    }

    if (sheetOpen) {
        FormSettingsSheet(settings, onSettingsChange, onDismiss = { sheetOpen = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormSettingsSheet(
    settings: FormSettings,
    onSettingsChange: (FormSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPadding)
                    .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing),
        ) {
            Choice("Look", FormLook.entries, settings.look, { it.label }) {
                onSettingsChange(settings.copy(look = it))
            }
            Choice("Payment methods", FormMethods.entries, settings.methods, { it.label }) {
                onSettingsChange(settings.copy(methods = it))
            }
            Toggle("Labels inside the fields", "Floating labels instead of a line above.", settings.labelsInside) {
                onSettingsChange(settings.copy(labelsInside = it))
            }
            Toggle("Hide labels", "Placeholders only.", settings.hideLabels) {
                onSettingsChange(settings.copy(hideLabels = it))
            }
            Toggle("Custom wording", "Title, button, section and field names.", settings.customWording) {
                onSettingsChange(settings.copy(customWording = it))
            }
            Toggle(
                "Customer section",
                "Off, the app supplies the customer instead of the payer.",
                settings.customerSection,
            ) { onSettingsChange(settings.copy(customerSection = it)) }
            Toggle("Customer section first", "Reorders the sections.", settings.customerFirst) {
                onSettingsChange(settings.copy(customerFirst = it))
            }
            Toggle(
                "Require a customer number",
                "Adds the field and refuses to submit without it.",
                settings.requireCustomerNumber,
            ) { onSettingsChange(settings.copy(requireCustomerNumber = it)) }
            Toggle("Amount summary", "A read-only row the payer cannot edit.", settings.summary) {
                onSettingsChange(settings.copy(summary = it))
            }
            Toggle("Group the card number", "Shows the digits in fours.", settings.groupCardNumber) {
                onSettingsChange(settings.copy(groupCardNumber = it))
            }
            Toggle("Dash between month and year", "The expiry separator.", settings.dashExpirySeparator) {
                onSettingsChange(settings.copy(dashExpirySeparator = it))
            }
            Toggle("Mask the account number", "Obscured as it is typed.", settings.maskAccountNumber) {
                onSettingsChange(settings.copy(maskAccountNumber = it))
            }
        }
    }
}

@Composable
private fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(name(option)) },
                )
            }
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) = SwitchRow(label = label, checked = checked, note = note, onCheckedChange = onCheckedChange)

private val BrandViolet = Color(0xFF6D28D9)
private val BrandVioletLight = Color(0xFFEDE9FE)
private val BrandVioletDark = Color(0xFF3B0764)
private val CompactTeal = Color(0xFF0F766E)
