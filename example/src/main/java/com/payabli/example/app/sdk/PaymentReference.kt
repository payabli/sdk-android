package com.payabli.example.app.sdk

import java.util.UUID

/**
 * The one place this app mints an idempotency key.
 *
 * A key identifies an attempt, and the service recognizes a repeat by it. Minting in more than one place is
 * how a second site appears that mints on a different occasion than the first, and nothing fails when they
 * disagree: the request goes out, the service treats it as new, and the duplicate is only visible in a
 * dashboard afterwards.
 *
 * **Minted when an attempt begins, and not again while it lasts.** A payment gets one when the screen builds
 * the request; a transaction gets one when it completes, for reversing it. Starting over ends the attempt and
 * the next one asks here again.
 */
internal fun newIdempotencyKey(): String = UUID.randomUUID().toString()
