package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.taptopay.ChargeKeyStore
import com.payabli.sdk.taptopay.enrollment.ENTRY
import com.payabli.sdk.taptopay.enrollment.EnrollmentFixture
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.attestBody
import com.payabli.sdk.taptopay.enrollment.challengeBody
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.taptopay.enrollment.registerBody
import com.payabli.sdk.taptopay.provider.FakeTapToPayProvider
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Bounds every test in this package, so a wedge fails the test that caused it. */
internal val TEST_TIMEOUT = 5.seconds

/** The stem every key [SessionFixture] reserves is built from. */
internal const val MINTED_KEY = "minted"

/**
 * One state per member, for the tests that walk all of them.
 *
 * One list for the package. A second copy drifts, and a copy that loses a member narrows whatever it feeds
 * without failing anything.
 */
internal val EVERY_SESSION_STATE: List<TapToPaySessionState> =
    listOf(
        TapToPaySessionState.Idle,
        TapToPaySessionState.AttestingDevice,
        TapToPaySessionState.FetchingConfig,
        TapToPaySessionState.InitializingReader,
        TapToPaySessionState.Ready,
        TapToPaySessionState.SessionExpired,
        TapToPaySessionState.Reinitializing,
        TapToPaySessionState.PendingActivation,
        TapToPaySessionState.Failed(TapToPayFailureReason.SDK_INTERNAL_ERROR),
    )

/** Bounds one await, so a stranded claim reports what was stranded. */
private val COMPLETION_TIMEOUT = 3.seconds

/**
 * Awaits [block] under a deadline of its own.
 *
 * Without it a caller left waiting on a claim nobody completes fails as "the test timed out", which names
 * no claim.
 */
internal suspend fun <T> completing(
    what: String,
    block: suspend () -> T,
): T =
    withTimeoutOrNull(COMPLETION_TIMEOUT) { block() }
        ?: throw AssertionError("$what never completed: the session claim was stranded")

/**
 * A session wired to fakes, sharing one trace with the enrollment underneath it.
 *
 * The trace spans the transport, the store and the reader, because the properties worth asserting here —
 * that a second run sent nothing while the first held the region, and that the reader was entered once —
 * span all three and no per-fake list can show that.
 */
internal class SessionFixture(
    script: RouteScript,
    firstReadGate: (suspend () -> Unit)? = null,
    readerGate: (suspend () -> Unit)? = null,
    eligibilityFailure: Throwable? = null,
) {
    val enrollment = EnrollmentFixture(script, firstReadGate = firstReadGate)

    val reader = FakeTapToPayProvider(enrollment.trace, readerGate, eligibilityFailure)

    val manager = TapToPaySessionManager(enrollment.logger)

    /** Counted rather than random, so a test can tell one reserved key from the next. */
    val minted: AtomicInteger = AtomicInteger()

    /**
     * Over [EnrollmentFixture.storage], which is the one backing store a device has.
     *
     * A second fixture built on the same storage is what two terminals for one entry point look like, which
     * is the shape the key has to survive.
     */
    val keys =
        ChargeKeyStore(
            enrollment.storage,
            newKey = { "$MINTED_KEY-${minted.incrementAndGet()}" },
            logger = enrollment.logger,
        )

    val coordinator =
        TapToPaySessionCoordinator(
            entry = ENTRY,
            enrollment = enrollment.enrollment,
            client = enrollment.client,
            reader = reader,
            manager = manager,
            logger = enrollment.logger,
        )

    /** Only the transport's half of the trace, for asserting call order alone. */
    val routes: List<String> get() = enrollment.routes

    /** Every request sent, for asserting what one of them carried rather than which were sent. */
    val requests: List<PayabliRequest> get() = enrollment.requests

    /** The requests sent to [path], in order. */
    fun requestsTo(path: String): List<PayabliRequest> = enrollment.requests.filter { it.path == path }

    val state: TapToPaySessionState get() = manager.state.value

    fun seedRecord() = enrollment.seedRecord()

    /** The full cold script, for a device that registers already active. */
    companion object {
        fun coldScript(): RouteScript =
            RouteScript(
                RouteScript.CHALLENGE to listOf(challengeBody()),
                RouteScript.REGISTER to listOf(registerBody(status = "active")),
                RouteScript.ATTEST to listOf(attestBody()),
                RouteScript.CONFIG to listOf(configBody()),
            )
    }
}
