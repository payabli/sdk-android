# Card-not-present payments on Android

Take a card or bank account payment that the payer enters, in the SDK's Compose form or in your own UI.
This guide is part of the [Payabli Android SDK](../README.md); set up the SDK and its session there first.

> [!IMPORTANT]
> **Notice:** This SDK is in beta. Its public interface can change in ways that aren't backward compatible. See
> [Versioning and support](../README.md#versioning-and-support).

## Requirements

### Your app

- `minSdk` 23 or higher, and the `compileSdk` the root README's [Requirements](../README.md#requirements)
  give.
- Jetpack Compose, to use the SDK's form.

### Your account

OAuth2 credentials with a permission for each operation you use:

| Operation | Permission |
|---|---|
| `capture`, `authorize`, `captureAuthorizedTransaction` | `inboundpayments_create` |
| `voidTransaction` | `inboundpayments_void` |
| `storeMethod` | `tokens_create` |

## Before you start

### Choose the form or your own UI

- **The SDK's form** collects the details and builds the card data itself, so your code never handles a
  card number.
- **Your own UI** passes card data you collected to the direct API. Your app then handles card numbers and
  security codes, which brings it into scope for PCI DSS.

## Set up

`PayabliPayIn` runs on the SDK's session:

```kotlin
import com.payabli.sdk.payin.PayabliPayIn

val payIn = PayabliPayIn(session, entryPoint = "your-entry-point", scope = viewModelScope)
```

Create `PayabliPayIn` in a `ViewModel` and pass its `viewModelScope`, so the instance, and a submission's
outcome, survive a rotation. A form collects only the instance it was given, so an instance recreated with
the activity misses the outcome. Every call is a `suspend` function.

## Take a payment

### Use the SDK's form

`PayabliPayInForm` is a Composable card and bank account form. `operation` says what a submission does:

```kotlin
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import java.math.BigDecimal

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
    onFailed = { failed -> /* failed.cause says why; see Outcomes and errors */ },
    onMethodChanged = { },
)
```

### Call the API from your own UI

Card numbers and security codes travel in `SensitiveDigits` buffers. Collect them into `CharArray`s, never
`String`s, which can't be erased. `SensitiveDigits.of` copies the array, so wipe yours once the buffer is
built, and close the `SensitiveDigits` once the call returns; `use` does that.

```kotlin
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.model.SensitiveDigits
import java.math.BigDecimal

// numberChars and cvvChars are CharArrays your UI filled.
val number = SensitiveDigits.of(numberChars).also { numberChars.fill('0') }
val cvv = SensitiveDigits.of(cvvChars).also { cvvChars.fill('0') }

val result =
    number.use { number ->
        cvv.use { cvv ->
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

result.onSuccess { order.paymentTransId = it.transaction?.paymentTransId } // store it; don't log it
```

In sandbox, use Payabli's [test cards](https://docs.payabli.com/guides/test-accounts-reference).

### Store a payment method and charge it later

`storeMethod` saves a card or bank account and returns its stored ID. The form does the same with
`PayabliPayInOperation.StoreMethod()`. To charge a saved method, pass
`PayInPaymentMethod.Stored(PayInStoredMethodType.Card, storedMethodId)` as the payment method.

### Authorize, then capture

`authorize` holds an amount on a card without charging it. `captureAuthorizedTransaction` captures that
authorization later, and `voidTransaction` releases it or voids a transaction that hasn't settled.

### Retry safely

A charge always sends an idempotency key. The SDK mints one per call when you don't set
`PayInTransactionOptions.idempotencyKey`, so calling again without your own key is a second payment, not a
retry. Don't resend a charge whose outcome is unknown. Find the transaction first.

## Outcomes and errors

Every call returns a `Result`, except when it is cancelled: cancellation is rethrown as
`CancellationException`. A cancelled `storeMethod` may already have saved the method, so read the stored
methods back before storing again. A form reports through `onCompleted` and `onFailed`, whose argument's `cause` is
the exception.

A success means what the call did, which depends on the call:

| Call | A success means | A failure means |
|---|---|---|
| `capture` | The payment was charged. With `isAsync = true`, only that the service accepted it: look the transaction up before you fulfill the order | See the table below |
| `authorize` | An amount is held on the card. Nothing is charged until you capture it | See the table below |
| `captureAuthorizedTransaction` | The held amount was charged | See the table below |
| `voidTransaction` | The transaction was voided | See the table below. A refused void doesn't mean the original payment wasn't charged |
| `storeMethod` | The method was saved | See the table below |

The outcomes are the ones in the root README's [Handle the outcome](../README.md#handle-the-outcome). For
`authorize`, `captureAuthorizedTransaction` and `voidTransaction`, read "charged" as "held", "captured"
or "voided":

| Result | Outcome | What to do |
|---|---|---|
| `Result.success` | Charged, except an `isAsync` capture, which is accepted and not yet known | Store the transaction ID; look an async capture up before fulfilling |
| `PayInException.Refused`, for example a decline | Not charged | You can retry |
| `PayInException.InvalidInput` | Not charged; the request was refused before it was sent | Fix the named field |
| `PayInException.Unsettled`, or a cancellation of any call but `storeMethod` after it was called | Unknown | Look the transaction up before charging again. `Unsettled.paymentTransId` names it when there is one |
| `PayInException.AlreadySubmitting` | Not charged; a submission is already running | Wait for it |
| `PayInException.ServiceError`, `PayInException.Undecodable`, on `storeMethod` | The service answered with an error, or its answer couldn't be read | Read the stored methods back before storing again. On a charge, these arrive as `Unsettled` |
| A core `PayabliException`, such as a refused credential, a rate limit or `TOKEN_PROVIDER_FAILED` | On a charge, not charged: an unknown outcome arrives as `Unsettled` instead. On `storeMethod`, a network failure may have saved the method | Branch on its `type`, or its `category` for the remedy. After a network failure on `storeMethod`, read the stored methods back before storing again |
| `PayInException.Interrupted` (form only) | On a charge, cancelled before anything was sent. On `StoreMethod`, the method may have been saved | Retry a charge. Read the stored methods back before storing again |

## Reference

### Operations

| Operation | What a form submission does |
|---|---|
| `PayabliPayInOperation.StoreMethod()` | Saves the card or bank account as a stored payment method |
| `PayabliPayInOperation.Capture(options)` | Charges the payment method |
| `PayabliPayInOperation.Authorize(options)` | Authorizes a card without capturing it |

### Methods

| Method | What it does |
|---|---|
| `capture(request)` | Charges a card, bank account, stored payment method, cloud device, check or cash |
| `authorize(request)` | Authorizes a card |
| `captureAuthorizedTransaction(request)` | Captures an earlier authorization |
| `voidTransaction(transId)` | Voids a transaction that hasn't settled |
| `storeMethod(request)` | Saves a payment method and returns its stored ID |

### Form configuration

`PayInFormConfiguration` chooses what the form shows:

| Parameter | Sets |
|---|---|
| `allowedMethods`, `defaultMethod` | Card, bank account, or both, and which opens first |
| `cardSections`, `bankSections` | The fields in each section, and their order |
| `requiredFields` | Fields the payer must fill |
| `labelLayout`, `hiddenFieldLabels` | Where labels sit, and which are hidden |
| `formatting` | How entered values are formatted |
| `showsBaseAmount` | Whether the amount row shows |

`labels` takes a `PayInFormLabels` for the title, subtitle, submit button, field labels and placeholders.

### Styling

The form takes its colors, type and shapes from your app's `MaterialTheme`, so light, dark and dynamic
color apply with nothing passed. `PayabliPayInFormDefaults.style(PayInFormStyleOverrides(...))` changes
single values, and `style` on the form or `LocalPayInFormStyle` applies a `PayInFormStyle` to one form or
to every form in a tree.

## Go live

- `PayabliEnvironment.PRODUCTION` and a production entry point, with credentials carrying the permissions
  in [Requirements](#requirements).
- Production card details only. The sandbox test cards don't work in production.
- The transaction ID stored with every order, so any outcome can be reconciled.

## Related docs

- [Payabli Android SDK](../README.md): setup, the token endpoint, outcomes and go-live
- [Tap to Pay on Android](../taptopay/README.md)
- [Sample app](../example/README.md): `demo/simple/SimpleCaptureScreen.kt` is the smallest capture
- [Pay In API reference](https://docs.payabli.com/developers/api-reference/moneyin/get-details-for-a-processed-transaction)
