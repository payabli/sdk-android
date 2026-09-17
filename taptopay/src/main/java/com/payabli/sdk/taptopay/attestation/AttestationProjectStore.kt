package com.payabli.sdk.taptopay.attestation

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.storage.PayabliSecureStorage
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
        lock.withLock {
            val held = load()
            store(held + (environment.name to number))
        }
    }

    /**
     * Records a challenge field when it names a project, and does nothing when it does not.
     *
     * Absent, null, blank and non-numeric values are the same answer: nothing to remember. The next
     * [require] still fails unless an earlier challenge already stored a number for [environment].
     */
    suspend fun rememberFromChallenge(
        environment: PayabliEnvironment,
        cloudProjectNumber: String?,
    ) {
        val number = cloudProjectNumber?.trim()?.toLongOrNull() ?: return
        remember(environment, number)
    }

    /** The number last received for [environment], or null when none has been. */
    suspend fun numberFor(environment: PayabliEnvironment): Long? =
        lock.withLock { load()[environment.name] }

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

    private suspend fun load(): Map<String, Long> {
        val bytes = storage.get(ENTRY) ?: return emptyMap()
        return try {
            PayabliJson.format.decodeFromString(AttestationProjects.serializer(), bytes.decodeToString()).byEnvironment
        } catch (_: SerializationException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
    }

    private suspend fun store(byEnvironment: Map<String, Long>) {
        if (byEnvironment.isEmpty()) {
            storage.remove(ENTRY)
            return
        }
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
