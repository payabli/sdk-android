package com.payabli.sdk.taptopay.enrollment

/**
 * What [DeviceEnrollment.enroll] did, and what it is entitled to say about activation.
 *
 * **Two cases because only one of them asked.** A run that reaches the service is told whether the device
 * still owes a code; a run answered from the stored binding was told nothing, and has no basis for a claim.
 * Collapsing both into one boolean forces the second to guess, and the only way to make that guess look
 * informed is to keep a copy of the service's activation state — which then goes stale without anything
 * noticing.
 *
 * The sibling SDK does not keep that copy either. It re-checks on a live call, and this converges on the
 * same shape once Android has a route that reports device status.
 *
 * **Owing activation is a value, not a throw.** The sibling SDK throws it after a fully successful sequence,
 * which leaves whatever consumes it reading a success out of a catch block.
 *
 * Neither case carries a device handle. Nothing above needs one, and a value handed across the module
 * boundary is a value that ends up in a host's log.
 */
internal sealed class EnrollmentOutcome {
    /**
     * The device was already attested with the key at the handle, so nothing was asked of the service.
     *
     * **This says nothing about activation.** The device may owe a code or may not; this run did not find
     * out. Whoever consumes this learns it from the next live call, as the sibling SDK does.
     */
    object AlreadyAttested : EnrollmentOutcome() {
        override fun toString(): String = "AlreadyAttested"
    }

    /**
     * The cold sequence ran and the attestation was accepted.
     *
     * [activationRequired] is what registration reported in **this** run, never a remembered value.
     */
    class Attested(
        val activationRequired: Boolean,
    ) : EnrollmentOutcome() {
        override fun toString(): String = "Attested(activationRequired=$activationRequired)"
    }
}
