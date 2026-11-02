package com.payabli.sdk.core.model

/**
 * A failure with no richer wire shape behind it: the 401, 403 and 410 mappings, an unmapped non-2xx
 * status, a network failure, a decode failure, or a bad configuration.
 *
 * The underlying failure is carried as [Throwable.cause], so there is no second slot for it.
 */
public class PayabliGenericException internal constructor(
    type: PayabliErrorType,
    reason: String,
    detail: String?,
    cause: Throwable?,
    httpStatus: Int?,
    rawCode: String?,
) : PayabliException(type, reason, detail, cause, httpStatus, rawCode) {
    /**
     * A failure no response was read for.
     *
     * @param type the catalog entry.
     * @param reason short human-readable summary.
     * @param detail longer explanation, when there is one.
     * @param cause the underlying failure, when there is one.
     */
    public constructor(
        type: PayabliErrorType,
        reason: String,
        detail: String? = null,
        cause: Throwable? = null,
    ) : this(type, reason, detail, cause, null, null)
}
