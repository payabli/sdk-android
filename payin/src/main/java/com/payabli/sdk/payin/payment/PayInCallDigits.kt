package com.payabli.sdk.payin.payment

import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInStoreRequest

/**
 * Gives one call a copy of a request's digits and overwrites the copy however the call ends. The request stays as
 * the host built it, so the same request can be sent again, a declared retry included.
 */
internal object PayInCallDigits {
    /**
     * Runs [block] over a copy of [request], closing the copy's buffers before returning.
     *
     * @param request the payment the host built, left intact.
     * @param block what sends the copy.
     * @return what [block] returned.
     */
    suspend fun <T> useCopy(
        request: PayInRequest,
        block: suspend (PayInRequest) -> T,
    ): T {
        val method = request.paymentMethod.copyForCall()
        try {
            return block(PayInRequest(method, request.options))
        } finally {
            method.closeDigits()
        }
    }

    /**
     * Runs [block] over a copy of [request], closing the copy's buffers before returning.
     *
     * @param request the method to store, left intact.
     * @param block what sends the copy.
     * @return what [block] returned.
     */
    suspend fun <T> useCopy(
        request: PayInStoreRequest,
        block: suspend (PayInStoreRequest) -> T,
    ): T {
        val instrument = request.instrument.copyForCall()
        try {
            return block(PayInStoreRequest(instrument, request.options))
        } finally {
            instrument.closeDigits()
        }
    }
}
