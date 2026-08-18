package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.devicekey.DeviceKey
import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.taptopay.attestation.AppAttestor
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertion
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceAttestationBinding
import com.payabli.sdk.taptopay.attestation.device.DeviceIdentity
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.EntryPointFailures
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Drives a device from nothing to attested, then consumes the code that activates it.
 *
 * The wire client below holds no sequencing. This is where it lives: which call follows which, what is
 * kept, and what happens when one fails halfway.
 *
 * **Retry the whole of [enroll], never one call inside it.** Nothing here is wrapped in a retry, and the
 * sequence is safe to repeat from the top:
 *
 * - a challenge is fresh per call and single-use, so a repeat starts from a new one.
 * - registering keys on the hardware identifier, so a repeat returns the same device rather than a second,
 *   and an attestation already written for the same key survives it.
 * - the fixed key handle returns the key the previous attempt used. No attempt strands one.
 * - attesting replaces a prior binding for the same key rather than colliding with it.
 *
 * The transport can also send one of these calls twice on its own, when credential recovery replays a
 * rejected request. That is refused with nothing consumed.
 *
 * **Repeating the sequence after activation completes is destructive**: registering again replaces the
 * device's handle, costing the merchant a fresh out-of-band code. [enroll] therefore checks what it already
 * knows before calling anything, and a storage failure that may be momentary is raised instead of being read
 * as "nothing stored".
 *
 * **No path here deletes the device key.** No refusal in this sequence is about the key itself, so none of
 * them is a reason to discard it.
 */
