package com.payabli.sdk.payin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.payabli.sdk.payin.form.PayInFormStyle

/**
 * Submits the form. Material's own button, so it carries the host's colour and shape.
 *
 * `heightIn` sets a floor; a fixed height would clip the label at a large font scale.
 */
@Composable
internal fun PayInSubmitButton(
    text: String,
    busyText: String,
    enabled: Boolean,
    isSubmitting: Boolean,
    style: PayInFormStyle,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = style.fieldShape,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MINIMUM_TOUCH_TARGET),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(style.spacing.header),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SPINNER_SIZE),
                    strokeWidth = SPINNER_STROKE,
                    color = LocalContentColor.current,
                )
            }
            Text(text = if (isSubmitting) busyText else text)
        }
    }
}

/** Android's accessibility guideline for anything tappable. */
private val MINIMUM_TOUCH_TARGET = 48.dp

private val SPINNER_SIZE = 16.dp
private val SPINNER_STROKE = 2.dp
