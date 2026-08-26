package com.payabli.sdk.testutils.auth

import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.testutils.logging.RecordingSdkLogger

public const val TEST_TOKEN: String = "test-token"

/**
 * An auth holder for transport tests, which need `create` to have a token source without being about auth.
 *
 * The logger is injected rather than defaulted: the default reaches `android.util.Log`, which throws
 * "not mocked" on the JVM. Pass one when the test asserts on what was logged.
 *
 * No provider by default, so a 401 in one of these tests is terminal rather than a refresh. Pass one when
 * the refresh path is the subject.
 */
public fun testAuth(
    accessToken: String = TEST_TOKEN,
    tokenProvider: PayabliTokenProvider? = null,
    logger: SdkLogger = RecordingSdkLogger(),
): PayabliAuth =
    PayabliAuth(
        PayabliConfig(
            accessToken = accessToken,
            entryPoint = "entry",
            environment = PayabliEnvironment.SANDBOX,
            tokenProvider = tokenProvider,
        ),
        logger,
    )
