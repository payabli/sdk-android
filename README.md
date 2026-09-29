# Payabli Android SDK

The Payabli Android SDK lets an Android app take payments through Payabli in two ways:

- **Card-not-present.** Your app collects card or bank account details, in the SDK's Compose form or in
  your own UI, and the SDK stores them as a payment method or charges them. This is the `payin` module.
- **Tap to Pay.** The payer taps a contactless card, phone or watch on the Android phone, with no external
  reader. This is the `taptopay` module, documented in [`taptopay/README.md`](taptopay/README.md).

Your app never holds a Payabli credential. It supplies a function that fetches a short-lived access
token from your backend, and the SDK calls it when it needs one.

## Terms used in this guide

| Term | Meaning |
|---|---|
| **Paypoint** | A merchant account in Payabli. Payments are made to a paypoint. |
| **Entry point** | The identifier of a paypoint, for example `acmePay`. You pass it to the SDK. Payabli gives it to you. |
| **Token endpoint** | A route on your own backend that exchanges your Payabli client ID and client secret for a short-lived access token and returns the token to your app. |
| **Allowlist** | The list of apps a paypoint accepts Tap to Pay requests from. |

## Requirements

| Module | `minSdk` |
|---|---|
| `core`, `payin`, `telemetry` | 23 |
| `taptopay` | 30 |

Compile your app with `compileSdk` 31 or higher. The `payin` module's Compose dependencies require a
higher `compileSdk` of their own, and Gradle names it if yours is lower. Tap to Pay has further
requirements, listed in [`taptopay/README.md`](taptopay/README.md#requirements).

## Before you write code

1. **Get a sandbox paypoint.** Ask your Payabli representative for a sandbox entry point, with Tap to Pay
   enabled if you plan to use it.
2. **Create OAuth2 credentials.** Provision a client ID and client secret for the sandbox. See
   [OAuth authentication](https://docs.payabli.com/developers/oauth-authentication). Tap to Pay needs the
   `tools_init`, `pos_create` and `inboundpayments_create` permissions. Card-not-present needs permission to create
   transactions and to store payment methods; confirm the permission names with your Payabli representative.
3. **Build your token endpoint.** See [Build your token endpoint](#build-your-token-endpoint).
4. **Declare the `INTERNET` permission.** The SDK doesn't declare it. Add it to your app's manifest:

   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   ```

5. **For Tap to Pay only:** register your app on the paypoint's allowlist and have its package and
   signing certificate enrolled. See [`taptopay/README.md`](taptopay/README.md#before-you-write-code).

## Install

**No version of the SDK has been published yet.** When one is, it is published under these coordinates:

| Artifact | Contains |
|---|---|
| `com.payabli:sdk-android-core` | Configuration, the session and the token provider |
| `com.payabli:sdk-android-payin` | Card-not-present |
| `com.payabli:sdk-android-taptopay` | Tap to Pay |
| `com.payabli:sdk-android-telemetry` | Optional error and usage reporting |
| `com.payabli:sdk-android` | All four |
| `com.payabli:sdk-android-bom` | A bill of materials that pins the versions above |

This section gives the repository and the version once a release exists. Until then, you can build the SDK
from source into your local Maven repository:

```bash
git clone https://github.com/payabli/sdk-android.git
cd sdk-android
./gradlew publishToMavenLocal
```

Then add `mavenLocal()` to your repositories and depend on the version the build published, which is set by
`payabli.version` in [`gradle.properties`](gradle.properties):

```kotlin
dependencies {
    implementation("com.payabli:sdk-android-payin:0.1.0")
    implementation("com.payabli:sdk-android-taptopay:0.1.0") // for Tap to Pay
}
```

Building `taptopay` needs the card reader repository credentials described below, in
`~/.gradle/gradle.properties`.

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

## Build your token endpoint

Your backend holds the client ID and client secret. It calls `POST /api/v2/token/serverside` with them
and returns the access token to your app. The client secret never reaches the device.

This Node.js and Express example returns the token as `{ "accessToken": "..." }`:

```js
// server.js
import express from "express";

const app = express();
const PAYABLI_URL = process.env.PAYABLI_URL ?? "https://api-sandbox.payabli.com/api";

app.post("/payabli/token", async (req, res) => {
  // Authenticate your own user here before returning a token.
  const upstream = await fetch(`${PAYABLI_URL}/v2/token/serverside`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      clientId: process.env.PAYABLI_CLIENT_ID,
      clientSecret: process.env.PAYABLI_CLIENT_SECRET,
    }),
  });
  const body = await upstream.json();
  const accessToken = body.access_token ?? body.accessToken;
  if (!upstream.ok || !accessToken) {
    return res.status(502).json({ error: "token exchange failed" });
  }
  res.json({ accessToken });
});

app.listen(process.env.PORT ?? 3000);
```

Protect the route with the same authentication your app already uses. Anyone who can call it gets a
token for your paypoint.

The sample app ships a complete token server in [`example-server/`](example-server/README.md).

## Configure the SDK

Both modules run on one `PayabliSession`, built from your entry point, the environment and a token
provider. `initialize` is a `suspend` function, as are the SDK calls below, so call them from a coroutine,
for example in `viewModelScope.launch { }`:

```kotlin
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment

val config = PayabliConfig(
    entryPoint = "your-entry-point",
    environment = PayabliEnvironment.SANDBOX,
    tokenProvider = { backend.fetchPayabliAccessToken() },
)

val session: PayabliSession =
    PayabliSession.initialize(config, HostBindings(applicationContext)).getOrThrow()
