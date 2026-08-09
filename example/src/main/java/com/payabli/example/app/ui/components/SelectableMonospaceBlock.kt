package com.payabli.example.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.ui.theme.Dimens
import com.payabli.example.app.ui.theme.monospaceLabelSmall

/**
 * Output a reader may want to copy: a response body, a log line, a diagnostic entry.
 *
 * Selectable, because the first thing anyone does with a transaction id is paste it somewhere else.
 * Scrolls sideways, because a wrapped JSON line stops being readable as JSON.
 */
@Composable
fun SelectableMonospaceBlock(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    SelectionContainer(
        modifier =
            modifier
                .fillMaxWidth()
                .background(containerColor, RoundedCornerShape(Dimens.CardCorner)),
    ) {
        Text(
            text = text,
            style = monospaceLabelSmall(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(Dimens.RowPadding),
        )
    }
}

@PreviewLightDark
@Composable
private fun SelectableMonospaceBlockPreview() {
    PreviewSurface {
        SelectableMonospaceBlock(text = "{\n  \"code\": 1,\n  \"reason\": \"Approved\"\n}")
        // A line far wider than the screen. Sideways scrolling is the decision this component makes,
        // and the preview is where it can be checked.
        SelectableMonospaceBlock(
            text =
                "RESPONSE 200 https://api-sandbox.payabli.com/api/paymentTransaction " +
                    "durationMillis=412 id=txn_01JQ8F3M2K7WZ9XB4C6D8E0GHR",
        )
    }
}
