package com.payabli.sdk.payin.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import com.payabli.sdk.payin.form.PayInFormStyle
import com.payabli.sdk.payin.form.PayInFormStyleOverrides
import com.payabli.sdk.payin.form.PayInThemeRoles
import com.payabli.sdk.payin.form.resolvePayInFormStyle

/**
 * Styles every [PayabliPayInForm] inside it.
 *
 * ```
 * CompositionLocalProvider(LocalPayInFormStyle provides myStyle) {
 *     // every form in here uses myStyle
 * }
 * ```
 *
 * To style one form, pass `style` to that form instead. Left unset, forms use the host's
 * `MaterialTheme`.
 */
public val LocalPayInFormStyle: ProvidableCompositionLocal<PayInFormStyle?> =
    compositionLocalOf { null }

/** How the payment form looks when nothing overrides it. */
public object PayabliPayInFormDefaults {
    /**
     * The host's `MaterialTheme`, as the form's style. The only place this module reads the theme.
     *
     * An override replaces one value and leaves the rest following the theme.
     */
    @Composable
    @ReadOnlyComposable
    public fun style(overrides: PayInFormStyleOverrides = PayInFormStyleOverrides()): PayInFormStyle =
        resolvePayInFormStyle(themeRoles(), overrides)

    @Composable
    @ReadOnlyComposable
    private fun themeRoles(): PayInThemeRoles =
        PayInThemeRoles(
            onSurface = MaterialTheme.colorScheme.onSurface,
            onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
            error = MaterialTheme.colorScheme.error,
            secondaryContainer = MaterialTheme.colorScheme.secondaryContainer,
            onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer,
            titleType = MaterialTheme.typography.titleMedium,
            subtitleType = MaterialTheme.typography.bodyMedium,
            sectionTitleType = MaterialTheme.typography.titleSmall,
            labelType = MaterialTheme.typography.labelLarge,
            supportingType = MaterialTheme.typography.bodySmall,
            fieldShape = MaterialTheme.shapes.extraSmall,
        )
}

/**
 * The style this form will use: the one passed in, then the ambient one, then the host's theme.
 *
 * The first found wins whole. Per-property merging happens inside [PayabliPayInFormDefaults.style].
 */
@Composable
internal fun rememberResolvedStyle(explicit: PayInFormStyle?): PayInFormStyle =
    explicit ?: LocalPayInFormStyle.current ?: PayabliPayInFormDefaults.style()