```

| Environment | API host |
|---|---|
| `PayabliEnvironment.SANDBOX` | `https://api-sandbox.payabli.com` |
| `PayabliEnvironment.PRODUCTION` | `https://api.payabli.com` |

- `PayabliConfig` throws when the entry point is blank.
- There is one session per app process. Calling `initialize` again with the same configuration returns
  the same session. Calling it with a different configuration while the session is live fails.

The token provider is a `PayabliTokenProvider`, a `suspend` function that returns a new access token
from your token endpoint:

- The SDK calls it before its first request, and again when a token is rejected. Return a newly minted
  token each time, not a cached one.
- Each call has 30 seconds to return a token that isn't blank. A call that takes longer or throws fails
  with `PayabliErrorCode.TOKEN_PROVIDER_FAILED`.
- Let cancellation through. Don't catch `CancellationException`.

## Card-not-present payments

```kotlin
import com.payabli.sdk.payin.PayabliPayIn

val payIn = PayabliPayIn(session, entryPoint = "your-entry-point", scope = viewModelScope)
```

Pass a scope that outlives a configuration change, such as `viewModelScope`, so a submission's outcome
still arrives after a rotation.

### Use the SDK's form

`PayabliPayInForm` is a Composable card and bank account form. `operation` says what a submission does:

```kotlin
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import java.math.BigDecimal

PayabliPayInForm(
    payIn = payIn,
    operation = PayabliPayInOperation.Capture(
        PayInTransactionOptions(PayInPaymentDetails(totalAmount = BigDecimal("12.34"))),
    ),
    configuration = PayInFormConfiguration(),
    onCompleted = { succeeded -> /* charged, or saved */ },
    onFailed = { failed -> /* failed.cause says why; see Outcomes */ },
    onMethodChanged = { },
)
```

| Operation | What happens |
|---|---|
| `PayabliPayInOperation.StoreMethod()` | Saves the card or bank account as a stored payment method. |
| `PayabliPayInOperation.Capture(options)` | Charges the payment method. |
| `PayabliPayInOperation.Authorize(options)` | Authorizes a card without capturing it. |

`PayInFormConfiguration` chooses the payment methods and fields, `labels` sets the wording, and `style`
sets the look.

### Call the API from your own UI

Card numbers and security codes travel in `SensitiveDigits` buffers. Close the ones you build once the
call returns; `use` does that.

```kotlin
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInTransactionOptions
import java.math.BigDecimal
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.SensitiveDigits

val result =
    SensitiveDigits.ofString("4012000098765439").use { number ->
        SensitiveDigits.ofString("999").use { cvv ->
            payIn.capture(
                PayInRequest(
                    paymentMethod = PayInPaymentMethod.Card(
                        PayInCardData(number, ExpiryValue(12, 2030), cvv, "Jane Doe", "12345"),
                    ),
                    options = PayInTransactionOptions(PayInPaymentDetails(totalAmount = BigDecimal("12.34"))),
                ),
            )
        }
    }

result.onSuccess { println("Charged: ${it.transaction?.paymentTransId}") }
```

Use Payabli's sandbox [test cards](https://docs.payabli.com/guides/test-accounts-reference) in sandbox.

| Method | What it does |
|---|---|
| `capture(request)` | Charges a card, bank account or stored payment method. |
| `authorize(request)` | Authorizes a card. |
| `captureAuthorizedTransaction(request)` | Captures an earlier authorization. |
| `voidTransaction(transId)` | Voids a transaction that hasn't settled. |
| `storeMethod(request)` | Saves a payment method and returns its stored ID. |

Every method returns a `Result`. To charge a saved method, pass
`PayInPaymentMethod.Stored(PayInStoredMethodType.Card, storedMethodId)` as the payment method.

`capture` always sends an idempotency key. The SDK mints one per call when you don't set
`PayInTransactionOptions.idempotencyKey`, so calling again without your own key is a second payment, not a
retry. Set your own key to retry a payment safely.

## Tap to Pay payments

See [`taptopay/README.md`](taptopay/README.md). It covers the build settings, the allowlist, device
activation, charging and errors.

## Outcomes

Every charge ends in one of three outcomes. Only one of them is safe to retry.

| Outcome | Card-not-present | Tap to Pay | Retry? |
|---|---|---|---|
| **Charged** | `Result.success` | `charge` returns a `TapToPayResult` | No |
| **Not charged** | a failure such as `PayInException.Refused`, a decline | a `TapToPayException` whose `capture` is `NOT_CHARGED` | Yes |
| **Unknown** | `PayInException.Unsettled` | a `TapToPayException` whose `capture` is `UNKNOWN` | Not until you've checked |
| **Charged, not confirmed** | — | a `TapToPayException` whose `capture` is `CHARGED` | No. Call `closeCapturedCharge` |

On card-not-present, a form reports these through `onFailed`, whose argument's `cause` is the exception.

When the outcome is unknown, look the transaction up from your backend with
[`GET /api/MoneyIn/details/{transId}`](https://docs.payabli.com/developers/api-reference/moneyin/get-details-for-a-processed-transaction)
before you charge again. `PayInException.Unsettled` and `TapToPayException` carry the `paymentTransId` to
look up when there is one. When there isn't, find the transaction in the Payabli portal before you charge
again. Store the transaction ID with your order every time you get one.

## Sample app

[`example/`](example/README.md) is a Compose app that runs card-not-present and Tap to Pay against your
sandbox paypoint. [`example-server/`](example-server/README.md) is its token server. Their READMEs say
how to configure and run both.

## Support

- Payabli developer documentation: <https://docs.payabli.com>
- Support: support@payabli.com

## License

Commercial. See [LICENSE](LICENSE). Third-party components and their licenses are listed in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
