// IDE-only workaround, not a compiler requirement: K2's IDE analysis flags the plugin-generated
// $serializer as needing this opt-in, while the compiler exempts it (KTIJ-31549). Remove when fixed.
@file:OptIn(InternalSerializationApi::class)

package com.payabli.sdk.taptopay.attestation.device

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The value of `platform` on every device request, and the only one this SDK sends.
 *
 * A string rather than an enum because the server's `DeviceOsType` has exactly one member this platform can
 * ever be, so an enum would model a choice that does not exist while adding a mapping to get wrong.
 */
internal const val DEVICE_PLATFORM: String = "Android"

/** The `status` a freshly registered device reports, before activation. */
internal const val STATUS_PENDING: String = "pending"

/**
 * The request and response shapes of the `/api/v2/device/taptopay` routes.
 *
 * **Every key on this wire is lower-camelCase, and none of these types needs a `@SerialName`.** pay-in-api
 * sets no `PropertyNamingPolicy`, so ASP.NET's `JsonSerializerDefaults.Web` applies and camel-cases its
 * PascalCase members outward; the shipping iOS client decodes the same routes with `.useDefaultKeys` and no
 * `CodingKeys`, which is independent confirmation. Adding a `@SerialName` here would be inventing a
 * disagreement.
 *
 * **No request property carries a Kotlin default.** [com.payabli.sdk.core.network.PayabliJson] encodes with
 * `encodeDefaults = false`, so a defaulted property is silently dropped from the body. That is not a style
 * preference: `platform` is `[JsonRequired]` on `/attest`, meaning the key must be physically present or the
 * server's deserializer throws before any of our own validation is reached. A nullable property with no
 * default still disappears when it is null, which is the wanted behaviour and comes from
 * `explicitNulls = false`.
 *
 * Decoding reads the same two settings in the other direction: a **nullable** property with no default
 * decodes to null when its key is absent, while a **non-nullable** one raises `MissingFieldException`. So
 * nullability here is a deliberate statement about which fields this SDK cannot proceed without, and the
 * missing-field throw is caught one layer up and reported as [DeviceServiceException.Undecodable].
 *
 * These are plain classes rather than data classes, and each writes its own `toString`, because a generated
 * one would print every field it holds: `activationCode` is a live six-digit secret, and `attestation`,
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
    /** A stable app-generated identifier. The server keys its register state machine on it. */
    val hardwareId: String,
    /** The device key's Keystore alias. */
    val keyId: String,
    val deviceName: String?,
    val model: String?,
    val osVersion: String?,
    val platform: String,
) {
    override fun toString(): String = "RegisterRequest(platform=$platform)"
}

/**
 * `{ deviceId, status }`.
 *
 * [deviceId] is required: it is the handle every later call is made against, so a response without one is
 * unusable rather than partially usable. [status] is optional because iOS declares it so, and because
 * nothing here should fail on a field it only reads to answer one question.
 */
@Serializable
internal class RegisterResponse(
    val deviceId: String,
    val status: String?,
) {
    /**
     * Whether the device is awaiting activation.
     *
     * A fresh registration is always `"pending"`, and the comparison is case-insensitive for the reason iOS
     * lowercases before comparing: the value is a bare string literal in the server's response rather than a
     * serialized enum, so nothing on either side pins its case.
     */
    val isPending: Boolean get() = status?.equals(STATUS_PENDING, ignoreCase = true) == true

    override fun toString(): String = "RegisterResponse(isPending=$isPending)"
}

/**
 * `{ challengeId, keyId, attestation, deviceId, appId, entry, platform, publicKey }`.
 *
 * [publicKey] is nullable to match the server's DTO, and is nonetheless required on this platform: the Play
 * Integrity token does not embed the key, so the server has nothing to verify a later assertion against
 * without it and answers a missing one with a refusal. Nullable rather than required here so that the
 * refusal comes from the one place that owns the rule.
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
     * **`/attest` refuses every value this field can correctly hold, today.** The service validates it against
     * the iOS `TEAM_ID.BUNDLE_ID` shape before the request reaches its own Android branch, and a package name
     * cannot match that, so the call comes back a validation error naming `appId`. It is sent correctly here
     * rather than worked around, because the Android branch ignores the field entirely and the fix belongs on
     * the service: PLA-2354. Expect that failure until it lands, and do not read it as a defect in this client.
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
 * entirely, so neither field has ever been exercised by a client against the real service; `registered` is
 * additionally a constant `true` in the only path that emits it. They are decoded so a reader can see them
 * in a diagnostic, not so a caller can act on them.
 */
@Serializable
internal class AttestResponse(
    val registered: Boolean?,
    val isSandbox: Boolean?,
) {
    override fun toString(): String = "AttestResponse(registered=$registered, isSandbox=$isSandbox)"
}

/** Six decimal digits, the shape the service issues. */
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
        // Rejected at construction, because sending a value that cannot be right is not free: the server
        // compares the code after its other guards and increments `activation_attempts` on any mismatch, and
        // the device locks out at five. A typo of five digits would spend one of them to learn what a regex
        // knows. Same reason `AttestationChallenge` rejects a malformed value rather than letting the platform
        // answer it several rounds later.
        //
        // Matched as text, never parsed as a number: the service issues six digits from a CSPRNG, so `012345`
        // is a legitimate code and an `Int` round trip would silently make it `12345`.
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
