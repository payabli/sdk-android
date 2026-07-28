package com.payabli.sdk.core.model

/**
 * A failure with no richer wire shape behind it: the 401, 403 and 410 mappings, an unmapped non-2xx
 * status, a network failure, a decode failure, or a bad configuration.
 *
 * The underlying failure is carried as [Throwable.cause], so there is no second slot for it.
 */
public class PayabliGenericException(
    code: PayabliErrorCode,
    reason: String,
    detail: String? = null,
    cause: Throwable? = null,
) : PayabliException(code, reason, detail, cause)
