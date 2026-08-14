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
import com.payabli.sdk.core.network.TransportAssembly
import com.payabli.sdk.core.network.TransportFactory
import com.payabli.sdk.core.network.impl.AuthFailureListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val REASON_ALREADY_INITIALIZED = "a session is already initialized with a different configuration"

/**
 * One session per app process: one token holder, one transport, one state.
 *
 * Per **process**, not per app. The installed session and the lock guarding it are companion state, which
 * Android gives every process its own copy of, so an app that runs a service or an activity under
 * `android:process` gets a session in each, and a refresh in one is invisible to the other. Nothing here
 * coordinates across that boundary.
 *
 * One session serves every capability, and this type is what makes that structural rather than requested:
 * two auth stacks are two token holders, and a refresh de-duplicated inside one is invisible to the other.
 *
 * **The type is host-facing, its members mostly are not.** An integrator names this type, calls [initialize]
 * and [setLogLevel], and hands the result to a capability. The transport and [state] are
 * `@RestrictTo(LIBRARY_GROUP)`, so a capability shipped as its own artifact can reach them and a host app
 * cannot. Nothing reaches the token at any visibility.
 */
public class PayabliSession private constructor(
    private val identity: ConfigIdentity,
    private val machine: SessionStateMachine,
    /** The authenticated transport for this session: bearer injected, one 401 recovered, one replay. */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val transport: PayabliTransport,
) {
    public companion object {
        /** Serializes [initialize] so two callers racing at startup install one session rather than two. */
        private val lock = Mutex()

        @Volatile
        private var installed: PayabliSession? = null

        /** Written only by the [SessionStateMachine] of whichever session is current. See [state]. */
        private val sink = MutableStateFlow<SdkState>(SdkState.Uninitialized)

        private val logger: SdkLogger get() = LoggerRegistry.of(LogCategory.CORE)

        /**
         * Chosen here and handed down, and `IO` because sockets, files and Keystore calls all block.
         *
         * `internal` because the device-trust accessor reads it. Still the only place one is picked.
         */
        internal val IO_DISPATCHER: CoroutineDispatcher = Dispatchers.IO

        /** What the SDK can do right now. One per process, readable before a session exists. */
        @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public val state: StateFlow<SdkState> = sink.asStateFlow()

        /** Emits [level] and everything more severe. [LogLevel.NONE] silences the SDK. */
        public fun setLogLevel(level: LogLevel) {
            LoggerRegistry.setLogLevel(level)
        }

        /**
         * Starts the SDK, or returns the session already started.
         *
         * Idempotent: twice with the same configuration returns the same instance, so an app initializing in
         * `Application.onCreate` and again in an Activity gets one session. Two configurations built separately
         * count as the same one, which is what makes that work.
         *
         * The session keeps the token provider it was created with. Calling this again with a different one
         * does not swap it.
         *
         * Two cases are not idempotent:
         *
         * - A **different** configuration while the session is usable fails, rather than returning one
         *   configured for something else or replacing one capabilities already hold.
         * - Any configuration while the session is [SdkState.ReinitializeRequired] builds a fresh one. That
         *   is the documented recovery, and it has to work with a newly brokered token.
         *
         * It does not rehydrate.
         *
         * **It sets the diagnostic log level as a side effect**, once, from the host build. [setLogLevel] wins
         * whether it is called before or after this. Records go to the platform log, never to a callback, and
         * every one is redacted before it is written.
         */
        public suspend fun initialize(
            config: PayabliConfig,
            host: HostBindings,
        ): Result<PayabliSession> {
            host.appContext.applicationContext.applyHostLogLevel()

            return install(ConfigIdentity(config)) { onAuthFailure ->
                TransportFactory.authenticated(config, IO_DISPATCHER, TransportAssembly(onAuthFailure = onAuthFailure))
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
                TransportFactory.authenticatedAgainst(
                    baseUrl,
                    config,
                    IO_DISPATCHER,
                    TransportAssembly(onAuthFailure = onAuthFailure),
                )
            }

        /**
         * Drops the installed session and puts [state] back, so one test cannot decide the outcome of the
         * next.
         *
         * Test-only. The state is restored twice, once outside the lock and once under it, and the clearing
         * itself happens under it. Both halves are load-bearing and the order is not arbitrary: `:core`'s own
         * session tests fail if either moves, which is the place to read before changing this.
         */
        @VisibleForTesting
        internal suspend fun reset() {
            sink.value = SdkState.Uninitialized

            lock.withLock {
                installed?.machine?.finish()
                installed = null
                sink.value = SdkState.Uninitialized
            }
        }

        /**
         * Same, with the transport supplied, so a test can hold the critical section open.
         *
         * Without it the mutex cannot be shown to do anything: the section is a few microseconds of
         * object construction, so two racing callers almost never collide and a concurrency test passes
         * just as happily with the lock removed.
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
     * `PayabliConfig` is never compared and has no `equals`: a data class would generate a `toString` and undo
     * the redaction that keeps an access token out of a log. This type holds the parts that decide, and
     * compares those by value.
     *
     * A token is a credential rather than an identity, so it is not one of them. Two rules for whoever changes
     * this list: nothing secret joins it, and a callback is compared by whether one was supplied rather than by
     * which object it is, since an inline one is new on every call and would stop [initialize] being idempotent
     * for the ordinary way of writing it.
     *
     * `internal` rather than private so its [toString] can be tested: it holds an entry point, and
     * `PayabliConfig` withholds that for reasons that do not stop applying because the field was copied into
     * another type.
     */
    internal class ConfigIdentity(
        config: PayabliConfig,
    ) {
        private val entryPoint = config.entryPoint
        private val environment = config.environment
        private val telemetryEnabled = config.telemetryEnabled
        private val hasTokenProvider = config.tokenProvider != null

        override fun equals(other: Any?): Boolean =
            other is ConfigIdentity &&
                entryPoint == other.entryPoint &&
                environment == other.environment &&
                telemetryEnabled == other.telemetryEnabled &&
                hasTokenProvider == other.hasTokenProvider

        override fun hashCode(): Int {
            var result = entryPoint.hashCode()
            result = 31 * result + environment.hashCode()
            result = 31 * result + telemetryEnabled.hashCode()
            result = 31 * result + hasTokenProvider.hashCode()
            return result
        }

        /**
         * Carries no credential and no identifier, matching `PayabliConfig.toString`.
         *
         * The entry point is withheld: it names a specific merchant, and this string reaches exception
         * messages and crash reports. It is the same rule and the same reason as `PayabliConfig`'s.
         */
        override fun toString(): String =
            "ConfigIdentity(environment=$environment, telemetryEnabled=$telemetryEnabled, " +
                "tokenProvider=${if (hasTokenProvider) "present" else "absent"})"
    }
}
