package com.payabli.sdk.telemetry

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.annotation.RestrictTo
import androidx.lifecycle.ProcessLifecycleOwner
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.info
import com.payabli.sdk.core.telemetry.TelemetryBootstrap
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The whole of this artifact's surface, and no host ever names it. Linking the module is the integration.
 *
 * **Found by name from `:core`, so it needs its no-argument constructor and its type.**
 * `keepRules/rules.keep` here holds both through an integrator's R8, and renaming or moving this class is a
 * change to that file and to the name `:core` looks for.
 *
 * `PayabliConfig.telemetryEnabled` off installs nothing: no queue, no timer, no request.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class TelemetryModule : TelemetryBootstrap {
    override fun start(
        session: PayabliSession,
        host: HostBindings?,
    ) {
        val logger = LoggerRegistry.of(LogCategory.TELEMETRY)
        val context = session.telemetry

        if (!context.telemetryEnabled) {
            stop()
            logger.info(LogField.safe("event", "telemetry_disabled")) { "telemetry is off for this session" }
            return
        }

        val client =
            TelemetryClient(
                context = context,
                queue = TelemetryQueue(QUEUE_CAPACITY),
                uploader = TelemetryUploader(session.transport, context, logger),
                scope = CoroutineScope(SupervisorJob() + IO_DISPATCHER),
                flushInterval = FLUSH_INTERVAL,
                batchSize = BATCH_SIZE,
                maxEventsPerRequest = MAX_EVENTS_PER_REQUEST,
                logger = logger,
                now = System::currentTimeMillis,
            )

        InstalledTelemetry.install(client, watchBackground(host, client, logger))
        TelemetryRecorders.install(client)
        client.start()
        client.record(TelemetryEvents.SDK_INITIALIZED, mapOf(TelemetryProperty.STATE.key to READY))
    }

    override fun stop() {
        TelemetryRecorders.clear()
        InstalledTelemetry.stop()
    }

    /** Registers the background flush, where there is a host application to register it on. */
    private fun watchBackground(
        host: HostBindings?,
        client: TelemetryClient,
        logger: SdkLogger,
    ): AppBackgroundWatcher? {
        if (host?.appContext?.applicationContext !is Application) {
            // Every ordinary app reaches this through an Application, so this is the SDK started without host
            // bindings. The timer still flushes; what is lost is the last moment before the process may be
            // killed.
            logger.info(LogField.safe("event", "telemetry_background_flush_unavailable")) {
                "no application context; flushing on the timer only"
            }
            return null
        }

        val watcher = AppBackgroundWatcher { client.flushAsync() }
        onMainThread { ProcessLifecycleOwner.get().lifecycle.addObserver(watcher) }
        return watcher
    }
}

/**
 * Runs [block] on the main looper, always by posting. `ProcessLifecycleOwner` requires that thread.
 *
 * Posting even when already there, so the registrations keep their order: run inline, a stop on the main
 * thread removes the observer before a background start's posted add has run, and that add then installs a
 * watcher nothing removes.
 */
private fun onMainThread(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}

/**
 * The one running channel, and what it takes to unwind it.
 *
 * Process-wide for the reason `PayabliSession` is: one session serves every capability, so a second channel
 * would mean two queues reporting the same run, each with half its events.
 */
private object InstalledTelemetry {
    private var client: TelemetryClient? = null
    private var watching: AppBackgroundWatcher? = null

    @Synchronized
    fun install(
        client: TelemetryClient,
        watching: AppBackgroundWatcher?,
    ) {
        stopLocked()
        this.client = client
        this.watching = watching
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        // Captured first: the field is cleared before the post runs.
        watching?.let { watcher -> onMainThread { ProcessLifecycleOwner.get().lifecycle.removeObserver(watcher) } }
        watching = null
        client?.stop()
        client = null
    }
}

/** The state reported alongside `sdk.initialized`, matching the session state a host can observe. */
private const val READY = "ready"

/** How much is held when nothing can be sent. Roughly a busy minute, and bounded so an outage cannot grow. */
private const val QUEUE_CAPACITY = 500

/** Events that trigger a flush by filling a batch. */
private const val BATCH_SIZE = 20

/** Most events one request carries. */
private const val MAX_EVENTS_PER_REQUEST = 100

/** How often a quiet app ships what it has. */
private val FLUSH_INTERVAL: Duration = 30.seconds

/**
 * The one dispatcher pick in this module, at the layer the session reaches, as `PayabliPayInPaymentFlow` is
 * for `:payin` and `PayabliSession` is for `:core`.
 *
 * Everything below takes it as a required parameter, so no layer can quietly run somewhere else.
 */
private val IO_DISPATCHER: CoroutineDispatcher = Dispatchers.IO
