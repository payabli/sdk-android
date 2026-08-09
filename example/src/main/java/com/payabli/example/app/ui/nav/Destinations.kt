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
 * Payment method first, because it is the simplest thing the SDK does and the first thing an
 * integrator tries. Setup last, because it is a readout and not a task.
 */
enum class TopLevelDestination(
    val navLabel: String,
    val icon: ImageVector,
) {
    // Nav labels, which are shorter than the screen titles they lead to. Four items share the width
    // of the narrowest supported screen, and "Payment method" wraps to two lines there: the item
    // grows taller than its neighbours and its text left-aligns under a centred icon. The screen it
    // opens still says "Payment method" in full in the title bar.
    PaymentMethod("Method", DemoIcons.PaymentMethod),
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
