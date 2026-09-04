package com.payabli.sdk.core.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.ManualDeviceTest
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * How much of the provider deadline a real token round trip spends on a bandwidth-constrained link.
 *
 * The deadline in [DEFAULT_PROVIDER_TIMEOUT_MILLIS] is chosen on the argument that minting a token is one whole
 * network round trip. That argument is about a network, and every other tier tests it without one: the JVM suite
 * parks a provider on a `CompletableDeferred` and asserts on a virtual clock, which proves the mechanism and
 * measures nothing. This tier asks the question the value was chosen to answer, which is whether a real round
 * trip through a slow link fits.
 *
 * ```
 * # 1. A stand-in broker on the development machine. It paces its own writes; that is what makes this slow.
 * python3 scripts/slow_broker.py
 *
 * # 2. An emulator. Any emulator: no network profile is required, for the reason below.
 * emulator -avd <avd>
 *
 * # 3. This tier only.
 * ANDROID_SERIAL=<serial> ./gradlew :core:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.annotation=com.payabli.sdk.core.ManualDeviceTest
 * ```
 *
 * **The slowness is the broker's, not the emulator's, and that is a measurement rather than a preference.**
 * `-netspeed` and `-netdelay` are accepted and reported back correctly by `adb emu network status` and then not
 * applied. Measured on emulator 36.6.11 launched with `-netspeed edge -netdelay edge`: a 4 MiB body arrived in
 * 85 ms, about 394 Mbps, against a claimed 473.6 kbps, and ping to `10.0.2.2` held 0.16 ms against a claimed
 * 80 ms floor. Neither interface is shaped, and forcing the route from wlan0 to eth0 made it faster. Pacing the
 * server instead is enforced by a process that cannot silently ignore it, and it is reproducible, which
 * emulator shaping was not. Setting a profile does no harm and buys nothing.
 *
 * **`lte` would be the wrong profile even if shaping worked**, and it is the intuitive choice. The emulator documents `-netspeed lte` as up 58,000 and down 173,000 kbps with
 * `-netdelay lte` at 0, so it is faster than most real links and adds no latency. `edge` (473.6 kbps) is the
 * figure this borrows for its default rate.
 *
 * **Emulator only.** `10.0.2.2` is the emulator's alias for the machine running it, so this skips on a wired
 * phone. That is also why the broker runs on that machine rather than in the test process: `127.0.0.1` inside
 * the device is the device's own loopback, so an in-process `LoopbackServer` would never leave the device and
 * could not be a round trip to a separate host at all.
 *
 * **Manual for a reason that expires, and it is a provisioning gap rather than a property of emulators.**
 * Nothing here needs hardware, and the nightly's emulator could reach a broker started beside it, so this does
 * not qualify for [ManualDeviceTest] on the terms that annotation sets: it passes on an emulator rather than
 * failing there. It is parked here because the nightly starts no broker, and a test that skips unattended every
 * night is the standing-skip problem that annotation exists to avoid. Moving it is a workflow change that also
 * has to decide what an unprovisioned run means once nobody is reading the message, so it is queued rather than
 * bolted on here. Until then this is the one real-network check that no automated run exercises.
 *
 * **Skips when unprovisioned, fails when provisioned and meaningless.** No reachable broker means the
 * environment cannot answer, so it skips with what to start. A reachable broker that turns out to be fast is
 * the dangerous case, because that is the one that would otherwise pass and be read as coverage of a slow
 * round trip, so it fails.
 *
 * **What this tier established that no other one can.** Beyond the headline figure, driving a real slow socket
 * showed the deadline's cooperation requirement is not satisfied merely by moving the work to
 * `Dispatchers.IO`. At 50 kbps the awaiting readers were released on time, at the deadline, while the
 * provider's own thread ran to 41,949 ms: a blocking socket read is not a suspension point, so cancellation
 * never reached it. The deadline bounds how long a reader waits, not how long the host's thread runs.
 *
 * **`runBlocking` rather than `runTest`, and the reason generalises.** `runTest` would create the deadline on
 * the test scheduler, where its thirty seconds elapse the instant the coroutine suspends on the network. The
 * deadline would fire before a packet moved and the test would time out on every profile including a fast one.
 * What has to stay on a real clock is the coroutine that installs the deadline, not only the code it wraps.
 * The wall-clock bound is JUnit's, since `runBlocking` takes none.
 *
 * **Reads the constant rather than pinning it, which is the opposite of what `PayabliAuthTest` does.** The
 * question here is whether a round trip fits inside whatever ships, so this must move when the value moves.
 * The value itself is pinned once, on the clock and in the declaration, in the JVM suite.
 *
 * Tunable, with defaults that put the round trip a few seconds inside the deadline:
 * `-Pandroid.testInstrumentationRunnerArguments.payabli.brokerUrl=http://10.0.2.2:8080/token`,
 * `...payabli.brokerBytes=262144`, `...payabli.brokerKbps=473`, `...payabli.throttleFloorMillis=2000`.
 */
