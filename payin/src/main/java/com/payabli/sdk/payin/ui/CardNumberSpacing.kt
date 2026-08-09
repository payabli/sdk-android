package com.payabli.sdk.payin.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Groups a card number into fours for display. The value behind the field stays digits.
 */
internal object CardNumberSpacing : VisualTransformation {
    private const val GROUP = 4

    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val spaced = digits.chunked(GROUP).joinToString(" ")

        // The cursor has to move with the spaces, or typing in the middle of a card number puts the
        // next digit in the wrong place.
        val mapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = offset + ((offset - 1).coerceAtLeast(0) / GROUP)

                override fun transformedToOriginal(offset: Int): Int = offset - (offset / (GROUP + 1))
            }

        return TransformedText(AnnotatedString(spaced), mapping)
    }
}
