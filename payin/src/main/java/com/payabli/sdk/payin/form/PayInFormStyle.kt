package com.payabli.sdk.payin.form

import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** The box the six pieces of scheme artwork are drawn for, and the ratio they are cut to. */
private val DEFAULT_BRAND_MARK = DpSize(30.dp, 20.dp)

/**
 * The gaps the form leaves between things.
 *
 * `MaterialTheme` has no spacing role, so these are the only measurements this module names. All are
 * overridable.
 */
@Immutable
public data class PayInFormSpacing(
    public val content: Dp = 20.dp,
    public val header: Dp = 4.dp,
    public val fieldGroup: Dp = 12.dp,
    public val pairedField: Dp = 12.dp,
    public val label: Dp = 7.dp,
    public val section: Dp = 18.dp,
    public val sectionTitle: Dp = 10.dp,
) {
    init {
        // Finite as well as positive: Dp.Unspecified is NaN, and NaN < 0.dp is false. Dp.Infinity
        // likewise.
        require(
            listOf(content, header, fieldGroup, pairedField, label, section, sectionTitle)
                .all { it.value.isFinite() && it >= 0.dp },
        ) {
            "a gap has to be a finite measurement of zero or more"
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
    public val title: TextStyle,
    public val subtitle: TextStyle,
    public val sectionTitle: TextStyle,
    /**
     * The label the form draws above a field, under [PayInLabelLayout.External].
     *
     * Under [PayInLabelLayout.Placeholder] the label is Material's own, and Material interpolates
     * its size between resting and floating. Measured on a device: passing this style there pins
     * both states to one size, so the label stops shrinking as it floats and the resting one no
     * longer matches the text typed under it. That layout follows the host's `MaterialTheme`
     * typography, which is where its size comes from.
     */
    public val label: TextStyle,
    public val supporting: TextStyle,
    public val error: TextStyle,
    public val fieldShape: Shape,
    public val spacing: PayInFormSpacing,
    /** Behind the chosen month and year in the expiry picker. */
    public val selectedContainer: Color,
    /** On top of [selectedContainer]. */
    public val selectedContent: Color,
    public val fieldColors: TextFieldColors? = null,
    /**
     * The box a card scheme's mark is drawn into, at the end of the card number field.
     *
     * One box for all six marks, so Visa and Amex carry the same visual weight whatever it is set to: the
     * artwork is fitted inside it and keeps its own proportions.
     *
     * Last, because a parameter added anywhere else moves the ones after it and silently rebinds a positional
     * argument. New ones go here.
     */
    public val brandMark: DpSize = DEFAULT_BRAND_MARK,
) {
    init {
        // As PayInFormSpacing is, and for the same reason: this reaches Modifier.size, which refuses what
        // this refuses. Caught here, where the value was written, not one composition later.
        require(
            listOf(brandMark.width, brandMark.height).all { it.value.isFinite() && it >= 0.dp },
        ) {
            "the brand mark box has to be a finite measurement of zero or more"
        }
    }
}

/**
 * What the form reads out of the host's theme.
 *
 * The Compose layer fills this from `MaterialTheme`; a test fills it with anything.
 */
@Immutable
public data class PayInThemeRoles(
    public val onSurface: Color,
    public val onSurfaceVariant: Color,
    public val error: Color,
    public val secondaryContainer: Color,
    public val onSecondaryContainer: Color,
    public val titleType: TextStyle,
    public val subtitleType: TextStyle,
    public val sectionTitleType: TextStyle,
    public val labelType: TextStyle,
    public val supportingType: TextStyle,
    public val fieldShape: Shape,
)

/**
 * What a caller wants different. Each null keeps the theme-derived value.
 */
@Immutable
public data class PayInFormStyleOverrides(
    public val title: TextStyle? = null,
    public val subtitle: TextStyle? = null,
    public val sectionTitle: TextStyle? = null,
    public val label: TextStyle? = null,
    public val supporting: TextStyle? = null,
    public val error: TextStyle? = null,
    public val fieldShape: Shape? = null,
    public val spacing: PayInFormSpacing? = null,
    public val selectedContainer: Color? = null,
    public val selectedContent: Color? = null,
    public val fieldColors: TextFieldColors? = null,
    /** Last, for the reason [PayInFormStyle.brandMark] gives. */
    public val brandMark: DpSize? = null,
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
        brandMark = overrides.brandMark ?: DEFAULT_BRAND_MARK,
        selectedContainer = overrides.selectedContainer ?: roles.secondaryContainer,
        selectedContent = overrides.selectedContent ?: roles.onSecondaryContainer,
        fieldColors = overrides.fieldColors,
    )
