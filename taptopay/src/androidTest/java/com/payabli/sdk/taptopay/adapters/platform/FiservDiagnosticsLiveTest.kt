package com.payabli.sdk.taptopay.adapters.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fiserv.commercehub.ttp.provider.FiservTTPCardReader
import com.fiserv.commercehub.ttp.provider.exception.FiservTTPCardReaderException
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.adapters.ReaderArming
import com.payabli.sdk.taptopay.adapters.toArming
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.EntryPointFailures
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 300.seconds

/**
 * Everything the card reader vendor reports when arming fails, printed for a support ticket.
 *
 * The shipped path carries all five fields on the failure and logs only the code, because the other four
 * are free text that no caller branches on. This tier prints them, and drains the vendor's own progress
 * channel, which nothing in the SDK subscribes to.
 *
 * Arms with the same `toVendorConfig` the SDK ships, so the vendor is handed what it would be handed. It
 * calls `initializeSession` directly rather than through the gateway, so the classification the SDK applies
 * to a refusal is not exercised here: what this reports is the vendor's own answer, before that mapping.
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :taptopay:connectedAndroidTest \
 *   -Ppayabli.ttp.entry=<entry> -Ppayabli.ttp.environment=qa \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.payabli.sdk.taptopay.adapters.platform.FiservDiagnosticsLiveTest
 * adb -s <serial> logcat -d -s PayabliVendorDiag
 * ```
 *
 * Passes whether the reader comes up or not. What it produces is the log, not a verdict.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class FiservDiagnosticsLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireHardware() = LiveTapToPay.requireWiredHandset("the reader is only on a wired handset")

    @Test
    fun everythingTheReaderReports() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                Log.i(TAG, "androidTtpSdk=${FiservTTPCardReader.getAndroidTTPSdkVersion()}")
                Log.i(TAG, "kernel=${FiservTTPCardReader.getMagicCubeSdkVersion()}")
                Log.i(
                    TAG,
                    "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT}",
                )

                // Enrols and spends a code where one is owed: `/config` refuses a device that is not active.
                Log.i(TAG, "deviceId=${LiveTapToPay.activatedDeviceId(context)}")
                val enrollment = LiveTapToPay.enrollment(context)
                val assertion = enrollment.assertion() ?: error("this handset has no attested identity yet")
                val credentials =
                    DeviceServiceClient(LiveTapToPay.session(context).transport)
                        .config(LiveRunSettings.entry, assertion, failureMapper = EntryPointFailures)
                        .credentials
                val arming = credentials.toArming()
                // Never the two keys: they are the vendor's live API secrets.
                Log.i(
                    TAG,
                    "config environment=${arming.environment} currency=${arming.currency} " +
                        "merchantId=${arming.merchantId} terminalId=${arming.terminalId} " +
                        "ppid=${arming.ppId} hostPort=${arming.hostPort}",
                )

                // The vendor's own progress stream. Drained here, because a channel nobody reads is a send
                // that suspends.
                val progress = Channel<String>(Channel.UNLIMITED)
                FiservTTPCardReader.setLoggingChannel(progress)
                // Labels only. This stream is the vendor's, it carries credentials it derived rather than
                // ones handed to it, and this log is written to be attached to a support ticket. Both lines
                // the shipped version emits are `Access token: <bearer>` and `AndroidTTPId: <identifier>`,
                // so the label is the whole diagnostic value: it says which stage the workflow reached.
                // A line in any other shape is reported as unrecognised rather than printed, because
                // nothing bounds what a later version puts here.
                launch {
                    for (line in progress) {
                        Log.i(TAG, "vendor: ${line.labelOnly()}")
                    }
                }

                // Two failure paths: the call itself throwing, and the flow answering with a failed Result.
                val thrown =
                    runCatching {
                        val armed: Result<Boolean> =
                            FiservTTPCardReader.initializeSession(context, arming.toVendorConfig()).first()
                        armed.exceptionOrNull()?.let { throw it }
                    }
                report(thrown.exceptionOrNull(), arming)
                progress.close()
            }
        }

    /**
     * Everything the vendor said about a refusal, with the two credentials this run supplied removed.
     *
     * The free-text fields are the whole reason this tier exists: `code` alone does not separate one
     * refusal from another, and the shipped adapter drops the rest because no caller branches on prose.
     *
     * The risk in printing them is an echo of an input, and an echo is verbatim by construction, so
     * matching the two secrets exactly covers it. That is not true of the vendor's own progress stream,
     * which carries values it derived and is handled by label above.
     */
    private fun report(
        failure: Throwable?,
        arming: ReaderArming,
    ) {
        if (failure == null) {
            Log.i(TAG, "the reader came up")
            return
        }
        val secrets = arrayOf(arming.apiKey, arming.secretKey)
        Log.w(TAG, "arming failed: ${failure.javaClass.name}")
        if (failure is FiservTTPCardReaderException) {
            Log.w(TAG, "type=${failure.type?.withoutSecrets(*secrets)}")
            Log.w(TAG, "code=${failure.code}")
            Log.w(TAG, "field=${failure.field?.withoutSecrets(*secrets)}")
            Log.w(TAG, "message=${failure.message?.withoutSecrets(*secrets)}")
            Log.w(TAG, "additionalInfo=${failure.additionalInfo?.withoutSecrets(*secrets)}")
        }
        // The type only. A cause's `toString` is its own free text and it is not this SDK's type.
        Log.w(TAG, "cause=${failure.cause?.javaClass?.name}")
    }

    /** Replaces the credentials this run hands the vendor, wherever they appear in its own output. */
    private fun String.withoutSecrets(vararg secrets: String): String =
        secrets.filter { it.isNotBlank() }.fold(this) { line, secret -> line.replace(secret, "[redacted]") }

    /** The part before the first colon, so a value the vendor derived cannot travel with its label. */
    private fun String.labelOnly(): String {
        val label = substringBefore(':', missingDelimiterValue = "")
        return if (label.isBlank()) "[unrecognised line, $length characters]" else "$label: [withheld]"
    }

    private companion object {
        /** One tag, so a support ticket is one logcat filter. */
        const val TAG = "PayabliVendorDiag"
    }
}
