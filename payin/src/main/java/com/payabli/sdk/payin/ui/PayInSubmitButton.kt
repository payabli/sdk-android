package com.payabli.sdk.payin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.payabli.sdk.payin.form.PayInFormStyle

/**
 * Submits the form. Material's own button, so it carries the host's colour and shape.
 *
 * `heightIn` sets a minimum, so the label keeps its room at a large font scale.
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
    // No shape here. Material gives a Button the host's button shape, and passing the field shape
    // made this one unlike every other button in the app, and made fieldShape move two things.
    Button(
        onClick = onClick,
        enabled = enabled,
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
                // Sized from the type beside it, so it grows with the host's typography and with the
                // font scale. A fixed size here is one an integrator cannot reach.
                //
                // A TextStyle may carry no size, and toDp throws on that rather than returning a
                // default, so a host whose typography leaves one unset would crash the button.
                val fontSize = LocalTextStyle.current.fontSize
                val diameter =
                    if (fontSize.isSpecified) {
                        with(LocalDensity.current) { fontSize.toDp() }
                    } else {
                        MINIMUM_TOUCH_TARGET / 3
                    }
                CircularProgressIndicator(
                    modifier = Modifier.size(diameter),
                    strokeWidth = diameter * SPINNER_STROKE_FRACTION,
                    color = LocalContentColor.current,
                )
            }
            Text(text = if (isSubmitting) busyText else text)
        }
    }
}

/** Android's accessibility guideline for anything tappable. */
private val MINIMUM_TOUCH_TARGET = 48.dp

/** A share of the spinner's own diameter, so the stroke stays in proportion at any type size. */
private const val SPINNER_STROKE_FRACTION = 0.12f
