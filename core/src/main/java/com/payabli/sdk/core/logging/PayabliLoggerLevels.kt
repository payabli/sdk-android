package com.payabli.sdk.core.logging

import androidx.annotation.RestrictTo

/*
 * The level ladder, as extension functions rather than interface members: extensions are not
 * virtual, so no PayabliLogger implementation can make a level bypass the redaction pipeline that
 * PayabliLogger.log applies. Runtime values go in `fields`, never interpolated into `message`.
 */

/** Detail useful while diagnosing. Off by default on a device; raise with `setprop log.tag.<TAG>`. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun PayabliLogger.debug(
    vararg fields: LogField,
    message: () -> String,
): Unit = log(LogLevel.DEBUG, fields.asList(), throwable = null, message = message)

/** [debug] with exception context. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun PayabliLogger.debug(
    throwable: Throwable,
    vararg fields: LogField,
    message: () -> String,
): Unit = log(LogLevel.DEBUG, fields.asList(), throwable, message)

/** A normal lifecycle milestone. Always emitted, so it may carry allowlisted fields only. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun PayabliLogger.info(
    vararg fields: LogField,
    message: () -> String,
): Unit = log(LogLevel.INFO, fields.asList(), throwable = null, message = message)

/** [info] with exception context. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun PayabliLogger.info(
    throwable: Throwable,
    vararg fields: LogField,
    message: () -> String,
): Unit = log(LogLevel.INFO, fields.asList(), throwable, message)

/** Recoverable, but worth a maintainer's attention. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun PayabliLogger.warn(
    vararg fields: LogField,
    message: () -> String,
): Unit = log(LogLevel.WARN, fields.asList(), throwable = null, message = message)

/** [warn] with exception context. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun PayabliLogger.warn(
    throwable: Throwable,
    vararg fields: LogField,
    message: () -> String,
): Unit = log(LogLevel.WARN, fields.asList(), throwable, message)

/** An operation failed. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun PayabliLogger.error(
    vararg fields: LogField,
    message: () -> String,
): Unit = log(LogLevel.ERROR, fields.asList(), throwable = null, message = message)

/** [error] with exception context. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun PayabliLogger.error(
    throwable: Throwable,
    vararg fields: LogField,
    message: () -> String,
): Unit = log(LogLevel.ERROR, fields.asList(), throwable, message)

/** An invariant this SDK guarantees was violated. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun PayabliLogger.fault(
    vararg fields: LogField,
    message: () -> String,
): Unit = log(LogLevel.FAULT, fields.asList(), throwable = null, message = message)

/** [fault] with exception context. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun PayabliLogger.fault(
    throwable: Throwable,
    vararg fields: LogField,
    message: () -> String,
): Unit = log(LogLevel.FAULT, fields.asList(), throwable, message)
