package com.payabli.example.app.demo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.demo.ui.theme.Dimens

/**
 * What the last action produced.
 *
 * Always on screen, including before anything has happened, so the place an outcome will appear is
 * visible before there is one. A card that materialises only on success leaves a reader wondering
 * whether a failure was silent.
 *
 * @param emptyText what to show before the first result. Phrase it as a state ("Nothing stored
 *   yet"), not as an instruction.
 */
@Composable
fun ResultCard(
    text: String,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    val isEmpty = text.isEmpty()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(Dimens.CardCorner))
                .padding(Dimens.CardPadding),
    ) {
        SelectionContainer {
            Text(
                text = if (isEmpty) emptyText else text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val PREVIEW_EMPTY = "Nothing stored yet"

@PreviewLightDark
@Composable
private fun ResultCardPreview() {
    PreviewSurface {
        // Empty first: the state a reader sees before touching anything is the one most likely to
        // be wrong and the least likely to be looked at.
        ResultCard(text = "", emptyText = PREVIEW_EMPTY)
        ResultCard(
            text = "Stored method: mth_2f9c1\nResponse: Success\nResult: Approved",
            emptyText = PREVIEW_EMPTY,
        )
        ResultCard(
            text = "Could not store the payment method.\nThe card number failed validation.",
            emptyText = PREVIEW_EMPTY,
        )
    }
}
