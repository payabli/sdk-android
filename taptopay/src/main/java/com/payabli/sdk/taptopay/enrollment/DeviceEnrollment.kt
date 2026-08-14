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
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceAttestationBinding
import com.payabli.sdk.taptopay.attestation.device.DeviceIdentity
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
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
 * - `/challenge` mints a fresh value per call, and `/attest` consumes it on read.
 * - `/register` keys on the hardware identifier. A device still awaiting activation gets the same handle
 *   back, and the key's thumbprint is unchanged, so the service keeps any attestation already written.
 * - The fixed key handle returns the key the previous attempt used. No attempt strands one.
 * - `/attest` revokes prior bindings before inserting, so the same key is a replacement, not a conflict.
 *
 * The transport can also send `/attest` or `/activate` twice on its own: credential recovery replays a
 * rejection that was an exact 401. That is answered before any controller runs, so nothing is consumed.
 *
 * **Repeating the sequence after activation completes is destructive.** `/register` retires an active
 * device and issues a new handle, costing the merchant a fresh out-of-band code. [enroll] therefore checks
 * what it already knows before calling anything, and a storage failure that may be momentary is raised
 * instead of being read as "nothing stored".
 *
 * **No path here deletes the device key.** No `/attest` refusal reports the key as rejected: each one is
 * about the application, the paypoint, the device state, or the challenge.
 */
internal class DeviceEnrollment(
    /**
     * The paypoint every call is scoped to.
     *
     * Held, not passed per call. The service scopes a device by paypoint: activating against one entry a
     * device attested under another is answered as a device that does not exist. Holding it makes that
     * mismatch unwritable at the call site.
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
     * the layer that owns moving them off the caller's thread. Note that the attestor and the store already
     * suspend and hold their own dispatchers — **do not** wrap those as well; a second hop onto the same
     * pool is a hop for the symmetry of it.
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
     * Brings the device to attested, and says whether it still owes an activation code.
     *
     * Returns without touching the network when the device is already known and the key it was bound to is
     * still the one at the handle.
     */
    suspend fun enroll(): EnrollmentOutcome =
        lock.withLock {
            val identity = withContext(dispatcher) { deviceKey.publicKey() }

            val known = store.read()
            if (known != null) {
                if (known.entry == entry && known.keyId == identity.identity) {
                    logger.debug(LogField.safe("event", "device_already_enrolled")) {
                        "device identity is current, skipping the cold sequence"
                    }
                    return@withLock EnrollmentOutcome(activationRequired = !known.activated)
                }
                // Either this session is against a different paypoint, or the key was replaced under us.
                // Both mean the record describes something that is no longer true. Deciding it here keeps it
                // a local answer; left to the service it comes back as a revoked attestation, which means
                // something else and would be read that way by whoever is looking at it later.
                logger.warn(LogField.safe("event", "device_identity_stale")) {
                    "stored device identity does not describe this device, re-enrolling"
                }
                store.clear()
            }

            val challenge = client.challenge(entry)

            val registration =
                client.register(
                    entry = entry,
                    hardwareId = description.hardwareId,
                    keyId = identity.identity,
                    deviceName = description.deviceName,
                    model = description.model,
                    osVersion = description.osVersion,
                )

            if (known != null) {
                reportRowChange(registration.outcome)
            }

            // Awaiting activation does not short-circuit: `/attest` accepts a device in that state and
            // writes the row `/activate` verifies against, so stopping here would leave nothing to verify.
            val activationRequired = registration.isPending

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
                        activated = !activationRequired,
                    ),
                )
            }

            EnrollmentOutcome(activationRequired = activationRequired)
        }

    /**
     * Spends the six-digit code the merchant issued out of band.
     *
     * **No challenge is requested first.** The sibling SDK does, and the call is dead: its result is
     * discarded, the activation body carries nothing to correlate it with, and what the service verifies is
     * the assertion, signed over its timestamp. It costs a round trip that can fail on its own and surface
     * as an attestation error while someone is typing a perfectly good code.
     *
     * The code's shape is checked here. The service counts a wrong code against a five-attempt lockout, and
     * a typo should not spend one.
     */
    suspend fun confirmActivation(activationCode: String) {
        lock.withLock {
            if (!SIX_DIGITS.matches(activationCode)) throw DeviceActivationException.CodeMalformed()

            val known = store.read() ?: throw DeviceActivationException.NotEnrolled()

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
                // alone, including the other refusal that arrives under the same result code.
                if (declined is DeviceActivationException.AttestationRevoked ||
                    declined is DeviceActivationException.DeviceUnknown
                ) {
                    forget("revoked")
                }
                throw declined
            }

            withContext(NonCancellable) { store.write(known.activated()) }
        }
    }

    /** Forgets the device without touching its key, so the next [enroll] runs the cold sequence. */
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
        withContext(NonCancellable) { store.clear() }
        logger.warn(LogField.safe("event", "device_identity_cleared"), LogField.safe("state", state)) {
            "discarded the stored device identity"
        }
    }

    /**
     * Says so when the service replaced the row this device was using.
     *
     * Diagnostic only, and nothing branches on it — by this point the returned handle has been taken and the
     * record is about to be rewritten, which is correct in every case. What it catches is the combination
     * that should not happen: this device held a record, so it expected the service to recognise it, and the
     * service says it created something new instead. That is the signature of a hardware identifier that is
     * not staying still, or of a row the service can no longer find.
     *
     * Absent from the response until the service starts sending it, which reads as nothing to report.
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
