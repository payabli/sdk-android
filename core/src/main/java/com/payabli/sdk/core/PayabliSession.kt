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
import com.payabli.sdk.core.network.AuthRecoveryPolicy
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.TransportFactory
import com.payabli.sdk.core.network.impl.AuthFailureListener
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val REASON_ALREADY_INITIALIZED = "a session is already initialized with a different configuration"

/**
 * One session per app: one token holder, one transport, one state.
 *
 * One initialize call and one session serving every capability, never two, and this type makes that
 * structural. Building the auth stack twice produced two token holders, so a refresh de-duplicated inside
 * one was invisible to the other; nothing enforced the sharing, a doc comment requested it.
 *
 * **The type is host-facing, its members mostly are not.** An integrator names this type, calls [initialize]
 * and [setLogLevel], and hands the result to a capability. The transport and the state are
 * `@RestrictTo(LIBRARY_GROUP)`: reachable from the SDK's own artifacts, including a card-present capability
 * shipped as its own repository, and a Lint error in a host app's build. A detached capability cannot build
 * the auth stack itself, so it must be handed a ready transport; a host app has no business issuing
 * arbitrary authenticated requests.
 *
 * **There is no accessor for the token holder, at any visibility.** A capability needs a transport that is
 * already correct, never the credential inside it.
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
    /** What this session can do right now. [SessionStateMachine] is the only writer. */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val state: StateFlow<SdkState>
        get() = machine.state

    public companion object {
        /**
         * Serializes [initialize] so two callers racing at startup install one session rather than two.
         * A `Mutex` rather than a synchronized block because `initialize` is `suspend`.
         */
        private val lock = Mutex()

        @Volatile
        private var installed: PayabliSession? = null

        private val logger: SdkLogger get() = LoggerRegistry.of(LogCategory.CORE)

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
         * Two cases are deliberately not idempotent:
         *
         * - A **different** configuration while the session is usable fails, rather than returning one
         *   configured for something else or replacing one capabilities already hold.
         * - Any configuration while the session is [SdkState.ReinitializeRequired] builds a fresh one. That
         *   is the documented recovery, and it has to work with a newly brokered token.
         *
         * It does not rehydrate.
         *
         * [recovery] narrows or widens what counts as a credential rejection. The card-present device routes
         * need it narrowed, because they pin the token captured at attestation and a refresh rotates it out
         * of the match.
         */
        public suspend fun initialize(
            config: PayabliConfig,
            host: HostBindings,
            recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
        ): Result<PayabliSession> {
            // First, so everything below is subject to the level it derives. `applicationContext` rather
            // than the reference as given, because the debuggable flag belongs to the application.
            host.appContext.applicationContext.applyHostLogLevel()

            return install(ConfigIdentity(config)) { onAuthFailure ->
                TransportFactory.authenticated(config, recovery, onAuthFailure = onAuthFailure)
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
            recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
        ): Result<PayabliSession> =
            install(ConfigIdentity(config)) { onAuthFailure ->
                TransportFactory.authenticatedAgainst(baseUrl, config, recovery, onAuthFailure = onAuthFailure)
            }

        /** Drops the installed session so one test cannot decide the outcome of the next. */
        @VisibleForTesting
        internal suspend fun reset() {
            lock.withLock { installed = null }
        }

        private suspend fun install(
            identity: ConfigIdentity,
            buildTransport: (AuthFailureListener) -> PayabliTransport,
        ): Result<PayabliSession> =
            lock.withLock {
                val current = installed
                if (current != null && current.state.value != SdkState.ReinitializeRequired) {
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

                val machine = SessionStateMachine()
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
     * `internal` rather than private so its [toString] can be tested: it holds an access token.
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

        /** Holds an access token, so it must never render one. */
        override fun toString(): String = "ConfigIdentity(entryPoint=$entryPoint, environment=$environment)"
    }
}
