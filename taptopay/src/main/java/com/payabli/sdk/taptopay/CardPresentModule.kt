package com.payabli.sdk.taptopay

import androidx.annotation.RestrictTo

/**
 * Marks this artifact as linked, and does nothing else.
 *
 * `:core` reports a device type only where a device record can exist, and whether one can is decided by
 * whether this artifact is on the classpath. It cannot ask that of a dependency it does not have, so it asks
 * for this class by name.
 *
 * A marker rather than an existing class because every other public type here is one an integrator uses:
 * naming one of those would make an unrelated refactor silently change what the SDK reports about the device.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object CardPresentModule
