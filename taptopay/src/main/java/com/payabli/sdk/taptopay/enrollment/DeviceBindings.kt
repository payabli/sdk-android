package com.payabli.sdk.taptopay.enrollment

import kotlinx.serialization.Serializable

/**
 * Every binding this device holds, one per entry point, most recently used first.
 *
 * A device is issued its own handle for each entry point it enrolls against, so one binding cannot stand for
 * another and holding only the newest strands the rest. Ordering is the whole of the retention rule: the
 * front is the binding used last, and anything past [MAX] falls off the back.
 *
 * **A list, not a map.** The order carries meaning, and a map would leave it resting on the iteration order of
 * whatever collection the decoder happened to build. Lookup is by [entry][AttestedDevice.entry] either way,
 * and at this size the scan is not worth a second structure.
 *
 * **No field records when a binding was last used**, and none should be added. A position is enough to say
 * which binding is coldest, and it needs no clock to stay true — where a stored instant would have to be
 * trusted against a device clock the SDK does not control.
 *
 * [bindings] carries no default. The SDK's decoder ignores keys it does not recognize, so a defaulted list
 * would let an older single-binding record decode cleanly to an empty one and report a device holding a
 * binding as holding none.
 *
 * Not a data class, for the reason [AttestedDevice] is not: a generated `toString` would print every entry
 * point held.
 */
@Serializable
internal class DeviceBindings(
    val bindings: List<AttestedDevice>,
) {
    /** The binding for [entry], or null when this device holds none. */
    fun forEntry(entry: String): AttestedDevice? = bindings.firstOrNull { it.entry == entry }

    /** True when [entry]'s binding is already at the front, so promoting it would rewrite nothing. */
    fun isMostRecent(entry: String): Boolean = bindings.firstOrNull()?.entry == entry

    /**
     * [record] at the front, replacing any binding for the same entry point, capped at [MAX].
     *
     * Replace rather than insert: one entry point has one binding, and a second for the same one would make
     * which is read depend on where the scan started.
     */
    fun with(record: AttestedDevice): DeviceBindings =
        DeviceBindings(
            (listOf(record) + bindings.filterNot { it.entry == record.entry }).take(MAX),
        )

    /** Without [entry]'s binding. Every other entry point's is left exactly where it was. */
    fun without(entry: String): DeviceBindings = DeviceBindings(bindings.filterNot { it.entry == entry })

    val isEmpty: Boolean get() = bindings.isEmpty()

    /** The count only. Every binding names an entry point, and an entry point names a merchant. */
    override fun toString(): String = "DeviceBindings(size=${bindings.size})"

    companion object {
        /**
         * How many bindings are kept.
         *
         * **Set by how little should be held, not by what the device could afford to hold.** Every binding
         * names a merchant and a device handle, so a device that has served many of them accumulates a
         * record of which merchants it has served — material worth holding only while it is being used.
         * Storage cost is not the constraint and should not be the argument.
         *
         * Above the deployment that exists, which is one entry point at a time, with room for a device
         * genuinely shared between a few. Past that the coldest is discarded, and the cost of discarding
         * one is that its entry point runs the cold sequence again the next time it is used.
         */
        const val MAX: Int = 4

        /** The collection a single binding makes. */
        fun of(record: AttestedDevice): DeviceBindings = DeviceBindings(listOf(record))
    }
}
