package com.payabli.sdk.core

import android.content.Context
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.device.platform.DeviceIdentifierFactory
import com.payabli.sdk.core.device.platform.DeviceProfileFactory
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.info
import com.payabli.sdk.core.logging.platform.applyHostLogLevel
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.TransportAssembly
import com.payabli.sdk.core.network.TransportFactory
import com.payabli.sdk.core.network.impl.AuthFailureListener
import com.payabli.sdk.core.network.impl.AuthenticatedTransport
import com.payabli.sdk.core.network.impl.ClientFacts
import com.payabli.sdk.core.network.impl.RedactedCause
import com.payabli.sdk.core.telemetry.TelemetryBootstraps
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

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
 * and [setLogLevel], and hands the result to a capability. The transport, [renewRefusedToken] and [state] are
 * `@RestrictTo(LIBRARY_GROUP)`, so a capability shipped as its own artifact can reach them and a host app
 * cannot. Nothing reaches the token at any visibility.
 *
 * @property transport the authenticated transport for this session: bearer injected, one 401 recovered, one replay.
 * @property telemetry what a reporting channel is allowed to know about this session, including the id
 *   that identifies this SDK lifetime. Published because the configuration itself is not: a sibling
 *   artifact has no other way to learn which entry point and environment it is reporting for.
 */
