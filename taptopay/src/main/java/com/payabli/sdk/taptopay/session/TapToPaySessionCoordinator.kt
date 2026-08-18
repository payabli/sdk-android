package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.attestation.device.EntryPointFailures
import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials
import com.payabli.sdk.taptopay.enrollment.DeviceEnrollment
import com.payabli.sdk.taptopay.enrollment.EnrollmentOutcome
import com.payabli.sdk.taptopay.provider.TapToPayProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Drives a card-present session: builds one, repairs one, and spends an activation code against one.
 *
 * **All three of those mutate the same state and reach the same reader, so they never overlap.** What a
 * second caller gets:
 *
 * - A caller of the **same** kind joins the one already in flight, running or waiting its turn, and is
 *   given its outcome, success or failure. It does no work of its own.
 * - A caller of a **different** kind waits for it and then runs. Repairing a session skips attestation and
 *   building one does not, so they cannot share an answer.
 * - A caller whose owner withdrew is given [TapToPaySessionException.SetupAbandoned] and may ask again.
 *   Handing on the owner's cancellation would make the waiter's own scope look like it is unwinding.
 *
 * Exclusion is [region]; joining is [inFlight]. Each is held by its own tests.
 *
 * **Locks are taken in one order and only one:** this region, then the enrollment coordinator's, then the
 * attestor's. The state monitor is never held across any of them.
 */
