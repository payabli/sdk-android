package com.payabli.sdk.core.telemetry

import androidx.annotation.RestrictTo

/**
 * Every event name the SDK may report.
 *
 * The names are shared with the other platform and are not this module's to invent: both SDKs bind their
 * constants to one catalog, so an event added on one side has a counterpart or a stated reason it does not.
 * Adding a name here without adding it there is how the two drift.
 *
 * They live in `:core` rather than in the telemetry module because the emitting sites do: a capability
 * artifact never depends on a sibling, so the vocabulary has to sit where every emitter can already reach it.
 * [TelemetryCatalog] holds the property keys each of these accepts, in the same module for the same reason.
 *
 * Every name is held to one shape: two to four dot-separated segments, a lowercase first letter, no
 * underscores and no hyphens, at most 64 characters. `TelemetryCatalogTest` holds every constant here to it,
 * so a name that would not be reportable is a failing test rather than an event that vanishes.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object TelemetryEvents {
    // Instrument storage.

    // Storing an instrument is reported by the money path, under `payin.storeMethod.completed`. The four
    // `tokenization.*` names the sibling SDK ships were retired on 2026-08-26: one name carrying an outcome
    // and a duration says what a started-and-succeeded pair says, and two vocabularies for one act is drift.

    // Payment form.

    /** A payment form was drawn. */
    public const val FORM_PRESENTED: String = "form.presented"

    /** The payer asked to submit, before anything is sent. */
    public const val FORM_SUBMITTED: String = "form.submitted"

    /** A field was refused on a submission, one event per field. Carries which rule, never the value. */
    public const val FORM_VALIDATION_ERROR: String = "form.validationError"

    // Card-present lifecycle.

    /** Card-present initialization began. */
    public const val TTP_INITIALIZE_STARTED: String = "ttp.initialize.started"

    /** The reader is armed. */
    public const val TTP_INITIALIZE_SUCCEEDED: String = "ttp.initialize.succeeded"

    /** Arming was refused. */
    public const val TTP_INITIALIZE_FAILED: String = "ttp.initialize.failed"

    /** Attestation began. */
    public const val TTP_ATTESTATION_STARTED: String = "ttp.attestation.started"

    /** Attestation passed. */
    public const val TTP_ATTESTATION_SUCCEEDED: String = "ttp.attestation.succeeded"

    /** Attestation was refused. */
    public const val TTP_ATTESTATION_FAILED: String = "ttp.attestation.failed"

    /** A card-present charge began. */
    public const val TTP_CHARGE_STARTED: String = "ttp.charge.started"

    /** The charge was approved. */
    public const val TTP_CHARGE_SUCCEEDED: String = "ttp.charge.succeeded"

    /** The charge was declined or failed. */
    public const val TTP_CHARGE_FAILED: String = "ttp.charge.failed"

    /** The tap window opened. */
    public const val TTP_NFC_STARTED: String = "ttp.nfc.started"

    /** A card was read. */
    public const val TTP_NFC_SUCCEEDED: String = "ttp.nfc.succeeded"

    /** The read failed or the window closed without one. */
    public const val TTP_NFC_FAILED: String = "ttp.nfc.failed"

    /** Re-arming began. */
    public const val TTP_REINITIALIZE_STARTED: String = "ttp.reinitialize.started"

    /** Re-arming completed. */
    public const val TTP_REINITIALIZE_SUCCEEDED: String = "ttp.reinitialize.succeeded"

    /** The card-present session machine moved. */
    public const val TTP_SESSION_STATE_CHANGED: String = "ttp.session.stateChanged"

    // Device-lifecycle routes. One name per route, because the route is what these are counted by, and a
    // label has to come from a fixed set where a property value does not.

    /** The device challenge route reached an outcome. */
    public const val TTP_DEVICE_CHALLENGE_COMPLETED: String = "ttp.device.challenge.completed"

    /** The device registration route reached an outcome. */
    public const val TTP_DEVICE_REGISTER_COMPLETED: String = "ttp.device.register.completed"

    /** The device attestation route reached an outcome. */
    public const val TTP_DEVICE_ATTEST_COMPLETED: String = "ttp.device.attest.completed"

    /** The device activation route reached an outcome. */
    public const val TTP_DEVICE_ACTIVATE_COMPLETED: String = "ttp.device.activate.completed"

    /** The device configuration route reached an outcome. */
    public const val TTP_DEVICE_CONFIG_COMPLETED: String = "ttp.device.config.completed"

    /**
     * The platform integrity service refused for rate or budget reasons.
     *
     * Separate from [TTP_ATTESTATION_FAILED] because its cause is not the device reporting it: the request
     * budget belongs to the cloud project and is shared across every app embedding the SDK, so one
     * integrator's traffic exhausts it for all of them while each device sees only its own failure. Telling
     * one device retrying from the budget being gone needs a fleet-wide count, which is what this is.
     */
    public const val TTP_ATTESTATION_QUOTA_EXHAUSTED: String = "ttp.attestation.quotaExhausted"

    // Card-not-present submission. One name per operation, for the reason the device routes have one per
    // route: the operation is what they are counted by.

    /** A payment submission reached a terminal outcome. */
    public const val PAYIN_CAPTURE_COMPLETED: String = "payin.capture.completed"

    /** An authorization submission reached a terminal outcome. */
    public const val PAYIN_AUTHORIZE_COMPLETED: String = "payin.authorize.completed"

    /** A store-method submission reached a terminal outcome. */
    public const val PAYIN_STORE_METHOD_COMPLETED: String = "payin.storeMethod.completed"

    /** A void reached a terminal outcome. Its own name, because the operation is what these are counted by. */
    public const val PAYIN_VOID_COMPLETED: String = "payin.void.completed"

    // System.

    /** Initialization was asked for. Emitted before a session exists, so the first one has no listener. */
    public const val SDK_INITIALIZE_STARTED: String = "sdk.initialize.started"

    /** Initialization was refused. Carries the classification and the SDK's own bounded reason. */
    public const val SDK_INITIALIZE_FAILED: String = "sdk.initialize.failed"

    /** A session is installed and reporting has started. This is the success case of the two above. */
    public const val SDK_INITIALIZED: String = "sdk.initialized"

    /**
     * Declared and never emitted: reporting through a channel the host switched off is the one
     * thing switching it off forbids. Kept so that neither platform reintroduces it as a new name.
     */
    public const val SDK_TELEMETRY_DISABLED: String = "sdk.telemetryDisabled"

    /** The host broker returned a token. */
    public const val AUTH_TOKEN_ACQUIRED: String = "auth.tokenAcquired"

    /** The host broker failed or timed out. */
    public const val AUTH_TOKEN_FAILED: String = "auth.tokenFailed"

    /** A rejected credential was replaced. */
    public const val AUTH_TOKEN_REFRESHED: String = "auth.tokenRefreshed"
}
