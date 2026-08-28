package com.payabli.example.app.demo.ui.nav

import androidx.compose.ui.graphics.vector.ImageVector
import com.payabli.example.app.demo.ui.components.DemoIcons
import kotlinx.serialization.Serializable

// Type-safe routes. A typo in a destination name is a compile error here; with string routes it
// would be a navigation that silently does nothing.

@Serializable
data object PaymentMethodGraph

@Serializable
data object PaymentMethodHome

@Serializable
data object PaymentMethodSaved

@Serializable
data object CaptureGraph

@Serializable
data object CaptureHome

@Serializable
data object CaptureResult

@Serializable
data object TapToPayGraph

@Serializable
data object TapToPayHome

@Serializable
data object SimpleCaptureGraph

@Serializable
data object SimpleCaptureHome

@Serializable
data object SetupGraph

@Serializable
data object SetupHome

/**
 * The capability areas, and the order they appear in.
 *
 * Saving a method first, as the simplest thing the SDK does. Setup last, being a readout. [SimpleCapture] is
 * shown only when the setting for it is on, which is what keeps the ordinary bar at four.
 */
enum class TopLevelDestination(
    val navLabel: String,
    val icon: ImageVector,
) {
    // One word each where the capability allows it. Four items share the width of the narrowest
    // supported screen, and a label that wraps grows taller than its neighbours and left-aligns
    // its text under a centred icon.
    PaymentMethod("Save", DemoIcons.PaymentMethod),
    Capture("Capture", DemoIcons.Capture),
    SimpleCapture("S-Capture", DemoIcons.Capture),
    TapToPay("TapToPay", DemoIcons.TapToPay),
    Setup("Config", DemoIcons.Setup),
    ;

    /** Whether this item is always in the bar, or waits on a setting. */
    val isOptional: Boolean get() = this == SimpleCapture

    /**
     * How a test finds this item.
     *
     * A tag and not the label, because three of the four labels also appear as the title of the
     * screen they open, so selecting by text would match two nodes and fail on the ambiguity.
     */
    val testTag: String get() = "nav.$name"

    val graph: Any
        get() =
            when (this) {
                PaymentMethod -> PaymentMethodGraph
                Capture -> CaptureGraph
                SimpleCapture -> SimpleCaptureGraph
                TapToPay -> TapToPayGraph
                Setup -> SetupGraph
            }

    /** The fully-qualified route name, for matching against the current back stack. */
    val graphRoute: String
        get() =
            when (this) {
                PaymentMethod -> PaymentMethodGraph::class.qualifiedName!!
                Capture -> CaptureGraph::class.qualifiedName!!
                SimpleCapture -> SimpleCaptureGraph::class.qualifiedName!!
                TapToPay -> TapToPayGraph::class.qualifiedName!!
                Setup -> SetupGraph::class.qualifiedName!!
            }
}

/**
 * The items the bar draws, in order.
 *
 * One home for the rule, because the bar and the test that guards it have to be asking the same question. A
 * filter written out at the call site is one the test can only imitate.
 */
fun shownDestinations(simpleCaptureShown: Boolean): List<TopLevelDestination> =
    TopLevelDestination.entries.filter { !it.isOptional || simpleCaptureShown }
