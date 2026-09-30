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
- OAuth2 credentials with the `tools_init`, `pos_create` and `inboundpayments_create` permissions.

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

An app built without it installs and runs, and is refused when it is submitted for enrolment.

### Have your app enrolled

The card reader accepts an app only when its **package name and signing certificate** are enrolled as a
pair. Send Payabli your package name and the SHA-256 digest of each certificate that signs a build that
takes payments. A debug key, a release key and a CI key are three certificates. With Play App Signing,
the installed app is signed with Google Play's app signing certificate. An app signed with a certificate
that isn't enrolled is refused when the reader arms.

### Register your app on the allowlist

The allowlist entry for Android is your app's **package name**. Register it once per paypoint, from your
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
- Register each package name you ship, including a debug suffix or a flavour. The entry must match the
  package name of the installed app.

An app that isn't on the allowlist is refused when the device attests. `initialize()` fails, and
`sessionState` is `PendingActivation`, the same state as a phone that needs a code.

## Set up

`PayabliTTP` runs on the session you built for the whole SDK, and uses that session's entry point:

```kotlin
import com.payabli.sdk.taptopay.PayabliTTP

val ttp: PayabliTTP = PayabliTTP.create(session, applicationContext)
```

- `sessionState` is a `StateFlow<TapToPaySessionState>` and `isReady` is a `StateFlow<Boolean>`. Collect them
  to drive your UI.
- `create`, `initialize`, `activateDevice`, `charge` and `closeCapturedCharge` are `suspend` functions;
  call them from a coroutine.
- **One paypoint per session.** There is one session per app process, and it has one entry point.
  `PayabliSession.initialize` with a different entry point fails while the session is live.

## Take a payment

### Initialize

```kotlin
import com.payabli.sdk.taptopay.TapToPayException
import com.payabli.sdk.taptopay.session.TapToPaySessionState

try {
    ttp.initialize()
} catch (failure: TapToPayException) {
    if (ttp.sessionState.value == TapToPaySessionState.PendingActivation) {
        // The phone needs an activation code, the app isn't on the allowlist, or the credentials lack
        // tools_init or pos_create. See Activate a phone.
    } else {
        throw failure
    }
}
```

`initialize()` attests the device, fetches its configuration and prepares the reader. It is safe to call
again at any time. The first run on a phone takes longer than later ones.

### Activate a phone

A phone takes Tap to Pay payments for a paypoint only after it is activated with a 6-digit code.

- Activation is **per phone and per paypoint**. It isn't per user.
- A reinstall, a restore to a new phone, or a new phone needs a new code.
- One install can hold activations for up to four paypoints. Activating a fifth drops the one used least
  recently, which then needs to be set up again the next time it's used.

Until the phone is activated, `initialize()` fails and `sessionState` is `PendingActivation`. An app that
isn't on the allowlist, or credentials without `tools_init` or `pos_create`, land in the same state, so check
both before issuing a code. Credentials without `inboundpayments_create` reach `Ready`, and `charge` is then
refused before the card is read.

1. Issue a code for the phone. In the Payabli portal, under **Device Management**, the waiting device's
   options include **Activate device**. The code is valid for 30 minutes, and asking again before it
   expires returns the same code. The API route,
   [Generate Tap to Pay activation code](https://docs.payabli.com/developers/api-reference/device/activation-challenge),
   takes the device's ID, which the SDK doesn't return, so issue codes from the portal.

   The code is six digits and can start with zero, so keep it as a string.
2. Deliver the code to the person holding the phone, and have your app ask for it.
3. Activate, then initialize again:

```kotlin
ttp.activateDevice(code)
ttp.initialize()
```

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

A `TapToPayException` carries `capture` and `paymentTransId`:

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
| `PendingActivation` | The phone needs an activation code, the app isn't on the paypoint's allowlist, or the credentials lack `tools_init` or `pos_create`. |
| `SessionExpired` | The session needs refreshing. The next `charge` refreshes it. |
| `Reinitializing` | The session is being refreshed. |
| `Failed(reason)` | The session stopped. `reason` says what to do. |

### Failure reasons

| `TapToPayFailureReason` | What to do |
|---|---|
| `CONFIGURATION_REJECTED` | The paypoint, the device or its setup is missing something. Retrying won't help; contact Payabli. |
| `ATTESTATION_REQUIRED` | The device's attestation was refused or revoked. Check that the app came from Google Play, then initialize again. |
| `SERVICE_UNAVAILABLE` | The service or the reader wasn't available. Try again later. |
| `DEVICE_INELIGIBLE` | This phone can't take Tap to Pay payments: the hardware or Android version is missing something, or the card reader refused the phone. Check developer options and restart the phone. If a phone that meets the requirements still lands here, contact Payabli before replacing it. |
| `SDK_INTERNAL_ERROR` | Report it to Payabli. |

### Events

The Android SDK has no event stream. Collect `sessionState` to follow progress.

## Go live

- Your release package name and the certificate the installed app is signed with, enrolled with Payabli.
- Your release package name on your **production** paypoint's allowlist.
- Your app installed from Google Play on a phone that meets the [requirements](#requirements).
- One phone activated and one payment approved end to end, then looked up by its transaction ID.

## Related docs

- [Payabli Android SDK](../README.md): setup, the token endpoint, outcomes and go-live
- [Card-not-present payments on Android](../payin/README.md)
- [Sample app](../example/README.md)
- [Generate Tap to Pay activation code](https://docs.payabli.com/developers/api-reference/device/activation-challenge)
