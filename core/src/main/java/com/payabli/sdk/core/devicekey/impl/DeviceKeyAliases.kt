package com.payabli.sdk.core.devicekey.impl

import java.security.SecureRandom

/**
 * The alias namespace the device keys live in.
 *
 * An alias is generated rather than fixed, because the service records it as the identifier for one attested
 * key: a constant would name every install's key the same thing, and a rotated key could not be told from
 * the key it replaced. It is versioned, so a later change to how keys are minted can coexist with keys
 * already attested under the old scheme instead of adopting them.
 *
 * Lowercase hex, so an alias is safe both as a key store alias and as an HTTP header value, which is where
 * it ends up when an assertion carries it.
 */
internal object DeviceKeyAliases {
    const val PREFIX: String = "com.payabli.sdk.core.devicekey.v1"

    /** 128 bits, the same width the storage aliases use to distinguish one store from another. */
    private const val SUFFIX_BYTES = 16

    private const val SEPARATOR = '.'

    private const val SUFFIX_LENGTH = SUFFIX_BYTES * 2

    private val head = PREFIX + SEPARATOR

    fun newAlias(random: SecureRandom = SecureRandom()): String {
        val suffix = ByteArray(SUFFIX_BYTES).also(random::nextBytes)
        return head + suffix.joinToString("") { "%02x".format(it) }
    }

    /**
     * Whether [alias] has the exact shape [newAlias] produces.
     *
     * Used to leave other entries in a shared key store alone: the process's key store holds the storage key
     * too, and anything the host app put there.
     *
     * The whole shape, not the prefix. A stored value that merely starts the same way, from a hand edit or a
     * future scheme, would otherwise be handed back as a name this minted, and whatever holds keys would look
     * up an alias this could not have created.
     */
    fun isDeviceKeyAlias(alias: String): Boolean =
        alias.length == head.length + SUFFIX_LENGTH &&
            alias.startsWith(head) &&
            // Lowercase only: the generated form is lowercase, so accepting uppercase would treat two
            // different key store aliases as the same name.
            (head.length until alias.length).all { alias[it] in '0'..'9' || alias[it] in 'a'..'f' }
}
