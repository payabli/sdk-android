package com.payabli.example.app.demo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.example.app.demo.ui.theme.Dimens

/**
 * One setting the app reads per request, so a control over it is honest.
 *
 * [DetailRow]'s counterpart, and the difference between them is the whole rule: a value captured when the
 * session was created is shown as text, and only a value read later gets a switch.
 *
 * @param note what each position does. Both, because a switch whose off position is undescribed is one nobody
 *   flips.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    note: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(Dimens.CardCorner))
                .padding(Dimens.RowPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f).width(Dimens.LabelValueGap))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Text(
            text = note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@PreviewLightDark
@Composable
private fun SwitchRowPreview() {
    PreviewSurface {
        // Both positions, because the note is what each one is for and it changes with the switch.
        SwitchRow(
            label = "Send a customer number",
            checked = true,
            note = "Charging sends QA Google Pixel 7a · qa-android-google-pixel-7a.",
            onCheckedChange = {},
        )
        SwitchRow(
            label = "Send a customer number",
            checked = false,
            note = "Charging sends no number, so the paypoint files a new customer for every payment.",
            onCheckedChange = {},
        )
    }
}
