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
2. **Your app** creates one `PayabliSession` with your entry point, the environment and a token provider
   that calls that endpoint.
3. **On that session**, your app takes a payment card-not-present with `PayabliPayIn`, or card-present
   with `PayabliTTP`.
4. **Every charge ends in an outcome** your app acts on: charged, not charged, or unknown and to be
   reconciled.

### Key terms

| Term | Meaning |
|---|---|
| **Paypoint** | A merchant account in Payabli. Payments are made to a paypoint. |
| **Entry point** | The identifier of a paypoint, for example `acmePay`. You pass it to the SDK. Payabli gives it to you. |
| **Token endpoint** | A route on your own backend that exchanges your Payabli client ID and client secret for a short-lived access token and returns the token to your app. |
| **Authorized apps** | The apps a paypoint accepts Tap to Pay requests from. The Payabli portal lists them under **Authorized apps**. |

## Requirements

| | Requirement |
|---|---|
| `minSdk` | 23. Tap to Pay needs 30, and so does `com.payabli:sdk-android`, which includes it |
| `compileSdk` | 31 or higher. The card-not-present form's Compose dependencies require a higher `compileSdk` of their own, and Gradle names it if yours is lower |
| Tap to Pay | Android 12 on a 64-bit phone with NFC. See the [Tap to Pay guide](taptopay/README.md#requirements) |

## Installation

### Add the SDK

Build the SDK from source into your local Maven repository:

```bash
git clone https://github.com/payabli/sdk-android.git
cd sdk-android
./gradlew publishToMavenLocal                                    # everything
./gradlew :core:publishToMavenLocal :payin:publishToMavenLocal  # card-not-present only
```

The build needs the Android SDK, through `ANDROID_HOME` or `sdk.dir` in `local.properties`. Building Tap
to Pay also needs the [card reader repository](#card-reader-repository) credentials.

Then add `mavenLocal()` to your app's repositories and depend on what you use. The version is the one the
build published, which `payabli.version` in the clone's [`gradle.properties`](gradle.properties) sets:

| Artifact | Adds |
|---|---|
| `com.payabli:sdk-android-payin` | Card-not-present |
| `com.payabli:sdk-android-taptopay` | Tap to Pay |
| `com.payabli:sdk-android` | Both, with error and usage reporting |

Each library includes `com.payabli:sdk-android-core`, which holds the session and the configuration.

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    val payabliVersion = "<payabli.version>" // from the clone's gradle.properties
    implementation("com.payabli:sdk-android-payin:$payabliVersion")
    implementation("com.payabli:sdk-android-taptopay:$payabliVersion")
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

An app built without it installs and runs, and is refused when it is submitted for enrolment.

The card reader library brings its own permissions, which Gradle merges into your app's manifest: `NFC`,
`INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`, `HIDE_OVERLAY_WINDOWS` and `ACCELEROMETER`. Your app
doesn't declare them. The [Tap to Pay guide](taptopay/README.md#before-you-start) covers the rest of its
setup.

### Card reader repository

Tap to Pay depends on a card reader library served from Payabli's own repository, which needs
credentials that Payabli issues. Declare it scoped to the two groups it serves, so Gradle asks it for
nothing else:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://sdk.payabli.com/maven")
            content {
                includeGroup("com.fiserv.ch")
                includeGroup("com")
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
| Save a payment method | `tokens_create` |
| Set up a phone for Tap to Pay | `tools_init` and `pos_create` |
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

Create the session once. `initialize` is a `suspend` function, as are the SDK's other calls, so call them
from a coroutine, for example in `viewModelScope.launch { }`:

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

val session: PayabliSession =
    PayabliSession.initialize(config, HostBindings(applicationContext)).getOrThrow()
```

| Environment | API host |
|---|---|
| `PayabliEnvironment.SANDBOX` | `https://api-sandbox.payabli.com` |
| `PayabliEnvironment.PRODUCTION` | `https://api.payabli.com` |

- `PayabliConfig` throws when the entry point is blank.
- There is one session per app process, for one paypoint. Calling `initialize` again with the same entry
  point, environment and `telemetryEnabled` returns the same session, which keeps its original token
  provider. Calling it with a different entry point, environment or `telemetryEnabled` while the session is
  live fails.

The token provider is a `PayabliTokenProvider`, a `suspend` function that returns a new access token from
your token endpoint:

- The SDK calls it before its first request, and again when a token is rejected. Return a newly minted
  token each time, not a cached one.
- Each call has 30 seconds to return a token that isn't blank. A call that takes longer or throws fails
  with `PayabliErrorCode.TOKEN_PROVIDER_FAILED`.
- Let cancellation through. Don't catch `CancellationException`.

## Take a payment

### Card-not-present

`PayabliPayIn` runs on the session. Show its form, or call it from your own UI:

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
[Tap to Pay guide](taptopay/README.md), a payment takes three calls:

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

The guide covers the phone and build requirements, enrolment, authorized apps, activating a phone, and the
session states.

## Handle the outcome

Every charge ends in one of these outcomes, whichever way it was taken. Only **not charged** is safe to
retry.

| Outcome | Card-not-present | Tap to Pay | Retry? |
|---|---|---|---|
| **Charged** | `Result.success`, except a capture with `isAsync = true`, which is **unknown** | `charge` returns a `TapToPayResult` | No |
| **Not charged** | a failure such as `PayInException.Refused`, a decline | a `TapToPayException` whose `capture` is `NOT_CHARGED` | Yes |
| **Unknown** | `PayInException.Unsettled`, or a cancellation of `capture` after it was called | a `TapToPayException` whose `capture` is `UNKNOWN` | Not until you've checked |
| **Charged, not confirmed** | — | a `TapToPayException` whose `capture` is `CHARGED` | No. Call `closeCapturedCharge` |

When the outcome is unknown, look the transaction up from your backend with
[`GET /api/MoneyIn/details/{transId}`](https://docs.payabli.com/developers/api-reference/moneyin/get-details-for-a-processed-transaction)
before you charge again. `PayInException.Unsettled` and `TapToPayException` carry the `paymentTransId` to
look up when there is one. When there isn't, find the transaction in the Payabli portal before you charge
again. On card-not-present, setting `orderId` on each request lets you find it by your own reference.
Store the transaction ID with your order every time you get one.

A card-not-present capture sent with `isAsync = true` returns once the service accepts it, before the
processor answers. Treat its success as unknown, and look the transaction up before you fulfill the order.

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
| [Tap to Pay](taptopay/README.md) | Phone and build requirements, enrolment, authorized apps, activation, states and errors |
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
events to Payabli. Set `telemetryEnabled = false` in `PayabliConfig` to turn it off; the SDK then queues
and sends nothing.

## Versioning and support

> [!IMPORTANT]
> **Notice:** The Payabli Android SDK is in beta. Until a stable version is released, expect changes to the public
> interface that aren't backward compatible, and read the changes on `main` before you update.

- **No version has been released.** Build from `main` as [Installation](#installation) describes.
- **`main` changes without notice.** Record the commit you built, and build the same commit to reproduce a
  build.
- **When a stable version is released,** this section gives the version to depend on.

## Support

- Payabli developer documentation: <https://docs.payabli.com>
- Support: support@payabli.com

## License

Commercial. See [LICENSE](LICENSE). Third-party components and their licenses are listed in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
