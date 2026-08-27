package com.payabli.sdk.core.telemetry

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn

/**
 * How the reporting module is started, by the session rather than by the app that embeds it.
 *
 * Telemetry is the SDK reporting on itself. An integrator who has to remember to switch it on is an
 * integrator whose incidents are the ones with no data, so linking the artifact is the whole integration and
 * there is nothing to call.
 *
 * `:core` cannot name the telemetry module — the dependency runs the other way — so the module implements
 * this and [TelemetryBootstraps] finds it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface TelemetryBootstrap {
    /**
     * Starts reporting for [session], replacing any channel a previous session left running.
     *
     * [host] is absent where the SDK was started without host bindings, which costs the flush that would
     * otherwise happen as the app goes to the background.
     *
     * Called while the session install holds its lock, so it enqueues and returns rather than doing work.
     */
    public fun start(
        session: PayabliSession,
        host: HostBindings?,
    )

    /** Sends what is queued and stops. */
    public fun stop()

    public companion object {
        /**
         * The class the session looks for.
         *
         * Named here rather than in either module so the two cannot drift: the telemetry artifact's own test
         * asserts that this string resolves to a usable implementation, and its keep rule holds that class
         * through an integrator's R8.
         */
        public const val IMPLEMENTATION: String = "com.payabli.sdk.telemetry.TelemetryModule"
    }
}

/**
 * Finds the reporting module, if the app linked it.
 *
 * **One class name, looked up once.** Not a service loader and not a plugin registry: there is exactly one
 * optional module, so a mechanism that could find several would be more machinery for the same result. The
 * day there is a second, this is where that changes.
 *
 * The lookup is by name because the dependency cannot go the other way, and the telemetry artifact carries
 * the keep rule that holds its own class through R8. A module that is absent, stripped or unloadable leaves
 * [installed] null and the SDK reports nothing, which is what an integrator who did not link it asked for.
 */
internal object TelemetryBootstraps {
    private val logger get() = LoggerRegistry.of(LogCategory.TELEMETRY)

    /**
     * The name [installed] looks for.
     *
     * A variable only so a test can point it at a class that is not there. **An app that did not link the
     * module is the common case, not an edge one**, and the branch that serves it — the lookup missing and
     * the SDK carrying on — cannot be reached from a test whose classpath carries the module. Pointing the
     * name elsewhere reaches the same code by the same route.
     */
    @VisibleForTesting
    internal var implementation: String = TelemetryBootstrap.IMPLEMENTATION

    @Volatile
    private var resolved: TelemetryBootstrap? = null

    @Volatile
    private var lookedUp = false

    /**
     * The module, or null.
     *
     * The negative answer is cached too. Absent is the common case, and a class lookup that misses is the
     * expensive one.
     */
    @Synchronized
    fun installed(): TelemetryBootstrap? {
        if (lookedUp) return resolved
        lookedUp = true
        resolved = load()
        return resolved
    }

    /**
     * Starts the module if there is one, and absorbs whatever it does.
     *
     * **Finding the module was already non-fatal; running it was not.** A `RuntimeException` out of `start`,
     * or a `LinkageError` from a symbol resolved only once that method runs, propagated out of the call that
     * installs a session — after the session was published and marked ready. The first `initialize` threw and
     * the next returned the session the first one had installed, which is the opposite of what an optional
     * module is for.
     *
     * A module that fails to start is stopped and forgotten, so nothing is left half-registered and the next
     * `initialize` does not walk into the same failure.
     */
    @Synchronized
    fun startInstalled(
        session: PayabliSession,
        host: HostBindings?,
    ) {
        val module = installed() ?: return
        try {
            module.start(session, host)
        } catch (failure: RuntimeException) {
            unusable(failure)
            discard(module)
        } catch (failure: LinkageError) {
            unusable(failure)
            discard(module)
        }
    }

    /**
     * Stops the module if there is one, and absorbs whatever it does.
     *
     * The same code `start` is, and the caller is tearing a session down: a throw here left the teardown
     * half done, with the module still registered and the session neither retired nor cleared. A module that
     * cannot stop is forgotten, as one that cannot start is.
     */
    @Synchronized
    fun stopInstalled() {
        val module = installed() ?: return
        try {
            module.stop()
        } catch (failure: RuntimeException) {
            unusable(failure)
            forgetUnusable()
        } catch (failure: LinkageError) {
            unusable(failure)
            forgetUnusable()
        }
    }

    /**
     * Unwinds a module that threw while starting.
     *
     * `stop` is the same untrusted code, so it is absorbed the same way. Forgetting it is what keeps the
     * failure to one initialize instead of one per initialize.
     */
    private fun discard(module: TelemetryBootstrap) {
        try {
            module.stop()
        } catch (_: RuntimeException) {
            // Already reported as unusable. A module that cannot stop either has nothing left to say.
        } catch (_: LinkageError) {
        }
        forgetUnusable()
    }

    /** Drops a module that cannot be run, so the next lookup does not find it again. */
    private fun forgetUnusable() {
        resolved = null
        lookedUp = true
    }

    /** Drops the cached answer so a test can install a different module. */
    @Synchronized
    fun forget() {
        resolved = null
        lookedUp = false
    }

    private fun load(): TelemetryBootstrap? =
        try {
            val module = Class.forName(implementation).getDeclaredConstructor().newInstance()
            logger.debug(LogField.safe("event", "telemetry_module_found")) { "reporting is linked" }
            module as TelemetryBootstrap
        } catch (_: ClassNotFoundException) {
            // The ordinary case for an app that did not link the module.
            null
        } catch (failure: ReflectiveOperationException) {
            unusable(failure)
        } catch (failure: ClassCastException) {
            unusable(failure)
        } catch (failure: LinkageError) {
            // A partly linked module, which is what a class kept while something it needs was stripped looks
            // like. An `Error` is caught here and nowhere else: reporting must not be able to stop a payments
            // SDK from starting, and this is the one place where a half-present artifact can do that.
            unusable(failure)
        }

    private fun unusable(failure: Throwable): TelemetryBootstrap? {
        logger.warn(
            LogField.safe("event", "telemetry_module_unusable"),
            LogField.safe("errorKind", failure.javaClass.simpleName),
        ) { "reporting is linked but could not be started" }
        return null
    }
}
