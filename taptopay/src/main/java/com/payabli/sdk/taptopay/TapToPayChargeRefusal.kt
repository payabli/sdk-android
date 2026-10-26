package com.payabli.sdk.taptopay

import java.util.Collections

/**
 * A refusal of the charge itself.
 *
 * [TapToPayException.refusal] carries one when the failure refused the charge, and is null for every
 * other failure. Which member is present is what a host branches on: it names what this refusal asks
 * them to do, and the classification on [TapToPayException.type] is read exactly as for every other
 * failure.
 */
public sealed class TapToPayChargeRefusal {
    /**
     * The paypoint requires customer fields the charge did not carry.
     *
     * @property requiredFields the customer fields to collect before charging again, empty when the
     *   refusal named none.
     */
    public class CustomerDataRefused(
        requiredFields: List<String>,
    ) : TapToPayChargeRefusal() {
        /** Copied and wrapped: the caller's list cannot change what this carries. */
        public val requiredFields: List<String> = Collections.unmodifiableList(requiredFields.toList())
    }
}
