# Tap to Pay on Android

The `taptopay` module lets your app take a contactless card, phone or watch payment on an Android phone,
with no external reader. This guide is part of the [Payabli Android SDK](../README.md); set up the SDK and
its session there first.

> [!IMPORTANT]
> **Notice:** This SDK is in beta. Its public interface can change in ways that aren't backward compatible. See
> [Versioning and support](../README.md#versioning-and-support).

## Requirements

### Your app

- `minSdk` 30 or higher.
- The `packaging` setting in [Unpack the card reader's native library](#unpack-the-card-readers-native-library),
  in your **application** module.
- The 64-bit `arm64-v8a` ABI. The card reader has no 32-bit build.
- Nothing more in your manifest. The card reader library adds `NFC`, `INTERNET`, `ACCESS_NETWORK_STATE`,
  `CAMERA`, `HIDE_OVERLAY_WINDOWS` and `ACCELEROMETER` to your merged manifest itself.

### The phone

- Android 12 (API 31) or later, with NFC. An app with `minSdk` 30 installs on Android 11, but Tap to Pay
  isn't available there. NFC must be switched on in Settings; no app can switch it on.
- A 64-bit phone. A 32-bit-only phone can't take Tap to Pay payments.
- Developer options and USB debugging switched off, and the phone **restarted** after switching them off.
  The card reader refuses a phone it considers to be in developer mode until it restarts.
- In production, your app installed from Google Play. Ask Payabli whether your sandbox paypoint accepts a
  build installed another way. A build installed with `adb install` can be refused by the card reader.

`PayabliTTP.isSupported(context)` checks what the phone reports about itself, without a session or a
network call. `false` is reliable. `true` doesn't guarantee a payment, since it can't see the paypoint or
the card reader's own checks.

### Your account

- A paypoint with Tap to Pay enabled. Ask your Payabli representative.
- OAuth2 credentials with these permissions:

  | Operation | Permission |
  |---|---|
  | `initialize()` | `tools_init` and `pos_create` |
  | `activateDevice` | `pos_create` |
  | `charge` | `inboundpayments_create` |
  | Registering an authorized app through the API | `pos_create` |

## Before you start

### Unpack the card reader's native library

Add this to your **application** module's `build.gradle.kts`. The library module can't set it for you:

```kotlin
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

An app built without it installs and runs, and is refused when it is submitted for enrollment.

### Have your app enrolled

The card reader accepts an app only when its **package name and signing certificate** are enrolled as a
pair. Send Payabli your package name and the SHA-256 digest of each certificate that signs a build that
takes payments. A debug key, a release key and a CI key are three certificates. With Play App Signing,
the installed app is signed with Google Play's app signing certificate. An app signed with a certificate
that isn't enrolled is refused when the reader arms.

### Register your app as an authorized app

The authorized app entry for Android is your app's **package name**. Register it once per paypoint,
in the Payabli portal under **Pay In > Devices > Device management**, **⋯ > Authorized apps**, or from your
backend:

```bash
curl -X POST "https://api-sandbox.payabli.com/api/v2/paypoint/{entryPoint}/apps" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -d '{ "deviceOs": "android", "appId": "com.example.checkout", "friendlyName": "Checkout" }'
```

- The call needs the `pos_create` permission. An API token in the `requestToken` header works in place of
  the bearer token.
- `friendlyName` is optional. Calling it again with the same values is safe.
- Register each package name you ship, including a debug suffix or a flavor. The entry must match the
  package name of the installed app.

An app that isn't an authorized app is refused when the device attests, with an HTTP 403. `initialize()`
throws a `TapToPayException` whose `type` is `PERMISSION_DENIED`, and `sessionState` is
`Failed(CONFIGURATION_REJECTED)`: the phone holds no registration for the paypoint, so there is nothing to
activate. Register the app, then initialize again. On a phone that already holds a registration, the same
refusal throws `DEVICE_PENDING_ACTIVATION` and lands on `PendingActivation(activationId)`.

## Set up

`PayabliTTP` runs on the session you built for the whole SDK, and uses that session's entry point:

```kotlin
import com.payabli.sdk.taptopay.PayabliTTP

val ttp: PayabliTTP = PayabliTTP.create(session, applicationContext)
```

- `sessionState` is a `StateFlow<TapToPaySessionState>` and `isReady` is a `StateFlow<Boolean>`. Collect them
  to drive your UI.
- `create`, `initialize`, `activateDevice`, `charge` and `closeCapturedCharge` are `suspend` functions; call
  them from a coroutine.

## Take a payment

### Initialize

```kotlin
import com.payabli.sdk.taptopay.TapToPayException
import com.payabli.sdk.taptopay.session.TapToPaySessionState

try {
    ttp.initialize()
} catch (failure: TapToPayException) {
    if (ttp.sessionState.value is TapToPaySessionState.PendingActivation) {
        // The phone needs an activation code. See Activate a phone.
    } else {
        throw failure
    }
}
```

`initialize()` attests the device, fetches its configuration and prepares the reader. It is safe to call
again while no charge is running. The first run on a phone takes longer than later ones.

### Activate a phone

A phone takes Tap to Pay payments for a paypoint only after it is activated with a 6-digit code.

- Activation is **per phone and per paypoint**. It isn't per user.
- A reinstall, a restore to a new phone, or a new phone needs a new code.
- One install can hold activations for up to four paypoints. Activating a fifth drops the one used least
  recently, which then needs to be set up again the next time it's used.

Until the phone is activated, `initialize()` throws a `TapToPayException` whose `type` is
`DEVICE_PENDING_ACTIVATION`, and `sessionState` is `PendingActivation(activationId)`. An app that isn't an
authorized app, or credentials without `tools_init` or `pos_create`, land on
`Failed(CONFIGURATION_REJECTED)` on a phone with no setup stored for the paypoint from an earlier run.
Credentials without
`inboundpayments_create` reach `Ready`, and `charge` throws a `TapToPayException` whose `type` is
`PERMISSION_DENIED`, before the card is read.

The code is six digits and can start with zero, so keep it as a string. It's valid for 30 minutes, and
asking for one again before it expires returns the same code. There are two ways to get it to the app, and
both end the same way: activate, then initialize again.

```kotlin
ttp.activateDevice(code)
ttp.initialize()
```

#### Option 1: Manual

Someone issues the code and gives it to the person holding the phone, and your app asks for it.

- **From the portal:** under **Pay In > Devices > Device management**, choose
  **⋯ > Generate activation code**.
- **From a backend tool:** call
  [Generate Tap to Pay activation code](https://docs.payabli.com/developers/api-reference/device/activation-challenge)
  with the paypoint's entry point and this phone's activation ID in the request's `deviceId` field. The app
  reads the ID from the pending state, as in option 2, step 1, and shows it to whoever runs the tool.

#### Option 2: Automated, in the app

No person handles the code.

1. Read the phone's activation ID. It's on the pending state, and only there:

   ```kotlin
   (ttp.sessionState.value as? TapToPaySessionState.PendingActivation)?.let { pending ->
       // Send pending.activationId to your backend.
   }
   ```

   An app that lost the ID initializes again and lands on the same one.
2. Send the activation ID to your backend. Your backend calls
   [Generate Tap to Pay activation code](https://docs.payabli.com/developers/api-reference/device/activation-challenge)
   with the paypoint's entry point and the activation ID in the request's `deviceId` field, and returns the
   code.

   Anyone who can call this route can set up a phone to take payments for your paypoint, so it has to
   authenticate the caller and check that they may take payments, the way your token endpoint does.
3. Activate, then initialize again.

The activation ID is read from this phone's own state, never looked up as the latest pending device on the
paypoint, so another phone enrolling on the same paypoint can't change it.

### Charge

When `sessionState` is `Ready`, or `SessionExpired`, which `charge` refreshes before it reads the card:

```kotlin
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import java.math.BigDecimal

val result = ttp.charge(
    paymentDetails = TapToPayPaymentDetails(BigDecimal("9.99")),
    customer = TapToPayCustomerData(firstName = "Jane", lastName = "Doe"),
    invoice = TapToPayInvoiceData(invoiceNumber = "INV-9001"),
)
order.paymentTransId = result.paymentTransId // store it; don't log it
```

| Parameter | Type | Notes |
|---|---|---|
| `paymentDetails` | `TapToPayPaymentDetails` | `amount`, the total charged, is required. `serviceFee` defaults to zero. `serviceFee` is part of `amount`, not added to it: the card is charged `amount`. Leave `currency` out to charge in the paypoint's currency. `paymentDescription` is optional. |
| `customer` | `TapToPayCustomerData` | Required. Name the payer with at least one of `firstName`, `lastName`, `customerNumber` or `customerId`; a charge that names nobody is refused before the card is read. The other fields are optional. |
| `invoice` | `TapToPayInvoiceData` | Optional. `invoiceNumber`. |
| `orderDescription` | `String?` | Optional. |

`charge` suspends until the payer taps, and returns only when the payment is approved. The result carries
`paymentTransId`, and `cardNetwork` when the reader reports it. Store `paymentTransId` with your order.

## Outcomes and errors

Every failure is a `TapToPayException`, apart from a coroutine cancellation. A cancellation is rethrown
as `CancellationException` and carries no transaction ID. Cancelling after the card was presented doesn't
mean nothing was charged: find the transaction before charging again.

A `TapToPayException` carries the catalog entry for its cause:

- `category` says what to do, such as `CREDENTIAL` (call `initialize()` again) or `OUTCOME_UNKNOWN` (find
  the transaction before repeating the call). Choose your remedy from `category`.
- `type` names the cause, for a case your app handles on its own, such as `DEVICE_PENDING_ACTIVATION`.
- `code` is the catalog number Payabli support reads. Give it to them with the failure.
- `message` is fixed text, safe to show and to log. `reason` is a short summary and `detail` a longer
  explanation, from the service or the SDK, when there is one. Show them, but don't log them: the service's
  text can repeat what the request carried.
- `retryAfterMillis` is the wait the service asked for before trying again, in milliseconds, when it asked
  for one and the failure carries it.

These Tap to Pay causes are ones your app handles, with their codes:

| `type` | `code` | `category` | `message` |
|---|---|---|---|
| `ACTIVATION_CODE_MALFORMED` | 3023 | `INVALID_REQUEST` | The activation code must be six digits. |
| `ACTIVATION_CODE_INCORRECT` | 3024 | `INVALID_REQUEST` | The activation code is incorrect. |
| `ACTIVATION_CODE_EXPIRED` | 3025 | `CONFIGURATION` | The activation code has expired. |
| `ACTIVATION_ATTEMPTS_EXHAUSTED` | 3026 | `CONFIGURATION` | Too many incorrect activation codes were entered. |
| `ACTIVATION_CODE_NOT_ISSUED` | 3027 | `CONFIGURATION` | No activation code has been issued for this device. |
| `DEVICE_NOT_PENDING` | 3028 | `INVALID_REQUEST` | This device is not waiting for activation. |
| `TERMINAL_NOT_READY` | 3029 | `INVALID_REQUEST` | The terminal is not ready for this call. |
| `TOO_MANY_OPEN_CHARGES` | 3030 | `INVALID_REQUEST` | Too many charges are waiting to be resolved. |
| `PAYMENT_NOT_HELD` | 3031 | `INVALID_REQUEST` | No captured payment is held under that identifier. |
| `CHARGE_NOT_FINISHED` | 3034 | `OUTCOME_UNKNOWN` | An earlier charge on this device hasn't finished. Check that payment before charging again, or wait three minutes. |

It also carries `capture` and `paymentTransId`:

| `capture` | Meaning | What to do |
|---|---|---|
| `NOT_CHARGED` | The card wasn't charged. | You can retry. |
| `UNKNOWN` | The outcome isn't known. | Look up `paymentTransId` with [`GET /api/MoneyIn/details/{transId}`](https://docs.payabli.com/developers/api-reference/moneyin/get-details-for-a-processed-transaction) before charging again. When there's no ID, find the transaction in the Payabli portal. |
| `CHARGED` | The card was charged, but the step that confirms it didn't complete. | Don't charge again. Call `closeCapturedCharge(paymentTransId)`, which confirms it without reading the card again. |

Call `closeCapturedCharge` again only while the close is unconfirmed. Once one succeeds, the SDK no longer
holds the payment, and a further call fails with `capture` `UNKNOWN`. It works only for the payment most
recently taken for this entry point in this process; for anything else, reconcile with the transaction
lookup.

## Reference

### Session states

`sessionState` is a `TapToPaySessionState`:

| State | Meaning |
|---|---|
| `Idle` | Not started, or activated and waiting for `initialize()`. |
| `AttestingDevice`, `FetchingConfig`, `InitializingReader` | `initialize()` is running. |
| `Ready` | Ready to charge. |
| `Charging(activity)` | `charge` is running, and `isReady` is `false` until it ends. `activity` is `OPENING`, `WAITING_FOR_CARD` or `CLOSING`; show a tap prompt on `WAITING_FOR_CARD`. The outcome is what `charge` returns or throws. |
| `PendingActivation(activationId)` | The phone needs an activation code. `activationId` is what the activation route's `deviceId` field takes. |
| `SessionExpired` | The session needs refreshing. The next `charge` refreshes it. |
| `Reinitializing` | The session is being refreshed. |
| `Failed(reason)` | The session stopped. `reason` says what to do. |

### Failure reasons

| `TapToPayFailureReason` | What to do |
|---|---|
| `CONFIGURATION_REJECTED` | The paypoint, the device or its setup is missing something. Retrying won't help. If Google Play on the phone is missing, out of date or signed out, fix that on the phone; otherwise contact Payabli. |
| `DEVICE_SETUP_REQUIRED` | This device's setup was refused or revoked. Call `initialize()`, which sets the device up again. If it lands here again, check that the app came from Google Play. |
| `SERVICE_UNAVAILABLE` | The service or the reader wasn't available. Try again later. |
| `DEVICE_INELIGIBLE` | This phone can't take Tap to Pay payments: the hardware or Android version is missing something, or the card reader refused the phone. Check developer options and restart the phone. If a phone that meets the requirements still lands here, contact Payabli before replacing it. |
| `SDK_INTERNAL_ERROR` | Report it to Payabli. |
| `DEVICE_KEY_UNAVAILABLE` | The phone's key facility or secure storage failed, so the SDK can't tell whether the device's keys still work. Initialize again. If it keeps failing, the phone is the cause. |

### Watching the session

Collect `sessionState`, a `StateFlow<TapToPaySessionState>`, to follow progress.

## Go live

- Your release package name and the certificate the installed app is signed with, enrolled with Payabli.
- Your release package name among your **production** paypoint's authorized apps.
- Your app installed from Google Play on a phone that meets the [requirements](#requirements).
- One phone activated and one payment approved end to end, then looked up by its transaction ID.

## Related docs

- [Payabli Android SDK](../README.md): setup, the token endpoint, outcomes and go-live
- [Card-not-present payments on Android](../payin/README.md)
- [Sample app](../example/README.md)
- [Generate Tap to Pay activation code](https://docs.payabli.com/developers/api-reference/device/activation-challenge)