internal class TapToPaySessionCoordinator(
    private val entry: String,
    private val enrollment: DeviceEnrollment,
    private val client: DeviceServiceClient,
    private val reader: TapToPayProvider,
    private val manager: TapToPaySessionManager,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /** Where the session has got to. Safe to collect at any time; reading it takes no lock. */
    val state: StateFlow<TapToPaySessionState> get() = manager.state

    /** Serialises the work. Held for a whole run, so no two runs are ever inside the reader together. */
    private val region = Mutex()

    /** Guards [inFlight] alone. Nothing suspends while it is held. */
    private val claims = Mutex()

    /** One claim per kind, so a caller joins work of its own kind whatever else is queued. */
    private val inFlight = mutableMapOf<SessionWorkKind, Claim>()

    private class Claim(
        val kind: SessionWorkKind,
        val done: CompletableDeferred<Unit>,
    )

    private sealed interface RunPlan {
        class Join(
            val done: CompletableDeferred<Unit>,
        ) : RunPlan

        class Own(
            val claim: Claim,
        ) : RunPlan
    }

    /**
     * Builds the session from wherever it stands: attest if needed, fetch the credentials, bring the reader
     * up.
     *
     * Safe to call again at any time, including while one is already running. It starts from a known state,
     * so it does not depend on what the last attempt left behind.
     *
     * Fails with [TapToPaySessionException.PendingActivation] when the device still owes a code.
     */
    suspend fun initialize() = runExclusively(SessionWorkKind.INITIALIZE) { runInitialize() }

    /**
     * Repairs a session whose reader is spent, and does nothing to one that is ready.
     *
     * Cheaper than [initialize] because it does not attest. Two failures follow from that, and they are
     * different questions. A state this cannot be entered from is refused with
     * [TapToPaySessionException.NotRecoverable]. A device whose stored identity is gone gets as far as
     * fetching the credentials and fails with [TapToPaySessionException.AttestationRequired], because
     * attesting is what would restore it. [initialize] is the remedy for both.
     */
    suspend fun reinitializeIfNeeded() = runExclusively(SessionWorkKind.REINITIALIZE) { runReinitializeIfNeeded() }

    /**
     * Spends the code the merchant issued out of band.
     *
     * Inside the same region as the two above, because it moves the same state and a code spent against a
     * handle a concurrent registration has just replaced is a code wasted. A refused code leaves the session
     * exactly where it was, since the device still owes one.
     */
    suspend fun confirmActivation(activationCode: String) =
        runExclusively(SessionWorkKind.ACTIVATE) { runConfirmActivation(activationCode) }

    /** Decides whether to join or to run, under [claims], and does neither while holding it. */
    private suspend fun runExclusively(
        kind: SessionWorkKind,
        work: suspend () -> Unit,
    ) {
        val plan =
            claims.withLock {
                val existing = inFlight[kind]
                if (existing != null) {
                    RunPlan.Join(existing.done)
                } else {
                    Claim(kind, CompletableDeferred<Unit>()).also { inFlight[kind] = it }.let(RunPlan::Own)
                }
            }
        when (plan) {
            is RunPlan.Join -> {
                logger.debug(
                    LogField.safe("event", "ttp_session_joined"),
                    LogField.safe("phase", kind.diagnosticName),
                ) { "joined the session work already running" }
                plan.done.await()
            }

            is RunPlan.Own -> own(plan.claim, work)
        }
    }

    private suspend fun own(
        claim: Claim,
        work: suspend () -> Unit,
    ) {
        try {
            region.withLock { work() }
        } catch (withdrawn: CancellationException) {
            // Nothing failed and nothing is in progress. Idle is also the one target that is never refused.
            withContext(NonCancellable) { manager.settle(TapToPaySessionState.Idle) }
            release(claim, TapToPaySessionException.SetupAbandoned())
            throw withdrawn
        } catch (failure: Exception) {
            TapToPaySessionFailures.landingFor(failure)?.let(manager::settle)
            release(claim, failure)
            throw failure
        } catch (fatal: Throwable) {
            // An OutOfMemoryError reaches the caller unchanged. The claim is still released, or every later
            // caller of this kind waits for something that will never complete. Waiters are told the run
            // failed rather than that it withdrew, and the cause stays with the owner: handing it on would
            // give every waiter a reference to whatever died.
            release(claim, TapToPaySessionException.SetupFailed())
            throw fatal
        }
        release(claim, null)
    }

    /**
     * Clears the slot and then answers everyone waiting on it, in that order, so a caller woken here never
     * finds a claim that has already finished.
     *
     * Uncancellable because liveness depends on it. A claim left set with nobody to complete it wedges every
     * later caller of its kind, and whether [Mutex.withLock] observes an already-cancelled job depends on
     * whether it has to suspend.
     *
     * The slot is cleared only when it still holds this claim, so a run finishing late leaves a successor's
     * claim in place.
     */
    private suspend fun release(
        claim: Claim,
        outcome: Throwable?,
    ) = withContext(NonCancellable) {
        claims.withLock { if (inFlight[claim.kind] === claim) inFlight.remove(claim.kind) }
        if (outcome == null) claim.done.complete(Unit) else claim.done.completeExceptionally(outcome)
    }

    /**
     * The cold path, and the warm one, which differ only in what enrollment finds.
     *
     * The handset is asked first, before the state is touched and before anything is sent. A device that
     * cannot take contactless payments would fail somewhere further in regardless, and every one of those
     * places would report it as something else.
     *
     * Then a reset, whatever the caller left behind, since the table of legal moves is narrow.
     */
    private suspend fun runInitialize() {
        reader.checkEligibility()
        manager.reset()
        val outcome = manager.advance(TapToPaySessionState.AttestingDevice) { enrollment.enroll() }
        if (outcome is EnrollmentOutcome.Attested && outcome.activationRequired) {
            // Registration already said so, so there is nothing to learn from asking for the credentials.
            throw TapToPaySessionException.PendingActivation()
        }
        bringReaderUp()
    }

    /**
     * The repair, which does not attest.
     *
     * A ready session is left alone. That is the whole reason a charge can call this without a round trip.
     */
    private suspend fun runReinitializeIfNeeded() {
        when (val current = state.value) {
            TapToPaySessionState.Ready -> return
            TapToPaySessionState.SessionExpired ->
                // The only state the table lets a re-initialization be entered from.
                manager.advance(TapToPaySessionState.Reinitializing)

            TapToPaySessionState.Idle, is TapToPaySessionState.Failed -> Unit
            else -> throw TapToPaySessionException.NotRecoverable(current)
        }
        bringReaderUp()
    }

    /** The half both entry points share: credentials, then a reader configured with them. */
    private suspend fun bringReaderUp() {
        val credentials = manager.advance(TapToPaySessionState.FetchingConfig) { fetchConfig() }
        manager.advance(TapToPaySessionState.InitializingReader) {
            reader.configure(credentials)
            reader.prepareReader()
        }
        manager.advance(TapToPaySessionState.Ready)
    }

    /**
     * The credentials, and the one place a warm start can learn the device still owes a code.
     *
     * Fetched every time. They are never stored and never held past the reader that takes them.
     */
    private suspend fun fetchConfig(): ReaderCredentials {
        val assertion = enrollment.assertion() ?: throw TapToPaySessionException.AttestationRequired()
        return try {
            client.config(entry, assertion, failureMapper = EntryPointFailures).credentials
        } catch (inactive: DeviceServiceException.Forbidden) {
            // An envelope decline and a transport status both arrive as this one type.
            throw TapToPaySessionException.PendingActivation(inactive)
        } catch (stale: DeviceServiceException.NotAttested) {
            // Never attested again from in here: that spends a challenge inside a call that is already
            // failing.
            enrollment.reset()
            throw TapToPaySessionException.AttestationRequired(stale)
        }
    }

    /**
     * Spends the code, then puts the session back to the start so it can be built.
     *
     * Whether the device is active is not this SDK's to hold, so nothing is recorded here. A code that is
     * refused leaves the state alone, because the device still owes one.
     */
    private suspend fun runConfirmActivation(activationCode: String) {
        enrollment.confirmActivation(activationCode)
        manager.settle(TapToPaySessionState.Idle)
    }
}

/** Which of the three entry points is running, so two of the same kind can share one run. */
internal enum class SessionWorkKind {
    INITIALIZE,
    REINITIALIZE,
    ACTIVATE,
    ;

    val diagnosticName: String get() = name.lowercase()
}
