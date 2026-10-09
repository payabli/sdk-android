package com.payabli.sdk.taptopay.enrollment

/**
 * What [DeviceEnrollment.enroll] did; only a run that reached the service says whether the device owes a code.
 * No copy of the service's activation state is kept, and owing activation is a value, not a throw.
 * Neither case carries a device handle: a value handed across the module boundary ends up in a host's log.
 */
internal sealed class EnrollmentOutcome {
    /**
     * The device was already attested with the key at the handle, so nothing was asked of the service.
     *
     * **This says nothing about activation.** The device may owe a code or may not; this run did not find
     * out. Whoever consumes this learns it from the next live call.
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
