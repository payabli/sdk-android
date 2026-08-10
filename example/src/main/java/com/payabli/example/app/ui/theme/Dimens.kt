package com.payabli.example.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing and shape constants the screens share.
 *
 * Named here so a card in one screen cannot drift from a card in another.
 */
object Dimens {
    /** Outer padding on every scrolling screen. */
    val ScreenPadding = 16.dp

    /** Between the major blocks of a screen. */
    val SectionSpacing = 20.dp

    /** Between items inside one block. */
    val ItemSpacing = 8.dp

    /** Inside a card or a readout row. */
    val CardPadding = 12.dp

    /** Inside the denser rows: readout rows and log entries. */
    val RowPadding = 10.dp

    /** Corner radius on every card and row. One value, used everywhere. */
    val CardCorner = 8.dp

    /** Minimum gap between a row's label and its value before the value wraps. */
    val LabelValueGap = 12.dp

    /** Between a step's title and the line under it, which is tighter than the gap around them. */
    val StepTitleGap = 2.dp
}
