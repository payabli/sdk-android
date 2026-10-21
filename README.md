# Payabli Android SDK

The Payabli Android SDK lets your app take payments through Payabli. Set it up once, and take a payment
either way on the same session: **card-not-present**, with card or bank account details entered in the
SDK's Compose form or in your own UI, or **Tap to Pay**, with a contactless card, phone or watch tapped
on the phone.

Your app never holds your Payabli client ID or client secret. It supplies a function that fetches a
short-lived access token from your backend, and the SDK calls it when it needs one. The SDK holds that
token in memory while the session runs.

[Payabli developer documentation](https://docs.payabli.com) ·
[Card-not-present guide](payin/README.md) · [Tap to Pay guide](taptopay/README.md) ·
[Sample app](example/README.md)

> [!IMPORTANT]
> **Notice:** This SDK is in beta and under active development. Its public interface can change in ways that
> aren't backward compatible, including the names, parameters and behavior of its types and methods.
> No stable version has been released. See [Versioning and support](#versioning-and-support).

## How it works

1. **Your backend** exchanges your Payabli client ID and client secret for a short-lived access token,
   through a token endpoint you build.
2. **Your app** starts one session with your entry point, the environment and a token provider that calls
   that endpoint.
3. **On that session**, your app takes a payment card-not-present with `PayabliPayIn`, or card-present
   with `PayabliTTP`.
4. **Every charge ends in an outcome** your app acts on: charged, not charged, or unknown and to be
   reconciled.

### Key terms

| Term | Meaning |
|---|---|
| **Paypoint** | A merchant account in Payabli. Payments are made to a paypoint. |
| **Entry point** | The identifier of the paypoint the SDK takes payments for, for example `acmePay`. Payabli generates it. In the Payabli portal, it's the **Entry Name** column under **Portfolio > Paypoints**, and the List paypoints endpoint returns it as `EntryName`. Use the paypoint's entry point, not your organization's. Your organization's credentials work for every paypoint under it. An entry point exists in one environment. |
| **Token endpoint** | A route on your own backend that exchanges your Payabli client ID and client secret for a short-lived access token and returns the token to your app. |
| **Authorized apps** | The apps a paypoint accepts Tap to Pay requests from. The Payabli portal lists them under **Authorized apps**. |

## Requirements

| | Requirement |
|---|---|
| `minSdk` | 23. Tap to Pay needs 30, and so does `com.payabli:sdk-android`, which includes it |
| `compileSdk` | 31 or higher. The card-not-present form's Compose dependencies require a higher `compileSdk` of their own, and Gradle names it if yours is lower |
| Tap to Pay | Android 12 on a 64-bit phone with NFC. See the [Tap to Pay guide](taptopay/README.md#requirements) |
| Building from source | JDK 17 or newer to run Gradle. The build runs on JDK 21 and downloads it when it isn't installed. |

## Installation

### Add the SDK

The SDK, and the card reader library Tap to Pay depends on, are served from Payabli's own repository,
which needs credentials that Payabli issues. Declare it in `settings.gradle.kts`, scoped to the groups it
serves, so Gradle asks it for nothing else:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://sdk.payabli.com/maven")
            content {
                includeGroup("com.payabli")
                includeGroup("com.fiserv.ch") // Tap to Pay
                includeGroup("com")           // Tap to Pay
            }
            credentials {
                username = providers.gradleProperty("payabli.maven.user").orNull
                password = providers.gradleProperty("payabli.maven.password").orNull
            }
            authentication { create<BasicAuthentication>("basic") }
        }
    }
}
```

Keep the credentials in `~/.gradle/gradle.properties` or your CI's secret store, never in the repository.

Then depend on the SDK at the latest version from the
[Releases](https://github.com/payabli/sdk-android/releases) page:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.payabli:sdk-android:<latest version>")
}
```

`sdk-android` includes card-not-present, Tap to Pay, and error and usage reporting, and needs `minSdk` 30.

To build changes that aren't released yet, build `main` from source with `./gradlew publishToMavenLocal`
and add `mavenLocal()` to your repositories.

#### Alternative declaration for solo features

To take one feature without the other, depend on its artifact instead of `sdk-android`:

| Artifact | Adds |
|---|---|
| `com.payabli:sdk-android-payin` | Card-not-present |
| `com.payabli:sdk-android-taptopay` | Tap to Pay |

```kotlin
dependencies {
    implementation("com.payabli:sdk-android-payin:<latest version>")
}
```

Each includes `com.payabli:sdk-android-core`, which holds the session and the configuration. To take both
at one version, use the bill of materials, `com.payabli:sdk-android-bom`, which sets the version and adds
no code:

