package com.payabli.sdk.testutils.auth

import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import java.util.concurrent.atomic.AtomicInteger

public const val TEST_TOKEN: String = "test-token"

/**
 * A provider answering [held] on its first call and running [refresh] for every call after.
 *
 * The holder mints its first token rather than being handed one, so a test whose subject is a rejection
 * needs the first answer to be the token the service is going to reject. A provider that answered the
 * refreshed value straight away would put that value on the first request, leaving no rejection to
 * recover from, and a provider answering one value forever would trip the rule against returning the
 * token that was just refused.
 *
 * [refresh] is not called for the first token, so a counter inside it counts refreshes and not the mint.
 */
public fun mintingThen(
    held: String,
    refresh: PayabliTokenProvider,
): PayabliTokenProvider {
    val calls = AtomicInteger()
    return PayabliTokenProvider { if (calls.getAndIncrement() == 0) held else refresh.freshToken() }
}

/**
 * An auth holder for transport tests, which need `create` to have a token source without being about auth.
 *
 * The logger is injected rather than defaulted: the default reaches `android.util.Log`, which throws
 * "not mocked" on the JVM. Pass one when the test asserts on what was logged.
 *
 * **The first call answers [TEST_TOKEN] whatever [tokenProvider] is, and every call after runs
 * [tokenProvider].** The holder mints its first token rather than being handed one, so a test whose
 * subject is a refresh needs the first answer to be the token the service is going to reject. Without
 * that split, a provider answering a fresh token would put that token on the first request and there
 * would be no rejection to recover from.
 *
 * A test that counts provider calls counts the mint, because obtaining the first token is a call.
 */
public fun testAuth(
    tokenProvider: PayabliTokenProvider = PayabliTokenProvider { TEST_TOKEN },
    logger: SdkLogger = RecordingSdkLogger(),
): PayabliAuth =
    PayabliAuth(
        PayabliConfig(
            entryPoint = "entry",
            environment = PayabliEnvironment.SANDBOX,
            tokenProvider = mintingThen(TEST_TOKEN, tokenProvider),
        ),
        logger,
    )
