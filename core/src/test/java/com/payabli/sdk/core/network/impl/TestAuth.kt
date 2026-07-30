package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger

internal const val TEST_TOKEN: String = "test-token"

/**
 * An auth holder for transport tests, which need `create` to have a token source without being about auth.
 *
 * The logger is injected rather than defaulted: the default reaches `android.util.Log`, which throws
 * "not mocked" on the JVM.
 *
 * No provider by default, so a 401 in one of these tests is terminal rather than a refresh. Pass one when
 * the refresh path is the subject.
 */
internal fun testAuth(
    accessToken: String = TEST_TOKEN,
    tokenProvider: PayabliTokenProvider? = null,
): PayabliAuth =
    PayabliAuth(
        PayabliConfig(
            accessToken = accessToken,
            entryPoint = "entry",
            environment = PayabliEnvironment.SANDBOX,
            tokenProvider = tokenProvider,
        ),
        DefaultPayabliLogger(LogCategory.AUTH, RecordingLogSink()),
    )
