package com.payabli.example.app.demo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.payabli.example.app.demo.ui.theme.Dimens
import com.payabli.example.app.demo.ui.theme.PayabliDemoTheme

/**
 * The frame every preview in this package renders in.
 *
 * Components draw on the themed background. A card on `surfaceContainer` is only judgeable against
 * the `surface` it will sit on, and the preview tool's default white backdrop hides exactly the
 * mistake worth catching in dark mode.
 *
 * Pair with `@PreviewLightDark`, which renders both schemes. This theme turns dynamic colour off, so
 * the two shown here are the two an integrator will see.
 */
@Composable
internal fun PreviewSurface(content: @Composable ColumnScope.() -> Unit) {
    PayabliDemoTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            content = content,
        )
    }
}
