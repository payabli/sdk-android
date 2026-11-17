package com.payabli.sdk.core

/**
 * Marks a member the release AAR does not carry. The debug build keeps it for this module's tests, and the
 * `payabli.release-strip` build convention removes it, with the lambdas and coroutine classes it encloses, from
 * the release classes.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
internal annotation class StrippedFromRelease
