# Tap to Pay on Android

The `taptopay` module lets your app take a contactless card, phone or watch payment on an Android phone,
with no external reader. Set up the session, your configuration and your token endpoint first, as the
[root README](../README.md) describes. This guide covers what Tap to Pay adds.

## Requirements

Your app:

- `minSdk` 30 or higher.
- The `packaging` setting below, in your **application** module.
- The 64-bit `arm64-v8a` ABI. The card reader has no 32-bit build, so a 32-bit-only phone can't take Tap
  to Pay payments.

The phone:

- Android 12 (API 31) or later, with NFC. An app with `minSdk` 30 installs on Android 11, but Tap to Pay
  isn't available there. NFC must be switched on in Settings; no app can switch it on.
- Developer options and USB debugging switched off, and the phone **restarted** after switching them off.
  The card reader refuses a phone it considers to be in developer mode until it restarts.
- In production, your app installed from Google Play. Ask Payabli whether your sandbox paypoint accepts
  a build installed another way. A build installed with `adb install` can be refused by the card reader.

Your account:

- A paypoint with Tap to Pay enabled. Ask your Payabli representative.
- OAuth2 credentials with the `tools_init`, `pos_create` and `inboundpayments_create` permissions.

`PayabliTTP.isSupported(context)` checks what the phone reports about itself, without a session or a
network call. `false` is reliable. `true` doesn't guarantee a payment, since it can't see the paypoint or
the card reader's own checks.

## Before you write code

### 1. Unpack the card reader's native library

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

### 2. Have your app enrolled

The card reader accepts an app only when its **package name and signing certificate** are enrolled as a
pair. Send Payabli your package name and the SHA-256 digest of each certificate that signs a build that
takes payments. A debug key, a release key and a CI key are three certificates. With Play App Signing,
the installed app is signed with Google Play's app signing certificate. An app signed with a
certificate that isn't enrolled is refused when the reader arms.

### 3. Register your app on the paypoint's allowlist

The allowlist entry for Android is your app's **package name**. Register it once per paypoint, from your
backend:

```bash
curl -X POST "https://api-sandbox.payabli.com/api/v2/paypoint/{entryPoint}/apps" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -d '{ "deviceOs": "android", "appId": "com.example.checkout", "friendlyName": "Checkout" }'
```

The call needs the `pos_create` permission. An API token in the `requestToken` header works in place of
the bearer token. `friendlyName` is optional. Calling it again with the same
values is safe. Register each package name you ship, including a debug suffix or a flavour.

Payabli reads the package name from Google Play Integrity's signed verdict, not from your request. An app
that isn't on the allowlist, or that Google Play doesn't recognize, is refused when the device attests.

## Create the Tap to Pay session

`PayabliTTP` runs on the session you built for the whole SDK, and uses that session's entry point:

```kotlin
import com.payabli.sdk.taptopay.PayabliTTP

val ttp: PayabliTTP = PayabliTTP.create(session, applicationContext)
```

`sessionState` is a `StateFlow<TapToPaySessionState>` and `isReady` a `StateFlow<Boolean>`. Collect them
to drive your UI. `create`, `initialize`, `activateDevice` and `charge` are `suspend` functions; call them
from a coroutine.

## Initialize

```kotlin
import com.payabli.sdk.taptopay.TapToPayException
import com.payabli.sdk.taptopay.session.TapToPaySessionState

try {
    ttp.initialize()
} catch (failure: TapToPayException) {
    if (ttp.sessionState.value == TapToPaySessionState.PendingActivation) {
        // The phone needs an activation code. See Activate a phone.
    } else {
        throw failure
    }
}
```

`initialize()` attests the device, fetches its configuration and prepares the reader. It is safe to call
again at any time. The first run on a phone takes longer than later ones.

## Activate a phone

A phone takes Tap to Pay payments for a paypoint only after it is activated with a 6-digit code.

- Activation is **per phone and per paypoint**. It isn't per user.
- A reinstall, a restore to a new phone, or a new phone needs a new code.
- One install can hold activations for up to four paypoints. Activating a fifth drops the one used least
  recently, which then needs to be set up again the next time it's used.

Until the phone is activated, `initialize()` fails and `sessionState` is `PendingActivation`.

