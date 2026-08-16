// IDE-only workaround, not a compiler requirement: K2's IDE analysis flags the plugin-generated
// $serializer as needing this opt-in, while the compiler exempts it (KTIJ-31549). Remove when fixed.
@file:OptIn(InternalSerializationApi::class)

package com.payabli.sdk.taptopay.attestation.device

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The value of `platform` on every device request, and the only one this SDK sends.
 *
 * A string rather than an enum because this platform can only ever send one value, so an enum would model a
 * choice that does not exist while adding a mapping to get wrong.
 */
internal const val DEVICE_PLATFORM: String = "Android"

/** The `status` a device reports before activation. */
internal const val STATUS_PENDING: String = "pending"

/** The `status` an activated device reports. */
internal const val STATUS_ACTIVE: String = "active"

/**
 * The request and response shapes of the device routes.
 *
 * **Every key on this wire is lower-camelCase, and none of these types needs a `@SerialName`.** The shipping
 * iOS client decodes the same routes with `.useDefaultKeys` and no `CodingKeys`, on the same keys. Adding a
 * `@SerialName` here would be inventing a disagreement.
 *
 * **No request property carries a Kotlin default.** [com.payabli.sdk.core.network.PayabliJson] encodes with
 * `encodeDefaults = false`, so a defaulted property is silently dropped from the body. That is not a style
 * preference: `platform` on `/attest` must be physically present, and a body that omits the key is refused
 * before any of its other fields are read. A nullable property with no default still disappears when it is
 * null, which is the wanted behaviour and comes from `explicitNulls = false`.
 *
 * Decoding reads the same two settings in the other direction: a **nullable** property with no default
 * decodes to null when its key is absent, while a **non-nullable** one raises `MissingFieldException`. So
 * nullability here is a deliberate statement about which fields this SDK cannot proceed without, and the
 * missing-field throw is caught one layer up and reported as [DeviceServiceException.Undecodable].
 *
 * These are plain classes rather than data classes, and each writes its own `toString`, because a generated
 * one would print every field it holds: `activationCode` is a live secret, and `attestation`,
 * `publicKey`, `keyId`, `hardwareId` and `deviceId` are device identity. `toString` reaches exception
 * messages and diagnostics, which the logger cannot redact.
 */
@Serializable
internal class ChallengeRequest(
    val entry: String,
) {
    override fun toString(): String = "ChallengeRequest()"
}

/** `{ challengeId, challenge }`. Both required: a challenge missing either is not usable. */
@Serializable
internal class ChallengeResponse(
    val challengeId: String,
    /**
     * The server's nonce material, standard base64.
     *
     * **Not what goes to Play Integrity.** See [DeviceAttestationBinding.nonceChallenge] for the derivation
     * the backend expects.
     */
    val challenge: String,
) {
    override fun toString(): String = "ChallengeResponse()"
}

/**
 * `{ entry, hardwareId, keyId, deviceName, model, osVersion, platform }`.
 *
 * [deviceName], [model] and [osVersion] are descriptive only and optional server-side. They are parameters
 * rather than reads of `android.os.Build` so that nothing in this package touches an Android type; whoever
 * owns the registration flow supplies them.
 */
@Serializable
internal class RegisterRequest(
    val entry: String,
    /** A stable app-generated identifier. It is what identifies this device across registrations. */
    val hardwareId: String,
    /**
     * The device key's identifier, and what an attestation is later looked up by.
     *
     * Not the alias the key is stored under. That is fixed and identical on every install, so it could not
     * tell one device's key from another's or from the key it replaced.
     */
    val keyId: String,
    val deviceName: String?,
    val model: String?,
    val osVersion: String?,
    val platform: String,
) {
    override fun toString(): String = "RegisterRequest(platform=$platform)"
}

/**
 * `{ deviceId, status, outcome }`.
 *
 * [deviceId] is required: it is the handle every later call is made against, so a response without one is
 * unusable rather than partially usable. [status] is optional because iOS declares it so, and because
 * nothing here should fail on a field it only reads to answer one question.
 */
@Serializable
internal class RegisterResponse(
    val deviceId: String,
    val status: String?,
    /**
     * What a registration did with this device: created it, reused it, or replaced it.
     *
     * **Absent today**, and defaulted rather than required so it stays absent without failing a decode. It is
     * here because [status] alone cannot say, and a device otherwise has no way to notice that the
     * registration it was using has been replaced.
     *
     * A `String`, not an enum: a value added later must not fail a decode.
     */
    val outcome: String? = null,
) {
    /**
     * Whether the device is awaiting activation.
     *
     * The comparison is case-insensitive for the reason iOS lowercases before comparing: the value arrives as
     * a bare string rather than a serialized enum, so nothing on either side pins its case.
     */
    val isPending: Boolean get() = status?.equals(STATUS_PENDING, ignoreCase = true) == true

    /**
     * Whether the device is active and owes no activation code.
     *
     * The negation of [isPending] does not answer this. [status] is nullable and the service may add
     * values, so an absent or unrecognized status makes both properties false. Reading only [isPending]
     * would then record the device as active: the next run answers from that record, prompts for no code,
     * and the device never activates. Owing a code that is not needed is answered by the service on the
     * next call; not owing one that is needed is not.
     */
    val isActive: Boolean get() = status?.equals(STATUS_ACTIVE, ignoreCase = true) == true

    override fun toString(): String = "RegisterResponse(isPending=$isPending)"
}