public class PayabliSession private constructor(
    private val identity: ConfigIdentity,
    private val machine: SessionStateMachine,
    private val readDeviceId: () -> String?,
    private val renewal: suspend () -> Unit,
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val transport: PayabliTransport,
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val telemetry: TelemetrySessionContext,
) {
    /**
     * This device's identity, the same for every capability and stable for the install, or null while it
     * cannot be read. It is not the activation id a Tap to Pay enrollment hands over.
     */
    public val deviceId: String? get() = readDeviceId()

    /**
     * Whether this is still the installed session, requiring re-initialization included. A capability built
     * from a session that was replaced reads false here and must stop acting for it.
     */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val isLive: Boolean get() = installed === this

    /**
     * Replaces the token a credential-pinned request was last refused with, so the next request sends a new
     * one. Call it between requests, never inside a sequence that depends on one token. Does nothing when no
     * pinned request has been refused since the last renewal, and keeps a token that has rotated since.
     *
     * @throws PayabliException when the session is finished or the token provider fails.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public suspend fun renewRefusedToken() {
        renewal()
    }

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

        /**
         * Emits [level] and everything more severe. [LogLevel.NONE] silences the SDK.
         *
         * A failure the SDK did not classify converts to its unknown failure, as it does at every entry
         * point, and is reported nowhere: no failure event exists for a log-level change.
         *
         * @param level the cutoff to emit at and above.
         */
        public fun setLogLevel(level: LogLevel) {
            try {
                LoggerRegistry.setLogLevel(level)
            } catch (original: Exception) {
                throw unexpectedFailure(original)
            } catch (original: LinkageError) {
                throw unexpectedFailure(original)
            }
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
         * - A **different** configuration while the session is usable throws [PayabliException] with
         *   [PayabliErrorType.INVALID_CONFIGURATION], rather than returning one configured for something else or
         *   replacing one capabilities already hold.
         * - Any configuration while the session is [SdkState.ReinitializeRequired] builds a fresh one. That
         *   is the documented recovery, and it has to work with a newly brokered token.
         *
         * It does not rehydrate.
         *
         * **It sets the diagnostic log level as a side effect**, once, from the host build. [setLogLevel] wins
         * whether it is called before or after this. Records go to the platform log, never to a callback, and
         * every one is redacted before it is written.
         *
         * A failure the SDK did not classify converts to [PayabliException] with [PayabliErrorType.UNKNOWN],
         * the original kept as its redacted cause, so nothing outside the SDK's own failure vocabulary
         * crosses this boundary. A caller that withdraws unwinds as `CancellationException`, because
         * withdrawing is not a failure.
         *
         * @param config the configuration to install; two configurations built separately count as the same one.
         * @param host the host application's bindings, whose context derives the diagnostic log level once.
         * @return the started session, or the one already started under the same configuration.
         */
        public suspend fun initialize(
            config: PayabliConfig,
            host: HostBindings,
        ): PayabliSession {
            val startedAt = System.nanoTime()

            return convertingUnexpectedFailures(startedAt) {
                host.appContext.applicationContext.applyHostLogLevel()

                install(config, host) { onAuthFailure, client ->
                    TransportFactory
                        .authenticated(
                            config,
                            IO_DISPATCHER,
                            client,
                            TransportAssembly(onAuthFailure = onAuthFailure),
                        ).let(::SessionAuth)
                }
            }
        }

        /**
         * Same, against an explicit [baseUrl] and with no host bindings, for `:core`'s own JVM tests, which
         * skip the host-log-level derivation. A failure the SDK did not classify converts here as in
         * [initialize]. `internal` because a member left public in bytecode for testing is an origin
         * override in a shipped artifact.
         *
         * @param baseUrl the origin every request goes to.
         * @param config the configuration to install.
         * @param host the host bindings, or null for none.
         * @param identifierOf stands in for the platform identifier, so a test can give a real device none.
         * @return the installed session.
         */
        @VisibleForTesting
        internal suspend fun initializeAgainst(
            baseUrl: String,
            config: PayabliConfig,
            host: HostBindings? = null,
            identifierOf: (Context) -> String = DeviceIdentifierFactory::of,
        ): PayabliSession {
            val startedAt = System.nanoTime()

            return convertingUnexpectedFailures(startedAt) {
                install(config, host, identifierOf) { onAuthFailure, client ->
                    TransportFactory
                        .authenticatedAgainst(
                            baseUrl,
                            config,
                            IO_DISPATCHER,
                            TransportAssembly(onAuthFailure = onAuthFailure),
                            client,
                        ).let(::SessionAuth)
                }
            }
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
                TelemetryBootstraps.stopInstalled()
                installed?.machine?.finish()
                installed = null
                sink.value = SdkState.Uninitialized
            }
        }

        /**
         * Same, with the transport supplied, so a test can hold the critical section open. A failure the
         * SDK did not classify converts here as in [initialize], which makes a throwing transport the
         * evidence for that catch.
         *
         * @param config the configuration to install.
         * @param buildTransport builds the transport. The session renews no refused token.
         * @return the installed session.
         */
        @VisibleForTesting
        internal suspend fun initializeWith(
            config: PayabliConfig,
            buildTransport: suspend (AuthFailureListener) -> PayabliTransport,
        ): PayabliSession {
            val startedAt = System.nanoTime()

            return convertingUnexpectedFailures(startedAt) {
                install(config, host = null) { onAuthFailure, _ -> SessionAuth(buildTransport(onAuthFailure)) {} }
            }
        }

        private suspend fun install(
            config: PayabliConfig,
            host: HostBindings?,
            identifierOf: (Context) -> String = DeviceIdentifierFactory::of,
            buildAuth: suspend (AuthFailureListener, ClientFacts) -> SessionAuth,
        ): PayabliSession {
            val startedAt = System.nanoTime()

            return lock.withLock {
                val identity = ConfigIdentity(config)
                val current = installed
                // The machine rather than [state], because this is a question about the session install
                // holds. The published value is process-wide, and reading it here would let one stray write
                // replace a healthy session, which is the two-sessions state this type exists to prevent.
                val live = current != null && !current.machine.isFinished

                // A call that changes nothing reports nothing: a start with no outcome after it is a funnel
                // nobody can close, and an Application and an Activity both initializing is the ordinary way
                // to produce one.
                if (live && current!!.identity == identity) {
                    return@withLock current
                }

                if (live) {
                    // Both halves on the live session's own channel. Recorded anywhere else in this call they
                    // would split: a replacement reports its start through the retired session's channel,
                    // carrying that session's id and entry point, and its success through the new one.
                    TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZE_STARTED) {
                        mapOf(TelemetryProperty.STATE.key to reportedState())
                    }
                    val refusal =
                        PayabliGenericException(
                            PayabliErrorType.INVALID_CONFIGURATION,
                            REASON_ALREADY_INITIALIZED,
                        )
                    reportFailure(refusal, startedAt)
                    throw refusal
                }

                val machine = SessionStateMachine(sink)
                // Without host bindings, which is the SDK's own tests, there is no device to read.
                val device = if (host == null) TelemetryDeviceContext.NONE else DeviceProfileFactory.of(host.appContext)
                val readDeviceId: () -> String? =
                    if (host == null) {
                        { null }
                    } else {
                        val context = host.appContext.applicationContext
                        { identifierOf(context).ifBlank { null } }
                    }
                // One reader per fact, so the header, telemetry and the public member report the same device.
                val client = ClientFacts(PayabliSdkVersion.VALUE, device.osVersion, device.modelName, readDeviceId)
                // The listener is handed in rather than wired afterwards so no request can complete against a
                // transport whose failures nothing is listening for.
                val auth = buildAuth(AuthFailureListener { machine.markReinitializeRequired() }, client)
                val session =
                    PayabliSession(
                        identity = identity,
                        machine = machine,
                        readDeviceId = readDeviceId,
                        renewal = auth.renewRefusedToken,
                        transport = auth.transport,
                        // Minted here rather than in the telemetry module, so every capability reporting for
                        // this session quotes the same lifetime even when they are wired independently.
                        telemetry =
                            TelemetrySessionContext(
                                entryPoint = config.entryPoint,
                                environment = config.environment,
                                sessionId = UUID.randomUUID().toString(),
                                device = device,
                            ),
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

                // Inside the lock, so a session is never installed with the previous one's channel still
                // reporting for it. Starting is an enqueue and a launch, which is why it can be here at all.
                // Whatever the module does is absorbed there: this line runs after the session is published
                // and marked ready, so anything escaping it would fail an initialize that had succeeded.
                TelemetryBootstraps.startInstalled(session, host)

                session
            }
        }

        /**
         * The published state as a fixed word, so what is reported comes from a closed set.
         *
         * @return the published state as a fixed word.
         */
        private fun reportedState(): String =
            when (state.value) {
                SdkState.Uninitialized -> "uninitialized"
                SdkState.Ready -> "ready"
                SdkState.ReinitializeRequired -> "reinitialize_required"
            }

        /**
         * Reports a refused initialization. [PayabliException.reason] is carried here and nowhere else in
         * this file: the only refusal this throws is the SDK's own words, and a reason from a server
         * would not be reportable.
         *
         * @param failure the refusal to report, built in this file from this file's constants.
         * @param startedAt when the initialize began, for the duration.
         */
        private fun reportFailure(
            failure: PayabliGenericException,
            startedAt: Long,
        ) {
            TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZE_FAILED) {
                mapOf(
                    TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.REFUSED,
                    TelemetryProperty.CODE.key to failure.type.wireName,
                    TelemetryProperty.ERROR_NUMBER.key to failure.code.toString(),
                    TelemetryProperty.REASON.key to failure.reason,
                    TelemetryProperty.DURATION_MS.key to
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).toString(),
                )
            }
        }

        /**
         * Runs an initialize's body, letting the failures the SDK itself classified through unchanged and
         * converting everything else: no failure outside the SDK's own vocabulary crosses an entry point.
         * A withdrawn caller passes through too, because withdrawing is not a failure and converting it
         * would hide the withdrawal from the caller that is unwinding.
         *
         * @param startedAt when the initialize began, for the duration a converted failure reports.
         * @param attempt the initialize's body: the log-level derivation and the install, or a test's
         *   stand-in for them.
         * @return the session the body installed.
         */
        private suspend fun convertingUnexpectedFailures(
            startedAt: Long,
            attempt: suspend () -> PayabliSession,
        ): PayabliSession =
            try {
                attempt()
            } catch (withdrawn: CancellationException) {
                throw withdrawn
            } catch (failure: PayabliException) {
                throw failure
            } catch (original: Exception) {
                throw converted(original, startedAt)
            } catch (original: LinkageError) {
                throw converted(original, startedAt)
            }

        /**
         * Builds the failure every entry point answers an unclassified original with: the SDK's unknown
         * failure, whose reason is the catalog's own text and whose cause is the original redacted — type
         * and frames kept, message dropped.
         *
         * @param original the failure as it was thrown, before conversion.
         * @return the failure to throw in the original's place.
         */
        private fun unexpectedFailure(original: Throwable): PayabliGenericException =
            PayabliGenericException(
                PayabliErrorType.UNKNOWN,
                PayabliErrorType.UNKNOWN.message,
                cause = RedactedCause(original),
            )

        /**
         * Converts an initialize's unclassified failure, reporting it first as the pair that answers the
         * start: `outcome=failed` and `failure_type` naming the original, which is what tells a converted
         * failure from a refusal the SDK named.
         *
         * @param original the failure as it was thrown, before conversion.
         * @param startedAt when the initialize began, for the duration.
         * @return the converted failure, for the catch to throw.
         */
        private fun converted(
            original: Throwable,
            startedAt: Long,
        ): PayabliGenericException {
            val failure = unexpectedFailure(original)
            reportConverted(failure, original, startedAt)
            return failure
        }

        /**
         * Reports an initialize that failed outside the refusals the SDK names: a start and its failure,
         * so the start is answered, with `outcome=failed` and `failure_type` naming the original.
         *
         * @param failure the converted failure the initialize is about to throw.
         * @param original the failure as it was thrown, whose type the report names.
         * @param startedAt when the initialize began, for the duration.
         */
        private fun reportConverted(
            failure: PayabliGenericException,
            original: Throwable,
            startedAt: Long,
        ) {
            TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZE_STARTED) {
                mapOf(TelemetryProperty.STATE.key to reportedState())
            }
            TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZE_FAILED) {
                mapOf(
                    TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.FAILED,
                    TelemetryProperty.CODE.key to failure.type.wireName,
                    TelemetryProperty.ERROR_NUMBER.key to failure.code.toString(),
                    TelemetryProperty.REASON.key to failure.reason,
                    TelemetryProperty.DURATION_MS.key to
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).toString(),
                    TelemetryProperty.FAILURE_TYPE.key to original.javaClass.simpleName,
                )
            }
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

        override fun equals(other: Any?): Boolean =
            other is ConfigIdentity &&
                entryPoint == other.entryPoint &&
                environment == other.environment

        override fun hashCode(): Int = 31 * entryPoint.hashCode() + environment.hashCode()

        /**
         * Carries no credential and no identifier, matching `PayabliConfig.toString`.
         *
         * The entry point is withheld: it names a specific merchant, and this string reaches exception
         * messages and crash reports. It is the same rule and the same reason as `PayabliConfig`'s.
         *
         * @return the environment, carrying no credential and no identifier.
         */
        override fun toString(): String = "ConfigIdentity(environment=$environment)"
    }
}

/**
 * The transport a session sends through, and how it renews a token a pinned request was refused with.
 *
 * @property transport the authenticated transport.
 * @property renewRefusedToken replaces the token a pinned request was last refused with.
 */
private class SessionAuth(
    val transport: PayabliTransport,
    val renewRefusedToken: suspend () -> Unit,
) {
    constructor(authenticated: AuthenticatedTransport) : this(authenticated, authenticated::renewRefusedToken)
}
