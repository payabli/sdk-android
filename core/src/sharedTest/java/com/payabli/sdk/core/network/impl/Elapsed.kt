package com.payabli.sdk.core.network.impl

import java.util.concurrent.TimeUnit

/**
 * Elapsed milliseconds since a [System.nanoTime] reading, for a test whose subject is a duration.
 *
 * `System.currentTimeMillis` reads the wall clock, which `android.os.SystemClock` documents as settable "by
 * the user or the phone network", so "the time may jump backwards or forwards unpredictably" and "interval or
 * elapsed time measurements should use a different clock". A correction landing inside a measured window
 * inflates the reading or turns it negative, so a timing assertion built on it can fail against a working
 * deadline or pass against a broken one. An emulator taking its time from the host is that correction, and it
 * is where these measurements run.
 *
 * `nanoTime` is the same document's monotonic clock, "suitable for interval timing when the interval does not
 * span device sleep". That caveat cannot apply here: every interval measured with this is under a few seconds
 * and runs inside an instrumentation that is holding the CPU. `elapsedRealtimeNanos` would carry no caveat at
 * all and is the recommended general-purpose choice, but it lives on `android.os.SystemClock`, and this source
 * set compiles into the JVM tests as well, where that class does not exist.
 *
 * The conversion matches `PayabliService.elapsedMillis`, so a test measures a duration the way the code under
 * test does.
 */
internal fun elapsedMillisSince(startedAtNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
