package com.payabli.sdk.core

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.info
import com.payabli.sdk.core.logging.platform.applyHostLogLevel
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.TransportFactory
import com.payabli.sdk.core.network.impl.AuthFailureListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val REASON_ALREADY_INITIALIZED = "a session is already initialized with a different configuration"

/**
 * One session per app process: one token holder, one transport, one state.
 *
 * Per **process**, not per app, and the difference is real rather than pedantic. The installed session and
 * the lock guarding it are companion state, which Android gives every process its own copy of, so an app
 * that runs a service or an activity under `android:process` gets a session in each and a refresh in one is
 * invisible to the other. Nothing here coordinates across that boundary and nothing pretends to.
 *
 * One initialize call and one session serving every capability, never two, and this type makes that
 * structural. Building the auth stack twice produced two token holders, so a refresh de-duplicated inside
 * one was invisible to the other; nothing enforced the sharing, a doc comment requested it.
 *
 * **The type is host-facing, its members mostly are not.** An integrator names this type, calls [initialize]
 * and [setLogLevel], and hands the result to a capability. The transport and [state] are
 * `@RestrictTo(LIBRARY_GROUP)`: reachable from the SDK's own artifacts, including a card-present capability
 * shipped as its own repository, and a Lint error in a host app's build. A detached capability cannot build
 * the auth stack itself, so it must be handed a ready transport; a host app has no business issuing
 * arbitrary authenticated requests.
 *
 * **There is no accessor for the token holder, at any visibility.** A capability needs a transport that is
 * already correct, never the credential inside it.
 *
 * The credential-rejection policy is the transport's default and is not settable here. A host cannot supply
 * one, since `AuthRecoveryPolicy` is `@RestrictTo` and naming it is a Lint error outside this Maven group,
 * and one policy for the whole session is the wrong granularity for the case that wants it: the card-present
 * device routes need refresh refused on those routes alone, which is a property of a transport rather than
 * of a session. Whatever gives them that is the shape that work chooses, not a parameter guessed ahead of it.
 */
