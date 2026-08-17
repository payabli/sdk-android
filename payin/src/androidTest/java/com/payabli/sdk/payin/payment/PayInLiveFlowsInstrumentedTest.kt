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
 * **`@ManualDeviceTest`, and excluded by name from `payin/build.gradle.kts` when its settings are absent.**
 * Two gates rather than one: the annotation says which tier this belongs to, and the name check means a run
 * without them reports no skip instead of a standing one. The environment, the entry point and the address of a
 * token server arrive as runner arguments, so nothing here is committed. One environment per invocation, because
 * the SDK installs one session per process and refuses a second configuration: QA and sandbox are two runs.
 *
 * **No client credential reaches this device, and none of the three above is one.** A token comes from the app's
 * backend, which holds the client id and secret and exchanges them; `example-server` plays that part for a test
 * run. Minting here instead would put the credential inside the process this SDK is supposed to keep it out of,
 * and a test is the worst place to make that exception, because it is the one run that proves the boundary.
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
     * A token from the app's backend, the way a host app gets one.
     *
     * The exchange that turns a client id and secret into a token happens on that server and not here. The
     * device never holds either, which is the boundary this SDK exists to keep, and a test that minted its own
     * would be the one place the boundary did not hold.
     *
     * `HttpURLConnection` because the SDK takes no third-party HTTP client and a test has no business
     * introducing one. The token is read into a local and never logged.
     */
    private fun mintToken(): String {
        val connection =
            (URL("$tokenBaseUrl$TOKEN_PATH").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
        return try {
            // The route takes an empty object: what it needs to mint is its own configuration, which is the
            // whole point of asking it rather than doing this here.
            connection.outputStream.use { out -> out.write("{}".toByteArray()) }
            // getInputStream throws for 4xx and 5xx; the body, when the server sent one, is on errorStream,
            // which is null when it sent none. Reading it first is what makes a refused exchange report its
            // status instead of an IOException naming the URL.
            val status = connection.responseCode
            val stream =
                if (status < HttpURLConnection.HTTP_BAD_REQUEST) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val token: String? =
                runCatching { Json.parseToJsonElement(body).jsonObject }
                    .getOrNull()
                    ?.let { payload ->
                        TOKEN_FIELDS.firstNotNullOfOrNull { field ->
                            payload[field]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        }
                    }
            requireNotNull(token) { "the token exchange answered HTTP $status without a token" }
        } finally {
            connection.disconnect()
        }
    }

    private fun argument(name: String): String =
        requireNotNull(InstrumentationRegistry.getArguments().getString("liveTest.$name")) {
            "liveTest.$name was not passed, so this class should have been excluded by name"
        }

    private val entryPoint: String get() = argument("entryPoint")

    /** A token server. `example-server` is one; an integrator's backend is the real thing. */
    private val tokenHost: String get() = argument("tokenHost")

    /**
     * The same address as a base URL, whether or not it was given with a scheme.
     *
     * The sample app takes a value carrying one as written, so the same argument reaches both. `http` is the
     * default because cleartext is what the test APK permits to loopback.
     *
     * Refused rather than accepted quietly: another scheme cannot reach this server, and a path would move
     * the route below without looking like it had.
     */
    private val tokenBaseUrl: String
        get() {
            val given = tokenHost.trim().trimEnd('/')
            val scheme = given.substringBefore("://", missingDelimiterValue = "http")
            require(scheme == "http" || scheme == "https") {
                "liveTest.tokenHost must be http or https: $given"
            }
            require(given.substringAfter("://").none { it == '/' || it == '?' || it == '#' }) {
                "liveTest.tokenHost carries a path: $given"
            }
            return if (given.contains("://")) given else "http://$given"
        }

    private val environment: PayabliEnvironment
        get() =
            PayabliEnvironment.entries.firstOrNull { it.name.equals(argument("environment"), ignoreCase = true) }
                ?: error("liveTest.environment named no environment: ${argument("environment")}")

    private companion object {
        private val LOCK = Any()

        @Volatile
        private var installed: PayabliSession? = null

        // The route the sample app's own token client calls, so the test asks for a token the same way the
        // app does. `access-token` serves a cached value; this one mints, which is what a provider owes.
        const val TOKEN_PATH = "/payabli/exchange-token"
        const val TIMEOUT_MILLIS = 20_000
        val TOKEN_FIELDS = listOf("accessToken", "access_token", "token")
        val AMOUNT: BigDecimal = BigDecimal("1.10")

        /** A second test card, so a swapped seed is a different instrument rather than the same one again. */
        const val REPLACEMENT_PAN = "4242424242424242"
    }
}
