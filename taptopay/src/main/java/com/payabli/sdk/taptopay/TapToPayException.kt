package com.payabli.sdk.taptopay

import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionState

/**
 * A card-present call that did not succeed.
 *
 * What to do next is on [PayabliTTP.state], where a failure that changed the session appears as
 * [TapToPaySessionState.Failed] carrying a [TapToPayFailureReason].
 */
public class TapToPayException internal constructor(
    message: String,
    cause: Throwable?,
) : Exception(message, cause)