public class PayabliSession private constructor(
    private val identity: ConfigIdentity,
    private val machine: SessionStateMachine,
    /**
     * The authenticated transport for this session: bearer injected, one 401 recovered, one replay.
     *
     * One instance, held. iOS rebuilds its decorator on every read of the equivalent property, which leaves
     * nowhere to hang anything stateful.
     */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val transport: PayabliTransport,
) {
    public companion object {
        /**
         * Serializes [initialize] so two callers racing at startup install one session rather than two.
         * A `Mutex` rather than a synchronized block because `initialize` is `suspend`.
         */
        private val lock = Mutex()

        @Volatile
        private var installed: PayabliSession? = null

        /** Written only by the [SessionStateMachine] of whichever session is current. See [state]. */
        private val sink = MutableStateFlow<SdkState>(SdkState.Uninitialized)

        private val logger: SdkLogger get() = LoggerRegistry.of(LogCategory.CORE)

        /**
         * What the SDK can do right now.
         *
         * On the companion for the reason [setLogLevel] is: it has to be readable before an instance exists.
         * A state reachable only through a session could never be [SdkState.Uninitialized], because a session
         * exists only once [initialize] has succeeded, and a consumer of a sealed state would then be writing
         * a branch that can never run.
         *
         * **Process-wide, and there is exactly one of it.** After a finished session is replaced this reads
         * the successor's state, so a caller never has to know that two sessions existed. So does a capability
         * still holding the session that was replaced, and that old session is not usable: its transport is
         * latched and every request through it fails without sending.
         */
        @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public val state: StateFlow<SdkState> = sink.asStateFlow()

        /**
         * Emits [level] and everything more severe. [LogLevel.NONE] silences the SDK.
         *
         * On the companion because an explicit level must beat the automatic one **in either call order**,
         * and the automatic one is derived inside [initialize]. An instance-owned setter could not be
         * called before that.
         *
         * There is no way back to an unset level. This governs **whether** records are emitted, never what
         * they may contain: every record is redacted before it is written.
         */
        public fun setLogLevel(level: LogLevel) {
            LoggerRegistry.setLogLevel(level)
        }

        /**
         * Starts the SDK, or returns the session already started.
         *
         * Idempotent: twice with the same configuration returns the same instance, so an app initializing
         * in `Application.onCreate` and again in an Activity gets one session. Sameness
         * is by value, not object identity, since rebuilding an equal configuration is the ordinary thing.
         *
         * With one exception, because "by value" would otherwise promise more than it delivers: the token
         * provider counts only as **present or absent**, never by which callback it is. A provider written
         * inline is a new object on every call, so comparing them would make this never idempotent for the
         * most ordinary way of writing it. The consequence is that calling this again with a different
         * provider does not replace the one in use, and the session keeps the callback it started with.
         *
         * Two cases are deliberately not idempotent:
         *
         * - A **different** configuration while the session is usable fails, rather than returning one
         *   configured for something else or replacing one capabilities already hold.
         * - Any configuration while the session is [SdkState.ReinitializeRequired] builds a fresh one. That
         *   is the documented recovery, and it has to work with a newly brokered token.
         *
         * It does not rehydrate.
         *
         * **It sets the diagnostic log level as a side effect**, once, from the host build. On a debuggable
         * host the SDK starts emitting at its most verbose; on any other build it stays silent. An app that
         * wants neither calls [setLogLevel] with [LogLevel.NONE], and an explicit level set before or after
         * this call wins either way. Records go to the platform log and never to a callback, and every one
         * is redacted before it is written, so this cannot surface a credential.
         */
        public suspend fun initialize(
            config: PayabliConfig,
            host: HostBindings,
        ): Result<PayabliSession> {
            // First, so everything below is subject to the level it derives. `applicationContext` rather
            // than the reference as given, because the debuggable flag belongs to the application.
            host.appContext.applicationContext.applyHostLogLevel()

            return install(ConfigIdentity(config)) { onAuthFailure ->
                TransportFactory.authenticated(config, onAuthFailure = onAuthFailure)
            }
        }

        /**
         * Same, against an explicit [baseUrl] and with no host bindings, for `:core`'s own JVM tests.
         *
         * It skips the host-log-level derivation, which needs a real `Context`; that line is covered on a
         * device instead. `internal` for the reason `TransportFactory.authenticatedAgainst` is: a member
         * left public in bytecode for testing is an origin override in a shipped artifact.
         */
        @VisibleForTesting
        internal suspend fun initializeAgainst(
            baseUrl: String,
            config: PayabliConfig,
        ): Result<PayabliSession> =
            install(ConfigIdentity(config)) { onAuthFailure ->
                TransportFactory.authenticatedAgainst(baseUrl, config, onAuthFailure = onAuthFailure)
            }

        /**
         * Drops the installed session and puts [state] back, so one test cannot decide the outcome of the
         * next.
         *
         * The outgoing machine is finished first: an in-flight request on it would otherwise publish a
         * terminal state over the value restored here. Both come before the lock, because a caller bounds
         * this call out when a test leaves the lock held, and inside the lock a wedged test would leave the
         * state set for every later class too.
         */
        @VisibleForTesting
        internal suspend fun reset() {
            installed?.machine?.finish()
            sink.value = SdkState.Uninitialized
            lock.withLock { installed = null }
        }

        /**
         * Same, with the transport supplied, so a test can hold the critical section open.
         *
         * Without it the mutex cannot be shown to do anything: the section is a few microseconds of
         * object construction, so two racing callers almost never collide and a concurrency test passes
         * just as happily with the lock removed. Measured, exactly that, which is why this exists.
         */
        @VisibleForTesting
        internal suspend fun initializeWith(
            config: PayabliConfig,
            buildTransport: suspend (AuthFailureListener) -> PayabliTransport,
        ): Result<PayabliSession> = install(ConfigIdentity(config), buildTransport)

        private suspend fun install(
            identity: ConfigIdentity,
            buildTransport: suspend (AuthFailureListener) -> PayabliTransport,
        ): Result<PayabliSession> =
            lock.withLock {
                val current = installed
                // The machine rather than [state], because this is a question about the session install
                // holds. The published value is process-wide, and reading it here would let one stray write
                // replace a healthy session, which is the two-sessions state this type exists to prevent.
                if (current != null && !current.machine.isFinished) {
                    return@withLock if (current.identity == identity) {
                        Result.success(current)
                    } else {
                        Result.failure(
                            PayabliGenericException(
                                PayabliErrorCode.INVALID_CONFIGURATION,
                                REASON_ALREADY_INITIALIZED,
                            ),
                        )
                    }
                }

                val machine = SessionStateMachine(sink)
                // The listener is handed in rather than wired afterwards so no request can complete against a
                // transport whose failures nothing is listening for.
                val session =
                    PayabliSession(
                        identity = identity,
                        machine = machine,
                        transport = buildTransport { machine.markReinitializeRequired() },
                    )
                machine.markReady()
                installed = session

                if (current != null) {
                    logger.warn(
                        LogField.safe("event", "session_replaced"),
                    ) { "replaced a session that required re-initialization" }
                } else {
                    logger.info(LogField.safe("event", "session_initialized")) { "session initialized" }
                }
                Result.success(session)
            }
    }

    /**
     * The parts of a configuration that decide whether two calls mean the same session.
     *
     * A value comparison rather than `PayabliConfig.equals`, which that type deliberately lacks: making it a
     * data class would generate a `toString` and undo the redaction keeping an access token out of a log.
     *
     * The token provider is compared by **presence, not identity**. A host writing the callback inline
     * passes a different object every call, so comparing references would make [initialize] never idempotent
     * for the most ordinary way of writing it.
     *
     * `internal` rather than private so its [toString] can be tested: it holds an access token and an
     * entry point, and `PayabliConfig` withholds both for reasons that do not stop applying because the
     * fields were copied into another type.
     */
    internal class ConfigIdentity(
        config: PayabliConfig,
    ) {
        private val accessToken = config.accessToken
        private val entryPoint = config.entryPoint
        private val environment = config.environment
        private val telemetryEnabled = config.telemetryEnabled
        private val hasTokenProvider = config.tokenProvider != null

        override fun equals(other: Any?): Boolean =
            other is ConfigIdentity &&
                accessToken == other.accessToken &&
                entryPoint == other.entryPoint &&
                environment == other.environment &&
                telemetryEnabled == other.telemetryEnabled &&
                hasTokenProvider == other.hasTokenProvider

        override fun hashCode(): Int {
            var result = accessToken.hashCode()
            result = 31 * result + entryPoint.hashCode()
            result = 31 * result + environment.hashCode()
            result = 31 * result + telemetryEnabled.hashCode()
            result = 31 * result + hasTokenProvider.hashCode()
            return result
        }

        /**
         * Carries no credential and no identifier, matching `PayabliConfig.toString`.
         *
         * The entry point is withheld as well as the token: it names a specific merchant, and this string
         * reaches exception messages and crash reports. It is the same rule and the same reason, and it
         * applies here because this type holds the same two fields.
         */
        override fun toString(): String =
            "ConfigIdentity(environment=$environment, telemetryEnabled=$telemetryEnabled, " +
                "tokenProvider=${if (hasTokenProvider) "present" else "absent"})"
    }
}
