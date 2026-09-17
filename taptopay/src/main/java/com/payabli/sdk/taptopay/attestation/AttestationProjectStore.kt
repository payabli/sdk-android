package com.payabli.sdk.taptopay.attestation

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.SecureStorageException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * The attestation project numbers the service has returned, keyed by environment.
 *
 * The challenge response is the only source. A number received for an environment is kept until a later
 * challenge for that environment overwrites it, so a mint that has no response in hand can still name the
 * project. Environments are isolated: a number stored for one is never read for another.
 *
 * The number is not a secret — every app shipping Play Integrity carries its project number in the binary —
 * and this store exists so the value survives the gap between challenge and mint, not so it is protected.
 */
internal class AttestationProjectStore(
    private val storage: PayabliSecureStorage,
) {
    private val lock = SHARED_LOCK

    /**
     * Records [number] for [environment], replacing whatever was stored for it.
     *
     * Other environments are left alone.
     */
    suspend fun remember(
        environment: PayabliEnvironment,
        number: Long,
    ) {
        require(number > 0L) { "cloud project number must be positive" }
        lock.withLock {
            val held = load()
            store(held + (environment.name to number))
        }
    }

    /**
     * Records a challenge field when it names a project, and does nothing when it does not.
     *
     * Absent, null, blank, non-numeric and non-positive values are the same answer: nothing to remember.
     * A later [require] still fails only when nothing has ever been stored for [environment] — an omit on
     * this response does not clear an earlier number.
     */
    suspend fun rememberFromChallenge(
        environment: PayabliEnvironment,
        cloudProjectNumber: String?,
    ) {
        val number = parse(cloudProjectNumber) ?: return
        remember(environment, number)
    }

    /**
     * The project this challenge-to-mint interval must use, decided under one hold of the store lock.
     *
     * When the response names a project, that value is persisted and returned. When it does not, the
     * environment's stored number is returned, or [AttestationException.Misconfigured] when none has
     * ever been received. Returning the parsed value rather than re-reading after a separate write is
     * what keeps a concurrent enrollment from changing the number already chosen for this challenge.
     */
    suspend fun resolveFromChallenge(
        environment: PayabliEnvironment,
        cloudProjectNumber: String?,
    ): Long =
        lock.withLock {
            val parsed = parse(cloudProjectNumber)
            val held = load()
            if (parsed != null) {
                store(held + (environment.name to parsed))
                return@withLock parsed
            }
            held[environment.name]
                ?: throw AttestationException.Misconfigured(
                    errorCode = null,
                    message = MISSING_ATTESTATION_PROJECT,
                )
        }

    /** The number last received for [environment], or null when none has been. */
    suspend fun numberFor(environment: PayabliEnvironment): Long? = lock.withLock { load()[environment.name] }

    /**
     * The number last received for [environment], or [AttestationException.Misconfigured] when none has.
     *
     * Raised before Play Integrity is consulted. [AttestationException.Misconfigured.errorCode] is null
     * because the failure never reached the platform.
     */
    suspend fun require(environment: PayabliEnvironment): Long =
        numberFor(environment)
            ?: throw AttestationException.Misconfigured(
                errorCode = null,
                message = MISSING_ATTESTATION_PROJECT,
            )

    private fun parse(cloudProjectNumber: String?): Long? =
        cloudProjectNumber?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    private suspend fun load(): Map<String, Long> {
        val bytes =
            try {
                storage.get(ENTRY)
            } catch (_: SecureStorageException.KeyInvalidated) {
                // The entry is gone. Same answer as nothing stored.
                return emptyMap()
            } catch (_: SecureStorageException.ValueUnreadable) {
                return emptyMap()
            }
                // CryptoUnavailable and StorageUnavailable propagate: a momentary unread must not be
                // treated as "no project", or enroll would Misconfigure a paypoint that still holds one.
                ?: return emptyMap()

        return try {
            PayabliJson.format
                .decodeFromString(AttestationProjects.serializer(), bytes.decodeToString())
                .byEnvironment
        } catch (_: SerializationException) {
            emptyMap()
        }
    }

    private suspend fun store(byEnvironment: Map<String, Long>) {
        storage.set(
            ENTRY,
            PayabliJson.format
                .encodeToString(AttestationProjects.serializer(), AttestationProjects(byEnvironment))
                .encodeToByteArray(),
        )
    }

    companion object {
        internal const val ENTRY: String = "com.payabli.sdk.taptopay.attestation.projects.v1"

        /** Ruled 2026-09-16: names Payabli so a host does not hunt a setting they do not own. */
        internal const val MISSING_ATTESTATION_PROJECT: String =
            "this Payabli environment has no attestation project configured"

        private val SHARED_LOCK = Mutex()
    }
}

@Serializable
private class AttestationProjects(
    val byEnvironment: Map<String, Long>,
)
