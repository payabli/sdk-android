package com.payabli.example.app.ui.nav

import androidx.compose.ui.graphics.vector.ImageVector
import com.payabli.example.app.ui.components.DemoIcons
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
data object SetupGraph

@Serializable
data object SetupHome

/**
 * The four capability areas, and the order they appear in.
 *
 * Saving a method first, because it is the simplest thing the SDK does and the first thing an
 * integrator tries. Setup last, because it is a readout and not a task.
 */
enum class TopLevelDestination(
    val navLabel: String,
    val icon: ImageVector,
) {
    // One word each where the capability allows it. Four items share the width of the narrowest
    // supported screen, and a label that wraps to two lines grows taller than its neighbours and
    // left-aligns its text under a centred icon.
    //
    // "Save" is the word the iOS demo uses for the same tab, so a reader moving between the two
    // apps is looking for the same thing in both.
    PaymentMethod("Save", DemoIcons.PaymentMethod),
    Capture("Capture", DemoIcons.Capture),
    TapToPay("Tap to pay", DemoIcons.TapToPay),
    Setup("Setup", DemoIcons.Setup),
    ;

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
                TapToPay -> TapToPayGraph
                Setup -> SetupGraph
            }

    /** The fully-qualified route name, for matching against the current back stack. */
    val graphRoute: String
        get() =
            when (this) {
                PaymentMethod -> PaymentMethodGraph::class.qualifiedName!!
                Capture -> CaptureGraph::class.qualifiedName!!
                TapToPay -> TapToPayGraph::class.qualifiedName!!
                Setup -> SetupGraph::class.qualifiedName!!
            }
}