/**
 * `{ challengeId, keyId, attestation, deviceId, appId, entry, platform, publicKey }`.
 *
 * [publicKey] is nullable to match the wire shape, and is nonetheless required on this platform: the Play
 * Integrity token does not embed the key, so a later assertion has nothing to be verified against without it.
 * Nullable rather than required here so that the refusal comes from the one place that owns the rule.
 */
@Serializable
internal class AttestRequest(
    val entry: String,
    val challengeId: String,
    val deviceId: String,
    val keyId: String,
    /**
     * The application id. On this platform, the package name.
     *
     * Not what identifies the caller on Android: that is the Google-signed `packageName` inside the integrity
     * verdict, which an app cannot state about itself. Sent anyway, and its shape is validated when present.
     */
    val appId: String,
    /** The integrity token, base64-encoded. See [DeviceAttestationBinding.attestationField]. */
    val attestation: String,
    /** Base64 of the 65-byte X9.62 uncompressed EC point. */
    val publicKey: String?,
    val platform: String,
) {
    override fun toString(): String = "AttestRequest(platform=$platform)"
}

/**
 * `{ registered, isSandbox }`.
 *
 * Every field optional, and **nothing should branch on them.** The shipping iOS client discards this body
 * entirely, so neither field has ever been exercised by a client in production. They are decoded so a reader
 * can see them in a diagnostic, not so a caller can act on them.
 */
@Serializable
internal class AttestResponse(
    val registered: Boolean?,
    val isSandbox: Boolean?,
) {
    override fun toString(): String = "AttestResponse(registered=$registered, isSandbox=$isSandbox)"
}

/** Six decimal digits, the shape an activation code takes. */
private val ACTIVATION_CODE = Regex("^[0-9]{6}$")

/** `{ entry, deviceId, activationCode }`, plus the assertion headers in [DeviceAssertion]. */
@Serializable
internal class ActivateRequest(
    val entry: String,
    val deviceId: String,
    /** The six-digit code, delivered to the device user out of band. Never logged, never in `toString`. */
    val activationCode: String,
) {
    init {
        // Rejected at construction, because sending a value that cannot be right is not free: a code that is
        // sent counts against the attempt limit even when it could not have matched, so a typo would spend one
        // to learn what a regex knows. Same reason `AttestationChallenge` rejects a malformed value rather
        // than letting the platform answer it several rounds later.
        //
        // Matched as text, never parsed as a number: a leading zero is legitimate, and an `Int` round trip
        // would silently make `012345` into `12345`.
        require(ACTIVATION_CODE.matches(activationCode)) {
            // The value is a live credential inside its window, so the message names the field and the shape
            // and never what was passed.
            "activationCode must be exactly six digits"
        }
    }

    override fun toString(): String = "ActivateRequest()"
}

/**
 * `{ deviceId, status }`.
 *
 * Both optional and neither load-bearing, for the reason given on [AttestResponse]: reaching this type at
 * all is the success signal, because a failed activation arrives as an envelope decline instead.
 */
@Serializable
internal class ActivateResponse(
    val deviceId: String?,
    val status: String?,
) {
    override fun toString(): String = "ActivateResponse()"
}

/**
 * `{ credentials }`.
 *
 * Required, unlike the payloads above: a config carrying no credentials is unusable, so an absent one is
 * a decode failure.
 */
@Serializable
internal class ConfigResponse(
    val credentials: ReaderCredentials,
) {
    override fun toString(): String = "ConfigResponse()"
}

/**
 * What the card reader is configured with, for one paypoint.
 *
 * **Typed, and that is a redaction decision.** A `Map`'s `toString` prints every value it holds, and two
 * of these are the reader vendor's API credentials, so a map puts them into any message built from one that
 * reached an exception. Naming the fields also states which two the reader cannot start without here.
 *
 * Every field is required. The service sends all of them, possibly empty, and a missing one means the
 * response is not this route's. That is what makes [platform] self-enforcing: the sibling platform's
 * variant omits [ppId] and [hostPort], so it fails to decode here.
 *
 * `pageIdentifier` sits beside these on the wire and is not modelled. It is a fresh token per call, so it is
 * not the credential the attestation is bound to, and sending it as the bearer fails every request on this
 * route.
 */
@Serializable
internal class ReaderCredentials(
    val platform: String,
    /** The reader vendor's application key. A live secret: never logged, never in `toString`. */
    val secretKey: String,
    /** The reader vendor's application id. A live secret, on the same terms as [secretKey]. */
    val apiKey: String,
    val merchantId: String,
    /** `"sandbox"` or `"production"`, as this merchant's reader is configured to run. */
    val environment: String,
    /** ISO 4217, three letters. */
    val currencyCode: String,
    val merchantName: String,
    /** ISO 18245. */
    val merchantCategoryCode: String,
    val terminalId: String,
    /** Required by the reader on this platform, and absent from the sibling platform's variant. */
    val ppId: String,
    /** `host:port`, on the same terms as [ppId]. */
    val hostPort: String,
) {
    override fun toString(): String = "ReaderCredentials(platform=$platform)"
}
