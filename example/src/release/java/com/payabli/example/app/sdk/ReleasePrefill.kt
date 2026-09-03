package com.payabli.example.app.sdk

import android.view.View
import com.payabli.example.app.demo.sample.SampleIdentity

/**
 * The release counterpart of the debug variant's `fillTestData`, and it does nothing.
 *
 * `PaymentFlowScreen` imports that function from `src/debug`, so a release compilation has nothing to
 * resolve the reference against and fails to build. The call itself is unreachable here:
 * `PaymentFlowScreen.kt:115` computes `offersPrefill` as `BuildConfig.DEBUG && state.prefillEnabled`,
 * so the button that calls it is never composed in a release build. Only the link is missing.
 *
 * **This is a stopgap.** The reference belongs in the debug source set alongside the button, or behind
 * a seam declared in `main` and implemented per variant. A no-op that exists only to satisfy the
 * compiler is the kind of thing that stays correct exactly as long as the call stays unreachable, and
 * nothing here enforces that.
 *
 * The break is not visible on `main`, which has no release variant. It appears here because this
 * branch re-enabled one for the card reader vendor's signing round.
 */
@Suppress("UNUSED_PARAMETER")
fun fillTestData(
    view: View,
    identity: SampleIdentity,
) = Unit
