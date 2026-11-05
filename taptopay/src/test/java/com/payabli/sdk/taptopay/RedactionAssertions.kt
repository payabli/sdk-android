package com.payabli.sdk.taptopay

/** Every throwable a host can reach from [failure]: its causes and what each one suppressed. */
internal fun reachableFrom(failure: Throwable): List<Throwable> {
    val seen = mutableListOf<Throwable>()
    val pending = ArrayDeque(listOf(failure))
    while (pending.isNotEmpty() && seen.size < 64) {
        val next = pending.removeFirst()
        if (seen.any { it === next }) continue
        seen += next
        next.cause?.let(pending::add)
        pending.addAll(next.suppressed)
    }
    return seen
}

/** Fails when [text] appears in the message of anything a host can reach from [failure]. */
internal fun assertNeverCarries(
    text: String,
    failure: Throwable,
) {
    val carrying = reachableFrom(failure).filter { it.message.orEmpty().contains(text) }
    org.junit.Assert.assertTrue("the original's message reached a host: $carrying", carrying.isEmpty())
}
