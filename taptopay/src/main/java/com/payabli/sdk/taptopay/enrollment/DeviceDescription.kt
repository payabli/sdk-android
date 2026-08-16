package com.payabli.sdk.taptopay.enrollment

/**
 * What `/register` is told about this handset.
 *
 * Free of platform types so the coordinator stays testable without a device; the values come from
 * [com.payabli.sdk.taptopay.enrollment.platform.DeviceDescriptionFactory].
 *
 * [hardwareId] is the load-bearing one and the rest are descriptive. It is what identifies this device across
 * registrations, so it decides whether a returning install is recognized as itself or registered as a
 * stranger. Two properties matter and both are easy to lose: it must be **stable across an uninstall and
 * reinstall**, or a returning device is registered as a second one and owes a fresh activation code; and it
 * must be **the same on every call**, or that happens on every launch.
 */
internal class DeviceDescription(
    val hardwareId: String,
    /**
     * A human-readable name for the handset, or null.
     *
     * Null on this platform. The field is optional on the wire and purely descriptive, and the only value
     * the platform offers is one the owner typed, which is routinely a person's name. Sending it would put
     * that in a device record and a trace log for no functional gain.
     */
    val deviceName: String?,
    val model: String?,
    val osVersion: String?,
) {
    /** [hardwareId] identifies the device, and the rest narrow it. */
    override fun toString(): String = "DeviceDescription()"
}
