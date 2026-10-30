package com.payabli.example.app.demo.ui.nav

import androidx.navigation.NavGraphBuilder

/** Whether the bar draws the Tap to Pay tab. This variant doesn't link the card reader, so it doesn't. */
internal const val CARD_PRESENT_IN_BUILD = false

/** Adds nothing: this variant has no Tap to Pay screen. */
@Suppress("UnusedReceiverParameter")
internal fun NavGraphBuilder.cardPresentGraph() = Unit
