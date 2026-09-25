package com.payabli.example.app.demo.ui.customize

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.payabli.example.app.demo.ui.components.DemoIcons

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

/**
 * The top bar's three-dots menu: presets, then every setting in named groups.
 *
 * Each pick applies and closes the menu, so the whole form is on screen when it changes.
 */
@Composable
fun FormSettingsMenu(
    settings: FormSettings,
    onSettingsChange: (FormSettings) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val pick: (FormSettings) -> Unit = {
        onSettingsChange(it)
        open = false
    }

    IconButton(onClick = { open = true }) {
        Icon(DemoIcons.More, contentDescription = "Customize the form")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Group("Presets")
        FormPreset.entries.forEach { preset ->
            Option(preset.label, selected = preset.settings == settings) { pick(preset.settings) }
        }

        Group("Look")
        FormLook.entries.forEach { look ->
            Option(look.label, selected = look == settings.look) { pick(settings.copy(look = look)) }
        }

        Group("Methods")
        Caption("Payment methods")
        FormMethods.entries.forEach { methods ->
            Option(methods.label, selected = methods == settings.methods) { pick(settings.copy(methods = methods)) }
        }
        Caption("Start on")
        FormStart.entries.forEach { start ->
            Option(start.label, selected = start == settings.startOn, enabled = settings.methods == FormMethods.Both) {
                pick(settings.copy(startOn = start))
            }
        }

        Group("Labels")
        Toggle("Labels inside the fields", settings.labelsInside) { pick(settings.copy(labelsInside = it)) }
        Toggle("Hide labels", settings.hideLabels) { pick(settings.copy(hideLabels = it)) }
        Toggle("Custom wording", settings.customWording) { pick(settings.copy(customWording = it)) }

        Group("Sections")
        Toggle("Customer section", settings.customerSection) { pick(settings.copy(customerSection = it)) }
        Toggle("Customer section first", settings.customerFirst, enabled = settings.customerSection) {
            pick(settings.copy(customerFirst = it))
        }
        Toggle("Require a customer number", settings.requireCustomerNumber, enabled = settings.customerSection) {
            pick(settings.copy(requireCustomerNumber = it))
        }

        Group("Formatting")
        Toggle("Group the card number", settings.groupCardNumber) { pick(settings.copy(groupCardNumber = it)) }
        Toggle("Dash between month and year", settings.dashExpirySeparator) {
            pick(settings.copy(dashExpirySeparator = it))
        }
        Toggle(
            "Mask the bank account number",
            settings.maskAccountNumber,
            enabled = settings.methods != FormMethods.Card,
        ) { pick(settings.copy(maskAccountNumber = it)) }
    }
}

@Composable
private fun Group(title: String) {
    HorizontalDivider()
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Caption(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Option(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = DropdownMenuItem(
    text = { Text(label) },
    leadingIcon = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
    onClick = onClick,
    enabled = enabled,
)

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) = DropdownMenuItem(
    text = { Text(label) },
    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
    onClick = { onCheckedChange(!checked) },
    enabled = enabled,
)

private val BrandViolet = Color(0xFF6D28D9)
private val BrandVioletLight = Color(0xFFEDE9FE)
private val BrandVioletDark = Color(0xFF3B0764)
private val CompactTeal = Color(0xFF0F766E)