1. Your backend requests a code with
   [Generate Tap to Pay activation code](https://docs.payabli.com/developers/api-reference/device/activation-challenge),
   `POST /api/v2/device/taptopay/activate/challenge`, which takes the entry point and the device's ID. The
   code is valid for 30 minutes. Asking again before it expires returns the same code. The SDK doesn't
   return the device's ID. A device waiting for activation, and its code, are shown in the Payabli portal
   under **Devices**.
2. Deliver the code to the person holding the phone, and have your app ask for it.
3. Activate, then initialize again:

```kotlin
ttp.activateDevice(code)
ttp.initialize()
```

## One paypoint per session

There is one session per app process, and it has one entry point. `PayabliSession.initialize` with a
different entry point fails while the session is live, so an app takes Tap to Pay payments for one
paypoint per session.

## Charge

When `sessionState` is `Ready`, or `SessionExpired`, which `charge` repairs before it reads the card:

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
println("Charged: ${result.paymentTransId}")
```

| Parameter | Type | Notes |
|---|---|---|
| `paymentDetails` | `TapToPayPaymentDetails` | `amount` is required. `serviceFee` defaults to zero. Leave `currency` out to charge in the paypoint's currency. `paymentDescription` is optional. |
| `customer` | `TapToPayCustomerData` | Required. Name the payer with at least one of `firstName`, `lastName`, `customerNumber` or `customerId`; a charge that names nobody is refused before the card is read. The other fields are optional. |
| `invoice` | `TapToPayInvoiceData` | Optional. `invoiceNumber`. |
| `orderDescription` | `String?` | Optional. |

`charge` suspends until the payer taps, and returns only when the payment is approved. The result carries
`paymentTransId`, and `cardNetwork` when the reader reports it. Store `paymentTransId` with your order.

## Outcomes and errors

Every failure is a `TapToPayException`, apart from a coroutine cancellation. It carries `capture` and
`paymentTransId`:

| `capture` | Meaning | What to do |
|---|---|---|
| `NOT_CHARGED` | The card wasn't charged. | You can retry. |
| `UNKNOWN` | The outcome isn't known. | Look up `paymentTransId` with [`GET /api/MoneyIn/details/{transId}`](https://docs.payabli.com/developers/api-reference/moneyin/get-details-for-a-processed-transaction) before charging again. |
| `CHARGED` | The card was charged, but the step that confirms it didn't complete. | Don't charge again. Call `closeCapturedCharge(paymentTransId)`, which confirms it without reading the card again. |

`closeCapturedCharge` is safe to call more than once. It works only for the payment most recently taken
for this entry point in this process; for anything else, reconcile with the transaction lookup.

## Session states

| `TapToPaySessionState` | Meaning |
|---|---|
| `Idle` | Not started, or activated and waiting for `initialize()`. |
| `AttestingDevice`, `FetchingConfig`, `InitializingReader` | `initialize()` is running. |
| `Ready` | Ready to charge. |
| `PendingActivation` | The phone needs an activation code. |
| `SessionExpired` | The session needs refreshing. The next `charge` refreshes it. |
| `Reinitializing` | The session is being refreshed. |
| `Failed(reason)` | The session stopped. `reason` says what to do. |

| `TapToPayFailureReason` | What to do |
|---|---|
| `CONFIGURATION_REJECTED` | The paypoint, the device or its setup is missing something. Retrying won't help; contact Payabli. |
| `ATTESTATION_REQUIRED` | The device's identity was refused. Check the allowlist and that the app came from Google Play, then initialize again. |
| `SERVICE_UNAVAILABLE` | The service or the reader wasn't available. Try again later. |
| `DEVICE_INELIGIBLE` | This phone can't take Tap to Pay payments: the hardware or Android version is missing something, or the card reader refused the phone. Check developer options and restart the phone before trying another one. |
| `SDK_INTERNAL_ERROR` | Report it to Payabli. |

## Go live

- Your release package name and the certificate the installed app is signed with, enrolled with Payabli.
- Your release package name on your **production** paypoint's allowlist.
- Your app installed from Google Play on a phone that meets the [requirements](#requirements).
- One phone activated and one payment approved end to end, then looked up by its transaction ID.