```kotlin
dependencies {
    implementation(platform("com.payabli:sdk-android-bom:<latest version>"))
    implementation("com.payabli:sdk-android-payin")
    implementation("com.payabli:sdk-android-taptopay")
}
```

### Configure your app

The card-not-present and core artifacts don't declare the `INTERNET` permission. Unless your app includes
Tap to Pay, which brings it, add it to your app's manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

For Tap to Pay, add this to your **application** module's `build.gradle.kts`, so the card reader's native
library is unpacked at install. A library module can't set it for you:

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

The card reader library brings its own permissions, which Gradle merges into your app's manifest: `NFC`,
`INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`, `HIDE_OVERLAY_WINDOWS` and `ACCELEROMETER`. Your app
doesn't declare them. The [Tap to Pay guide](taptopay/README.md#before-you-start) covers the rest of its
setup.

## Set up the SDK

### Prepare your Payabli account

1. **Get a sandbox paypoint.** Ask your Payabli representative for a sandbox entry point, with Tap to Pay
   enabled if you plan to use it.
2. **Create OAuth2 credentials.** Provision a client ID and client secret for the sandbox. See
   [OAuth authentication](https://docs.payabli.com/developers/oauth-authentication).
   Give them the permissions in the table below.
3. **For Tap to Pay**, have your app enrolled and registered as one of the paypoint's authorized apps, as the
   [Tap to Pay guide](taptopay/README.md#before-you-start) describes.

Each operation needs its own permission on those credentials:

| Operation | Permission |
|---|---|
| Charge, authorize, or capture an authorization | `inboundpayments_create` |
| Void a transaction | `inboundpayments_void` |
| Read a transaction back | `inboundpayments_read` |
| Save a payment method | `tokens_create` |
| Initialize Tap to Pay on a phone | `tools_init` and `pos_create` |
| Activate a phone with its code | `pos_create` |
| Take a Tap to Pay payment | `inboundpayments_create` |
| Register an authorized app through the API | `pos_create` |

### Build your token endpoint

Your backend holds the client ID and client secret. It calls `POST /api/v2/token/serverside` with them
and returns the access token to your app. The client secret never reaches the device.

This example needs Node.js 18 or later and `"type": "module"` in `package.json`. It returns the token as
`{ "accessToken": "..." }`:

```js
// server.js
import express from "express";

const app = express();
const PAYABLI_URL = process.env.PAYABLI_URL ?? "https://api-sandbox.payabli.com/api";

app.post("/payabli/token", async (req, res) => {
  // authenticateUser is your app's own check of the caller's session. Never return a token without it.
  const user = await authenticateUser(req);
  if (!user) {
    return res.status(401).json({ error: "unauthenticated" });
  }
  // mayTakePayments is your app's own rule for who may take payments for this paypoint.
  if (!(await mayTakePayments(user))) {
    return res.status(403).json({ error: "forbidden" });
  }
  let accessToken;
  try {
    const upstream = await fetch(`${PAYABLI_URL}/v2/token/serverside`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        clientId: process.env.PAYABLI_CLIENT_ID,
        clientSecret: process.env.PAYABLI_CLIENT_SECRET,
      }),
      redirect: "error", // never replay the client secret to another origin
      signal: AbortSignal.timeout(10_000), // well inside the SDK's 30 seconds
    });
    const body = await upstream.json();
    accessToken = upstream.ok ? (body.access_token ?? body.accessToken) : undefined;
  } catch {
    // A timeout, a redirect, a network failure, or an answer that isn't JSON.
  }
  if (!accessToken) {
    return res.status(502).json({ error: "token exchange failed" });
  }
  res.set("Cache-Control", "no-store").json({ accessToken });
});

app.listen(process.env.PORT ?? 3000);
```

Anyone who can call the route gets a token that can charge, store payment methods and void for your
paypoint, so it has to authenticate the caller and check that they may take payments, the way the rest of
your app does. The sample app's development token server is in
[`example-server/`](example-server/README.md). It authenticates no caller, so run it only on your own
machine.

### Configure the SDK

Start the session once, before you build either way to pay. It takes your entry point, the environment and a
token provider, and both ways to pay run on it. `initialize` is a `suspend` function, as are the SDK's other
calls, so call them from a coroutine, for example in `viewModelScope.launch { }`:

```kotlin
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment

val config = PayabliConfig(
    entryPoint = "your-entry-point",
    environment = PayabliEnvironment.SANDBOX,
    tokenProvider = { backend.fetchPayabliAccessToken() }, // sends your app's own session credential
)

val session: PayabliSession = PayabliSession.initialize(config, HostBindings(applicationContext))
```

| Environment | API host |
|---|---|
| `PayabliEnvironment.SANDBOX` | `https://api-sandbox.payabli.com` |
| `PayabliEnvironment.PRODUCTION` | `https://api.payabli.com` |

- `PayabliConfig` throws when the entry point is blank.
- There is one session per app process.
- Calling `initialize` again with the same entry point, environment and `telemetryEnabled` returns the same
  session, which keeps its original token provider. A different one throws a `PayabliException` whose `type` is
  `INVALID_CONFIGURATION`, and the session already running stays in place. After authentication has failed for
  good, it builds a fresh session instead.

The token provider is a `PayabliTokenProvider`, a `suspend` function that returns a new access token from
your token endpoint:

- The SDK calls the provider before its first request, and again when a token is rejected. Return a newly
  minted token each time, not a cached one.
- Concurrent callers share one call.
- Each call has 30 seconds to return. A call that takes longer, throws, or returns a token the SDK can't use
  fails with `PayabliErrorType.TOKEN_PROVIDER_FAILED`.
- The SDK doesn't retry a `TOKEN_PROVIDER_FAILED` or `TOKEN_EXPIRED` failure itself. Try again only when someone
  using your app asks to, or wait longer before each attempt.
- Let cancellation through. Don't catch `CancellationException`.

### Device identity

`PayabliSession.deviceId` is this device's identity, the same for every capability. It is stable across
reinstalls; a factory reset gives it a new one, and on Android 8.0 and later so does a new app signing key. It
is `null` when the platform gives no device identifier.

## Take a payment

### Card-not-present

`PayabliPayIn` runs on the session `initialize` returned. Show its form, or call it from your own UI:

```kotlin
import com.payabli.sdk.payin.PayabliPayIn
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import java.math.BigDecimal

// In a ViewModel, so the instance survives a rotation.
val payIn = PayabliPayIn(session, entryPoint = "your-entry-point", scope = viewModelScope)

PayabliPayInForm(
    payIn = payIn,
    operation = PayabliPayInOperation.Capture(
        PayInTransactionOptions(PayInPaymentDetails(totalAmount = BigDecimal("12.34"))),
    ),
    configuration = PayInFormConfiguration(),
    onCompleted = { succeeded ->
        when (succeeded) { // store the ID; don't log it
            is PayInSubmissionState.Succeeded.Payment -> order.paymentTransId = succeeded.result.transaction?.paymentTransId
            is PayInSubmissionState.Succeeded.Method -> order.storedMethodId = succeeded.storedMethod.storedMethodId
        }
    },
    onFailed = { failed -> /* failed.cause says why; see Handle the outcome */ },
    onMethodChanged = { },
)
```

The [card-not-present guide](payin/README.md) covers the direct API, storing and charging a saved method,
authorizing and capturing, voiding, and the form's configuration and styling.

### Tap to Pay

`PayabliTTP` runs on the same session. After the one-time setup in the
[Tap to Pay guide](taptopay/README.md), a payment takes these calls:

```kotlin
import com.payabli.sdk.taptopay.PayabliTTP
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import java.math.BigDecimal

val ttp = PayabliTTP.create(session, applicationContext)
ttp.initialize()
val result = ttp.charge(
    paymentDetails = TapToPayPaymentDetails(BigDecimal("9.99")),
    customer = TapToPayCustomerData(firstName = "Jane", lastName = "Doe"),
)
order.paymentTransId = result.paymentTransId // store it; don't log it
```

The guide covers the phone and build requirements, enrollment, authorized apps, activating a phone, and the
session states.

## Handle the outcome

Every charge ends in one of these outcomes, whichever way it was taken. Only **not charged** is safe to
retry.

| Outcome | Card-not-present | Tap to Pay | Retry? |
|---|---|---|---|
| **Charged** | `Result.success` | `charge` returns a `TapToPayResult` | No |
| **Not charged** | a failure such as `PayInException.Refused`, a decline | a `TapToPayException` whose `capture` is `NOT_CHARGED` | Yes |
| **Unknown** | `PayInException.Unsettled`, or a cancellation of `capture` after it was called | a `TapToPayException` whose `capture` is `UNKNOWN` | Not until you've checked |
| **Charged, not confirmed** | — | a `TapToPayException` whose `capture` is `CHARGED` | No. Call `closeCapturedCharge` |

When the outcome is unknown, look the transaction up before you charge again. On card-not-present, call
`readTransaction(transId)` and check its `status`. From your backend, use
[`GET /api/MoneyIn/details/{transId}`](https://docs.payabli.com/developers/api-reference/moneyin/get-details-for-a-processed-transaction).
`PayInException.Unsettled` and `TapToPayException` carry the `paymentTransId` to
look up when there is one. When there isn't, find the transaction in the Payabli portal before you charge
again. On card-not-present, setting `orderId` on each request lets you find it by your own reference.
Store the transaction ID with your order every time you get one.

If you then try the same payment again, say so: pass `attempt = PayabliAttempt.Retry`, in
`PayInTransactionOptions` or to `charge`. The SDK sends it under the key of the payment whose outcome is
unknown: on card-not-present, the capture or authorization with the same `paymentReference`, which
`PayInException.Unsettled` carries and the SDK mints when you set none; on Tap to Pay, the most recent charge for
that entry point. The service then recognizes the repeat rather than taking the money twice. Without it, every
call is a new payment, and on card-not-present a new payment naming an unresolved `paymentReference` fails with
`PAYMENT_REFERENCE_OPEN` and isn't sent. If the service refuses the retry because it already has that payment, a card-not-present
retry fails with `RETRY_REFUSED` and a Tap to Pay one with `CHARGE_NOT_FINISHED`; check that payment before
trying again.

A retry describes the same payment: the same amount, service fee and currency, on card-not-present the same
surcharge and kind of payment method, and the same order ID or invoice number when the first attempt set one. A
retry that describes a different payment fails with `RETRY_NOT_SAME_PAYMENT` and isn't sent.

Each guide lists its errors in full: [card-not-present](payin/README.md#outcomes-and-errors) and
[Tap to Pay](taptopay/README.md#outcomes-and-errors).

## Go live

- A **production** entry point, and production OAuth2 credentials with the permissions in
  [Prepare your Payabli account](#prepare-your-payabli-account).
- `PayabliEnvironment.PRODUCTION` in `PayabliConfig`.
- Your token endpoint deployed and authenticating its callers.
- The checklists for what you use: [card-not-present](payin/README.md#go-live) and
  [Tap to Pay](taptopay/README.md#go-live).

## Reference

### Guides

| Guide | Covers |
|---|---|
| [Card-not-present](payin/README.md) | The form, the direct API, stored methods, authorize and capture, void, configuration and styling |
| [Tap to Pay](taptopay/README.md) | Phone and build requirements, enrollment, authorized apps, activation, states and errors |
| [Sample app](example/README.md) and [token server](example-server/README.md) | Running both ways to pay against your sandbox paypoint |
| [Payabli developer documentation](https://docs.payabli.com) | The API, OAuth, test accounts and the portal |

### Language support

The SDK is written in Kotlin. Its calls are `suspend` functions, so call them from a coroutine. The
card-not-present form, `PayabliPayInForm`, is a Jetpack Compose composable.

## Sample app and testing

[`example/`](example/README.md) is a Compose app that takes card-not-present and Tap to Pay payments
against your sandbox paypoint, and [`example-server/`](example-server/README.md) is its token server.
Their READMEs say how to configure and run both. In sandbox, use Payabli's
[test cards](https://docs.payabli.com/guides/test-accounts-reference).

## Privacy and data collection

When `sdk-android-telemetry` is in your app, which `sdk-android` includes, the SDK sends error and usage
events to Payabli, with no card, payer or account data. The events are not listed. For Google Play's
Data safety form, the SDK collects device or other IDs, app interactions, and crash logs and
diagnostics, and none of it is shared or used for tracking.

Every request the SDK sends to Payabli carries an `X-Pyb-Client` header with the SDK version, the platform, the OS
version, the device model, the locale and the device ID described under [Device identity](#device-identity).
A value that's missing, blank or isn't printable ASCII is left out. The header can't be turned off.

## Versioning and support

> [!IMPORTANT]
> **Notice:** The Payabli Android SDK is in beta. Until a stable version is released, expect changes to the public
> interface that aren't backward compatible, and read the release notes before you update.

- **Depend on the latest version** as [Installation](#installation) describes. Each version and its notes are
  on the [Releases](https://github.com/payabli/sdk-android/releases) page.
- **`main` holds changes that aren't released yet, and changes without notice.** A build from `main` takes
  whatever is there when it's built.
- **When a stable version is released,** this section says so.

## Support

- Payabli developer documentation: <https://docs.payabli.com>
- Support: support@payabli.com

## License

Commercial. See [LICENSE](LICENSE). Third-party components and their licenses are listed in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
