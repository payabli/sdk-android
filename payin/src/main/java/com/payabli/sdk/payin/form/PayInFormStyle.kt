package com.payabli.sdk.payin.form

import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The gaps the form leaves between things.
 *
 * `MaterialTheme` has no spacing role, so these are the only measurements this module names. All are
 * overridable.
 */
@Immutable
public data class PayInFormSpacing(
    val content: Dp = 20.dp,
    val header: Dp = 4.dp,
    val fieldGroup: Dp = 12.dp,
    val pairedField: Dp = 12.dp,
    val label: Dp = 7.dp,
    val section: Dp = 18.dp,
    val sectionTitle: Dp = 10.dp,
) {
    init {
        require(listOf(content, header, fieldGroup, pairedField, label, section, sectionTitle).none { it < 0.dp }) {
            "spacing cannot be negative"
        }
    }
}

/**
 * How the form looks. Every value comes from the host's `MaterialTheme` unless overridden.
 *
 * Built by [resolvePayInFormStyle]; `PayabliPayInFormDefaults.style` reads the theme.
 *
 * @param fieldColors null uses Material's own, which already follow the host theme.
 */
@Immutable
public data class PayInFormStyle(
    val title: TextStyle,
    val subtitle: TextStyle,
    val sectionTitle: TextStyle,
    val label: TextStyle,
    val supporting: TextStyle,
    val error: TextStyle,
    val fieldShape: Shape,
    val spacing: PayInFormSpacing,
    val fieldColors: TextFieldColors? = null,
)

/**
 * What the form reads out of the host's theme.
 *
 * The Compose layer fills this from `MaterialTheme`; a test fills it with anything.
 */
@Immutable
public data class PayInThemeRoles(
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val error: Color,
    val titleType: TextStyle,
    val subtitleType: TextStyle,
    val sectionTitleType: TextStyle,
    val labelType: TextStyle,
    val supportingType: TextStyle,
    val fieldShape: Shape,
)

/**
 * What a caller wants different. Each null keeps the theme-derived value.
 */
@Immutable
public data class PayInFormStyleOverrides(
    val title: TextStyle? = null,
    val subtitle: TextStyle? = null,
    val sectionTitle: TextStyle? = null,
    val label: TextStyle? = null,
    val supporting: TextStyle? = null,
    val error: TextStyle? = null,
    val fieldShape: Shape? = null,
    val spacing: PayInFormSpacing? = null,
    val fieldColors: TextFieldColors? = null,
)

/**
 * Turns the host's theme into the form's style, with the caller's overrides on top.
 */
public fun resolvePayInFormStyle(
    roles: PayInThemeRoles,
    overrides: PayInFormStyleOverrides = PayInFormStyleOverrides(),
): PayInFormStyle =
    PayInFormStyle(
        title = overrides.title ?: roles.titleType.copy(color = roles.onSurface),
        subtitle = overrides.subtitle ?: roles.subtitleType.copy(color = roles.onSurfaceVariant),
        sectionTitle = overrides.sectionTitle ?: roles.sectionTitleType.copy(color = roles.onSurface),
        label = overrides.label ?: roles.labelType.copy(color = roles.onSurfaceVariant),
        supporting = overrides.supporting ?: roles.supportingType.copy(color = roles.onSurfaceVariant),
        error = overrides.error ?: roles.supportingType.copy(color = roles.error),
        fieldShape = overrides.fieldShape ?: roles.fieldShape,
        spacing = overrides.spacing ?: PayInFormSpacing(),
        fieldColors = overrides.fieldColors,
    )
