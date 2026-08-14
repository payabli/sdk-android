package com.payabli.sdk.taptopay.enrollment

/**
 * Where [DeviceEnrollment.enroll] left the device.
 *
 * **A value, not a thrown signal.** Owing activation is the expected end of a first run, not a failure, and
 * the sibling SDK throws it after a fully successful sequence — which leaves whatever consumes it reading a
 * success out of a catch block. The state machine that will consume this is easier to get right if the happy
 * path returns.
 *
 * Carries no device identifier. Nothing above needs one, and a value handed across the module boundary is a
 * value that ends up in a host's log.
 */
internal class EnrollmentOutcome(
    /** True when the service is still waiting for the code the merchant issues out of band. */
    val activationRequired: Boolean,
) {
    override fun toString(): String = "EnrollmentOutcome(activationRequired=$activationRequired)"
}
