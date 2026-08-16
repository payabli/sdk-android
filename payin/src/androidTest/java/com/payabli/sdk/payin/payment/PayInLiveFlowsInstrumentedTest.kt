package com.payabli.sdk.payin.payment

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliServerException
import com.payabli.sdk.payin.ManualDeviceTest
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInStoreOptions
import com.payabli.sdk.payin.model.PayInTransactionOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Every flow this module supports, against a real environment.
 *
 * **`@ManualDeviceTest`, and excluded by name from `payin/build.gradle.kts` when the credentials are absent.**
 * Two gates rather than one: the annotation says which tier this belongs to, and the name check means a run
 * without credentials reports no skip instead of a standing one. CI cannot hold the credentials yet, which is
 * the only reason this is not in the ordinary instrumented suite. The environment,
 * the entry point and the client credentials arrive as runner arguments, so nothing here is committed and CI can
 * pass the same four values as secrets. One environment per invocation, because the SDK installs one session per
 * process and refuses a second configuration: QA and sandbox are two runs.
 *
 * The token is minted here rather than by the sample's local server, which keeps the test to one process and no
 * `adb reverse`. The exchange is the same one that server performs: `POST {base}/api/v2/token/serverside` with
 * `clientId` and `clientSecret`, reading `accessToken`.
 *
 * These are real transactions. The amounts are small and the instruments are the sample app's test values, which
 * is what the recorded walks used.
 *
 * **The set is what the public flow reaches, which is not every case the model declares.** Storing and capturing
 * take an entered card or bank account, an authorization takes a card, and an authorization is captured by its
 * identifier. Charging a method already stored is absent because `PayInFormInstrument` builds only `Card` and
 * `BankAccount` from a form, so `PayInPaymentMethod.Stored` is reachable from the internal client alone.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class PayInLiveFlowsInstrumentedTest {
    private lateinit var flow: PayabliPayInPaymentFlow

    @Before
    fun setUp() {
        // One flow per test, as a screen holds one, and the scope is the test's rather than a ViewModel's. The
        // session behind it is the process's: the SDK hands back the one it installed for this entry point and
        // environment whatever token is presented, so a mint per test would spend a live call on a token
        // nothing reads.
        flow =
            PayabliPayInPaymentFlow(
                installedSession(),
                entryPoint,
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
    }

    /** The one session this process installs, built on first use and handed to every test after that. */
    private fun installedSession(): PayabliSession =
        synchronized(LOCK) {
            installed ?: runBlocking { session() }.also { installed = it }
        }

    @Test
    fun storingACardThePayerEntered() =
        runBlocking {
            val stored = flow.storeMethod(card(), PayInStoreOptions()).orFail("storing a card")

            assertTrue("a stored method with no identifier: $stored", stored.storedMethodId.isNotBlank())
        }

    @Test
    fun storingABankAccountThePayerEntered() =
        runBlocking {
            val stored = flow.storeMethod(bankAccount(), PayInStoreOptions()).orFail("storing a bank account")

            assertTrue("a stored method with no identifier: $stored", stored.storedMethodId.isNotBlank())
        }

    @Test
    fun capturingACardThePayerEntered() =
        runBlocking {
            assertApproved(flow.capture(transaction(), card()).orFail("capturing a card").code)
        }

    @Test
    fun capturingTheCardASecondSeedReplacedTheFirstWith() =
        runBlocking {
            // What the draft's seed key decides, against the real service. Read as unchanged, the second seed is
            // ignored and the service takes the card the caller replaced.
            val configuration = PayInFormConfiguration()
            val draft = PayInFormDraft()
            draft.seed(configuration, card())
            draft.seed(configuration, card(number = REPLACEMENT_PAN))

            val submitted = PayInFormValues(draft.method, draft.typed)
            assertEquals("the second seed did not reach the form", REPLACEMENT_PAN, submitted[PayInField.CardNumber])
            assertApproved(flow.capture(transaction(), submitted).orFail("capturing the replacement card").code)
        }

    @Test
    fun capturingABankAccountThePayerEntered() =
        runBlocking {
            assertApproved(flow.capture(transaction(), bankAccount()).orFail("capturing a bank account").code)
        }

    @Test
    fun authorizingACardAndThenCapturingTheAuthorization() =
        runBlocking {
            val authorized = flow.authorize(transaction(), card()).orFail("authorizing a card")

            assertApproved(authorized.code)
            val transId = authorized.transaction?.paymentTransId
            assertNotNull("an approval with no transaction to capture: $authorized", transId)

            assertTrue(flow.consume())
            val captured =
                flow
                    .captureAuthorized(
                        PayInAuthorizedRequest(
                            transId = transId!!,
                            paymentDetails = PayInPaymentDetails(totalAmount = AMOUNT),
                            idempotencyKey = UUID.randomUUID().toString(),
                        ),
                    ).orFail("capturing an authorization")

            assertApproved(captured.code)
        }

    /**
     * The value, or a failure that says what the service answered.
     *
     * `PayabliException.toString` carries the classification and nothing else, which is right for a log line and
     * useless in a test report: a live run that says only `SERVER_ERROR` names neither the status nor the route.
     * The status, the type and the wire code are safe to print. `reason` and `detail` are not, because either
     * can quote what was submitted.
     */
    private fun <T> Result<T>.orFail(what: String): T =
        getOrElse { failure ->
            val server = failure as? PayabliServerException
            val refused = (failure as? PayInException.Refused)?.failure
            val answered =
                listOfNotNull(
                    "code=${(failure as? PayabliException)?.code ?: failure.javaClass.simpleName}",
                    server?.let { "httpStatus=${it.httpStatus}" },
                    server?.rawCode?.let { "serviceCode=$it" },
                    server?.type?.let { "type=$it" },
                    // Which decline, which is the whole content of a refusal and the one thing worth reading
                    // off a live run. A unified code, and published API surface.
                    refused?.code?.let { "declineCode=$it" },
                    refused?.httpStatus?.let { "httpStatus=$it" },
                ).joinToString(" ")
            error("$what: $answered")
        }

    private fun assertApproved(code: String) = assertTrue("the service did not approve it: $code", code.startsWith("A"))

    /** The sample app's own test card, which is what the recorded walks used. */
    private fun card(number: String = "4111111111111111") =
        PayInFormValues(
            PayInMethodType.Card,
            mapOf(
                PayInField.CardholderName to "QA Tester",
                PayInField.CardNumber to number,
                PayInField.CardExpiration to "09/30",
                PayInField.CardSecurityCode to "999",
                PayInField.CardPostalCode to "22039",
                PayInField.FirstName to "QA",
                PayInField.LastName to "Tester",
                PayInField.CustomerNumber to "qa-tester-android",
                PayInField.BillingEmail to "qa@example.com",
            ),
        )

    private fun bankAccount() =
        PayInFormValues(
            PayInMethodType.BankAccount,
            mapOf(
                PayInField.AccountHolder to "QA Tester",
                PayInField.RoutingNumber to "121000248",
                PayInField.AccountNumber to "1234567890",
                PayInField.AccountType to "Checking",
                PayInField.FirstName to "QA",
                PayInField.LastName to "Tester",
                PayInField.CustomerNumber to "qa-tester-android",
                PayInField.BillingEmail to "qa@example.com",
            ),
        )

    /** A key per attempt, so a rerun is a second payment rather than a replay of the first. */
    private fun transaction() =
        PayInTransactionOptions(
            paymentDetails = PayInPaymentDetails(totalAmount = AMOUNT),
            orderDescription = "android live flows",
            idempotencyKey = UUID.randomUUID().toString(),
        )

    private suspend fun session(): PayabliSession =
        PayabliSession
            .initialize(
                PayabliConfig(
                    accessToken = mintToken(),
                    entryPoint = entryPoint,
                    environment = environment,
                    // Minted again on demand, which is what the SDK asks of a provider.
                    tokenProvider = { mintToken() },
                ),
                HostBindings(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext),
            ).getOrThrow()

    /**
     * The credential exchange, over HTTPS on this device.
     *
     * `HttpURLConnection` because the SDK takes no third-party HTTP client and a test has no business
     * introducing one. The token is read into a local and never logged: it is the credential this whole design
     * keeps out of an APK.
     */
    private fun mintToken(): String {
        val connection =
            (URL("${environment.baseUrl}$TOKEN_PATH").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
        return try {
            connection.outputStream.use { out ->
                out.write("""{"clientId":"$clientId","clientSecret":"$clientSecret"}""".toByteArray())
            }
            val body =
                connection.inputStream
                    .bufferedReader()
                    .use(BufferedReader::readText)
            val payload = Json.parseToJsonElement(body).jsonObject
            val token: String? =
                TOKEN_FIELDS.firstNotNullOfOrNull { field ->
                    payload[field]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                }
            requireNotNull(token) {
                "the token exchange answered HTTP ${connection.responseCode} without a token"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun argument(name: String): String =
        requireNotNull(InstrumentationRegistry.getArguments().getString("liveTest.$name")) {
            "liveTest.$name was not passed, so this class should have been excluded by name"
        }

    private val entryPoint: String get() = argument("entryPoint")
    private val clientId: String get() = argument("clientId")
    private val clientSecret: String get() = argument("clientSecret")

    private val environment: PayabliEnvironment
        get() =
            PayabliEnvironment.entries.firstOrNull { it.name.equals(argument("environment"), ignoreCase = true) }
                ?: error("liveTest.environment named no environment: ${argument("environment")}")

    private companion object {
        private val LOCK = Any()

        @Volatile
        private var installed: PayabliSession? = null

        const val TOKEN_PATH = "/api/v2/token/serverside"
        const val TIMEOUT_MILLIS = 20_000
        val TOKEN_FIELDS = listOf("accessToken", "access_token", "token")
        val AMOUNT: BigDecimal = BigDecimal("1.10")

        /** A second test card, so a swapped seed is a different instrument rather than the same one again. */
        const val REPLACEMENT_PAN = "4242424242424242"
    }
}