internal class DeviceEnrollment(
    /**
     * The paypoint every call is scoped to.
     *
     * Held, not passed per call. **A device holds a separate binding for each paypoint**, and one cannot
     * stand for another. Holding the entry is what keys every read, write and clear below, so reaching
     * another paypoint's binding is unwritable at the call site rather than checked for afterwards.
     */
    private val entry: String,
    private val appId: String,
    private val client: DeviceServiceClient,
    private val attestor: AppAttestor,
    private val deviceKey: DeviceKey,
    /** Must sign with [deviceKey]. Injected so a test can fix the clock. */
    private val signer: DeviceAssertionSigner,
    private val store: AttestedDeviceStore,
    private val description: DeviceDescription,
    /**
     * Where the blocking key-store work runs.
     *
     * Required, no default. `DeviceAssertionSigner.sign` and `DeviceKey.publicKey` both block, and this is
     * the layer that moves them off the caller's thread. The attestor and the store already suspend and
     * hold their own dispatchers — **do not** wrap those; it is a second hop onto the same pool.
     */
    private val dispatcher: CoroutineDispatcher,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /**
     * Serialises the two entry points against each other.
     *
     * Without it a concurrent [enroll] can re-register underneath a [confirmActivation] and the code is then
     * spent against a handle the service has just replaced.
     */
    private val lock = Mutex()

    /**
     * Brings the device to attested.
     *
     * Returns without touching the network when the device is already known and the key it was bound to is
     * still the one at the handle. That answer carries no activation claim, because nothing was asked — see
     * [EnrollmentOutcome].
     */
    suspend fun enroll(): EnrollmentOutcome =
        lock.withLock {
            val identity = withContext(dispatcher) { deviceKey.publicKey() }

            // Scoped to this entry point, so another one's binding is neither read nor disturbed here.
            val known = store.read(entry)

            if (known != null && known.keyId == identity.identity) {
                logger.debug(LogField.safe("event", "device_already_enrolled")) {
                    "device identity is current, skipping the cold sequence"
                }
                return@withLock EnrollmentOutcome.AlreadyAttested
            }

            if (known != null) {
                // The key at the handle was replaced, so the record names a binding this device can no
                // longer sign for. Discarded here, locally, rather than left for a later refusal to
                // classify.
                logger.warn(LogField.safe("event", "device_identity_stale")) {
                    "stored device identity names a key this device no longer holds, re-enrolling"
                }
                store.clear(entry)
            }

            val challenge = client.challenge(entry, failureMapper = EntryPointFailures)

            val registration =
                client.register(
                    entry = entry,
                    hardwareId = description.hardwareId,
                    keyId = identity.identity,
                    deviceName = description.deviceName,
                    model = description.model,
                    osVersion = description.osVersion,
                    failureMapper = EntryPointFailures,
                )

            if (known != null) {
                reportRowChange(registration.outcome)
            }

            // Awaiting activation does not short-circuit: attesting is what activation later verifies
            // against, so stopping here would leave nothing to verify.
            //
            // Keyed on `isActive`, not on the negation of `isPending`. An absent or unrecognized status
            // makes both false, and reporting that as active is the direction a caller cannot recover from:
            // it stops asking for a code the device still owes.
            val activationRequired = !registration.isActive

            val token = attestor.attest(DeviceAttestationBinding.nonceChallenge(challenge.challenge))

            client.attest(
                entry = entry,
                challengeId = challenge.challengeId,
                identity =
                    DeviceIdentity(
                        deviceId = registration.deviceId,
                        keyId = identity.identity,
                        publicKey = Base64.getEncoder().encodeToString(identity.point),
                    ),
                appId = appId,
                token = token,
                failureMapper = EntryPointFailures,
            )

            // One write, so there is no ordering to get right and no half-written state to compensate for.
            // Uncancellable because the binding exists at the service by this point: dropping the write to a
            // cancellation costs a redundant attestation on the next run for nothing.
            withContext(NonCancellable) {
                store.write(
                    AttestedDevice(
                        entry = entry,
                        deviceId = registration.deviceId,
                        keyId = identity.identity,
                    ),
                )
            }

            EnrollmentOutcome.Attested(activationRequired = activationRequired)
        }

    /**
     * Spends the six-digit code the merchant issued out of band.
     *
     * **No challenge is requested first.** The sibling SDK does, and the call is dead: its result is
     * discarded, the activation body carries nothing to correlate it with, and what is verified is the
     * assertion, signed over its timestamp. It costs a round trip that can fail on its own and surface as an
     * attestation error while someone is typing a perfectly good code.
     *
     * The code's shape is checked here, because a code that is sent counts against the attempt limit and a
     * typo should not spend one.
     */
    suspend fun confirmActivation(activationCode: String) {
        lock.withLock {
            if (!SIX_DIGITS.matches(activationCode)) throw DeviceActivationException.CodeMalformed()

            // Scoped to this entry point. A binding held for another one names a device this entry point
            // does not have, so for this one the device is simply not enrolled.
            val known = store.read(entry) ?: throw DeviceActivationException.NotEnrolled()

            val assertion =
                try {
                    withContext(dispatcher) { signer.sign(known.deviceId) }
                } catch (lost: DeviceKeyException.KeyLost) {
                    // The key store has already discarded the key, so the record names one that cannot
                    // exist. Nothing to retry and nothing to activate against.
                    forget("key_lost")
                    throw lost
                }

            try {
                client.activate(
                    entry = entry,
                    deviceId = known.deviceId,
                    activationCode = activationCode,
                    assertion = assertion,
                    failureMapper = DeviceActivationFailures(logger),
                )
            } catch (declined: DeviceActivationException) {
                // Exactly two outcomes say the thing this record names is gone. Everything else leaves it
                // alone, and reaching either of these takes a positive classification.
                if (declined is DeviceActivationException.AttestationRevoked ||
                    declined is DeviceActivationException.DeviceUnknown
                ) {
                    forget("revoked")
                }
                throw declined
            }

            // Nothing is written on success. Whether this device is active is not this SDK's to hold: it can
            // change without this SDK being involved, and a copy here would be a claim nobody re-checks.
        }
    }

    /**
     * Proves possession of the key this paypoint's device was attested with, or null when there is no such
     * device.
     *
     * Here rather than at the caller because the store, the signer, the paypoint check and the dispatcher
     * the blocking signature needs are all held here already, and every one of them would otherwise be
     * repeated somewhere else.
     *
     * Null rather than a [DeviceActivationException]: that vocabulary answers why an activation did not
     * complete, and this is not an activation.
     *
     * Takes the same lock as the rest, so a record cannot be read while a re-registration is replacing the
     * handle it names.
     */
    suspend fun assertion(): DeviceAssertion? =
        lock.withLock {
            val known = store.read(entry) ?: return@withLock null
            try {
                withContext(dispatcher) { signer.sign(known.deviceId) }
            } catch (lost: DeviceKeyException.KeyLost) {
                // The key store has already discarded the key, so the record names a binding this device
                // can no longer sign for. Same disposal as the activation path makes for the same finding.
                forget("key_lost")
                throw lost
            }
        }

    /**
     * Forgets this entry point's device without touching its key, so the next [enroll] runs the cold
     * sequence.
     *
     * Every other entry point's binding is left where it was, by the shape of the store rather than by a
     * check here: removing one this coordinator did not make would send that entry point's next enrollment
     * through a registration it does not need.
     */
    suspend fun reset() {
        lock.withLock { forget("reset") }
    }

    /**
     * Discards the record, never the key.
     *
     * Uncancellable for the reason the successful write is: the service has already spoken, and a coroutine
     * withdrawing here leaves a record known to be dead, which walks the next attempt into the same refusal.
     */
    private suspend fun forget(state: String) {
        withContext(NonCancellable) { store.clear(entry) }
        logger.warn(LogField.safe("event", "device_identity_cleared"), LogField.safe("state", state)) {
            "discarded the stored device identity"
        }
    }

    /**
     * Says so when a registration did not recognize the device this record named.
     *
     * Diagnostic only, and nothing branches on it — by this point the returned handle has been taken and the
     * record is about to be rewritten, which is correct in every case. What it catches is the combination
     * that should not happen: this device held a binding **for this paypoint**, so it expected to be
     * recognized, and something new came back instead. That is the signature of a hardware identifier that is
     * not staying still, or of a device that can no longer be found.
     *
     * A device holding no binding for this paypoint carries no such expectation, and a new device is the
     * right answer there, so the caller does not reach this.
     *
     * Absent from the response until it starts being sent, which reads as nothing to report.
     */
    private fun reportRowChange(outcome: String?) {
        if (outcome == null || outcome == OUTCOME_REUSED || outcome == OUTCOME_UNCHANGED) return
        logger.warn(
            LogField.safe("event", "device_row_replaced"),
            LogField.safe("outcome", outcome),
        ) { "the device service did not recognize the device this record named" }
    }

    private companion object {
        val SIX_DIGITS = Regex("^[0-9]{6}$")
        const val OUTCOME_REUSED = "reused"
        const val OUTCOME_UNCHANGED = "unchanged"
    }
}
