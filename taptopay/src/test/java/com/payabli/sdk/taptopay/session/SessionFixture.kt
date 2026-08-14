package com.payabli.sdk.taptopay.session

import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials
import com.payabli.sdk.taptopay.enrollment.ENTRY
import com.payabli.sdk.taptopay.enrollment.EnrollmentFixture
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.attestBody
import com.payabli.sdk.taptopay.enrollment.challengeBody
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.taptopay.enrollment.registerBody
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Bounds every test in this package, so a wedge fails the test that caused it. */
internal val TEST_TIMEOUT = 5.seconds

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
        TapToPaySessionState.Failed(TapToPayFailureReason.INTERNAL),
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
 * A reader that records what it was asked to do, and can be held open.
 *
 * [sawOverlap] is the assertion worth making about serialization. Checking the state afterwards can be
 * satisfied by luck; a flag raised from inside the shared resource cannot, because it says two runs were in
 * there together.
 */
internal class FakeReaderProvider(
    private val trace: MutableList<String>,
    private val gate: (suspend () -> Unit)? = null,
) : ReaderProvider {
    var configureCount: Int = 0
        private set
    var prepareCount: Int = 0
        private set
    var lastCredentials: ReaderCredentials? = null
        private set
    var sawOverlap: Boolean = false
        private set

    private var inside = false

    override suspend fun configure(credentials: ReaderCredentials) {
        trace += "reader:configure"
        configureCount++
        lastCredentials = credentials
    }

    override suspend fun prepareReader() {
        trace += "reader:prepare"
        if (inside) sawOverlap = true
        inside = true
        try {
            prepareCount++
            gate?.invoke()
        } finally {
            inside = false
        }
    }
}

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
) {
    val enrollment = EnrollmentFixture(script, firstReadGate = firstReadGate)

    val reader = FakeReaderProvider(enrollment.trace, readerGate)

    val manager = TapToPaySessionManager(enrollment.logger)

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
