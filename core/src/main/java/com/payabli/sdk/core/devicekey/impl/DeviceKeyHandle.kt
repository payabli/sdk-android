package com.payabli.sdk.core.devicekey.impl

/**
 * The one alias the device key lives under.
 *
 * Fixed here rather than generated per key. Exactly one device key exists at a time, and a replacement is
 * generated at this same alias, so the key it replaces does not survive it and no key is ever left with
 * nothing able to name it. A generated name would have to be recorded somewhere, and a record lost while its
 * key survives strands that key permanently: the private half never leaves the platform key store, so a name
 * is the only handle anything has on it.
 *
 * Versioned, so a later change to how keys are minted moves to a new alias instead of adopting keys created
 * under the old scheme.
 *
 * **This is not the identifier the service records.** That is derived per key from the public half; see
 * [JwkThumbprint]. One value cannot be both, because this one is the same on every install and that one has
 * to distinguish one key from its replacement.
 */
internal object DeviceKeyHandle {
    const val ALIAS: String = "com.payabli.sdk.core.devicekey.v1"
}