@RunWith(AndroidJUnit4::class)
class ThrottledBrokerManualTest {
    private val sink = RecordingLogSink()

    private fun argument(
        name: String,
        fallback: String,
    ): String = InstrumentationRegistry.getArguments().getString(name) ?: fallback

    private val brokerUrl: String get() = argument(BROKER_URL_ARG, DEFAULT_BROKER_URL)
    private val brokerBytes: Int get() = argument(BROKER_BYTES_ARG, DEFAULT_BROKER_BYTES.toString()).toInt()
    private val brokerKbps: Int get() = argument(BROKER_KBPS_ARG, DEFAULT_BROKER_KBPS.toString()).toInt()
    private val throttleFloorMillis: Long
        get() = argument(THROTTLE_FLOOR_ARG, DEFAULT_THROTTLE_FLOOR_MILLIS.toString()).toLong()

    /**
     * Fetches a token the way a host's provider would, reading the body to EOF.
     *
     * On a constrained link the transfer is the cost, so a caller that took the first line
     * and dropped the connection would pay the latency and none of the bandwidth, and the measurement would
     * describe a round trip nobody makes.
     */
    private fun fetchToken(): String {
        val connection =
            URL("$brokerUrl?bytes=$brokerBytes&kbps=$brokerKbps").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = PROBE_TIMEOUT_MILLIS
            connection.readTimeout = SOCKET_READ_TIMEOUT_MILLIS
            val body = connection.inputStream.readBytes().decodeToString()
            body.substringBefore('\n').trim().also {
                if (it.isEmpty()) throw IOException("the stand-in broker returned no token line")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Whether anything answers at the configured address, whatever it answers.
     *
     * **Any status counts as reachable, including an error status.** This decides between skipping and
     * running, so it must only ever answer "nothing is there", never "something is there but unhappy".
     * `HttpURLConnection.getInputStream` throws `IOException` on a 400 while `getResponseCode` returns
     * 400 without throwing, so reading the stream and requiring 200 reports a reachable endpoint
     * answering an error as absent, and skips with a message telling the operator to start a broker that is
     * already running. A bad response is the real fetch's business, where it fails and names itself.
     *
     * Unpaced and tiny, because this asks whether anything is listening and must not itself be slow.
     */
    private fun brokerIsReachable(): Boolean =
        runCatching {
            val connection = URL("$brokerUrl?bytes=$MIN_BROKER_BYTES&kbps=0").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = PROBE_TIMEOUT_MILLIS
                connection.readTimeout = PROBE_TIMEOUT_MILLIS
                // Not inputStream: that throws on an error status, which is exactly the case this must not
                // mistake for an absent server.
                connection.responseCode > 0
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)

    /**
     * The holder, with a provider that records how long it took.
     *
     * Timed inside the provider rather than around the refresh, because the deadline wraps the provider call and
     * nothing else. Measuring the whole refresh would fold in the claim and commit work under the mutex and
     * report a number the deadline does not govern.
     */
    private fun auth(providerMillis: AtomicLong) =
        PayabliAuth(
            PayabliConfig(
                entryPoint = "entry",
                environment = PayabliEnvironment.SANDBOX,
                // Hopped to IO because the alternative wedges the caller's dispatcher, and the deadline could
                // then not fire at all. The hop is not full cooperation and this tier shows why: measured at
                // 50 kbps, the readers got their answer at the 30s deadline while this thread kept reading to
                // completion at 41,949ms, because a blocking socket read is not a suspension point and
                // cancellation cannot reach it. The deadline bounds what a reader waits, not what the host's
                // thread does.
                tokenProvider = {
                    withContext(Dispatchers.IO) {
                        val startedAt = System.nanoTime()
                        try {
                            fetchToken()
                        } finally {
                            providerMillis.set((System.nanoTime() - startedAt) / NANOS_PER_MILLI)
                        }
                    }
                },
            ),
            DefaultSdkLogger(LogCategory.AUTH, sink),
        )

    /**
     * Does a real broker round trip over a slow link finish inside the deadline?
     *
     * One question, and the measured duration is in every failure message, because the number is the result
     * whether it passes or fails: a passing run that spent most of the budget is a finding too.
     *
     * A rate low enough can legitimately fail this. At 50 kbps the default payload needs about forty seconds and
     * cannot mint a token inside the deadline at all, which is a real answer about a slow link rather than a
     * defect, and the kind of result this tier exists to produce. Measured for reference: 256 KiB at 473 kbps,
     * the emulator's EDGE figure, takes about 4.5 seconds, so an EDGE-class broker has ample margin.
     */
    @ManualDeviceTest
    @Test(timeout = WALL_CLOCK_BOUND_MILLIS)
    fun aBrokerRoundTripOverAThrottledLinkFitsInsideTheDeadline() =
        runBlocking {
            Assume.assumeTrue(
                "no stand-in broker answered at $brokerUrl. Start it with `python3 scripts/slow_broker.py` and " +
                    "run this on an emulator, where 10.0.2.2 is the host's loopback interface.",
                brokerIsReachable(),
            )

            val providerMillis = AtomicLong(-1)
            val outcome = runCatching { auth(providerMillis).invalidateAndRefresh("initial-token") }
            val elapsedMillis = providerMillis.get()

            // Reported here rather than left to propagate. The deadline firing is this test's most interesting
            // outcome and a raw exception would carry the reason without the measurement, which is the half that
            // says how far past the budget the link actually was.
            outcome.exceptionOrNull()?.let { failure ->
                fail(
                    "the round trip did not complete. The provider ran ${elapsedMillis}ms against a " +
                        "${DEFAULT_PROVIDER_TIMEOUT_MILLIS}ms deadline, at payabli.brokerBytes=$brokerBytes over " +
                        "payabli.brokerKbps=$brokerKbps. Reason: ${failure.message}",
                )
            }

            // The only assertion left, because success already carries the other one: the refresh returning at
            // all means the provider finished inside the deadline, so a separate check on that would restate it.
            // What success does not establish is that anything was slow, and an unpaced broker is the case that
            // would otherwise pass and be counted as coverage of a slow round trip.
            assertTrue(
                "the round trip took ${elapsedMillis}ms, under the ${throttleFloorMillis}ms floor, so nothing " +
                    "was slow and this run measured nothing. The broker paces its own writes: check it is " +
                    "running without --kbps 0, and that payabli.brokerBytes ($brokerBytes) over " +
                    "payabli.brokerKbps ($brokerKbps) is expected to exceed the floor.",
                elapsedMillis >= throttleFloorMillis,
            )
        }

    private companion object {
        const val BROKER_URL_ARG = "payabli.brokerUrl"
        const val BROKER_BYTES_ARG = "payabli.brokerBytes"
        const val BROKER_KBPS_ARG = "payabli.brokerKbps"
        const val THROTTLE_FLOOR_ARG = "payabli.throttleFloorMillis"

        // 10.0.2.2 is the emulator's alias for the host's loopback interface. Cleartext for this address is
        // permitted by the androidTest network security config, scoped to it and to the loopback harness.
        const val DEFAULT_BROKER_URL = "http://10.0.2.2:8080/token"

        // 256 KiB at 473 kbps is about four and a half seconds: comfortably above the floor and comfortably
        // inside the deadline, so a passing run has margin to report rather than a verdict on a knife edge.
        const val DEFAULT_BROKER_BYTES = 256 * 1024

        // EDGE/EGPRS down, the emulator's own figure for the profile it declines to enforce.
        const val DEFAULT_BROKER_KBPS = 473

        // Above anything an unpaced transfer of the default payload takes, below what a paced one takes.
        const val DEFAULT_THROTTLE_FLOOR_MILLIS = 2_000L

        const val PROBE_TIMEOUT_MILLIS = 3_000

        // Longer than the provider deadline, so the deadline is what ends a slow refresh rather than the socket.
        const val SOCKET_READ_TIMEOUT_MILLIS = 60_000

        // JUnit's own bound, since runBlocking takes none. Generous: a refresh can legitimately spend the whole
        // deadline before failing, and the reachability probe runs first.
        const val WALL_CLOCK_BOUND_MILLIS = 120_000L

        const val NANOS_PER_MILLI = 1_000_000

        // The broker refuses a body it cannot fit the token line into, so the probe asks for the smallest
        // size that is always valid rather than an arbitrary one that a future token length could invalidate.
        const val MIN_BROKER_BYTES = 1_024
    }
}
