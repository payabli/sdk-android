package com.payabli.example.app.sdk

import java.util.UUID

/**
 * The one place this app names a payment. The reference is never sent: the SDK holds the payment's key under it,
 * so a retry naming the same reference is that payment again, and a new reference is a new payment.
 */
internal fun newPaymentReference(): String = UUID.randomUUID().toString()
