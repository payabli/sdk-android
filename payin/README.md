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
| `readTransaction` | `inboundpayments_read` |
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
import com.payabli.sdk.payin.PayInFormSuccess
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
    onCompleted = { succeeded ->
        when (succeeded) { // store the ID; don't log it
            is PayInFormSuccess.Payment -> order.paymentTransId = succeeded.result.transaction?.paymentTransId
            is PayInFormSuccess.Method -> order.storedMethodId = succeeded.storedMethod.storedMethodId
        }
    },
    onFailed = { error -> /* error.type says why it failed; see Outcomes and errors */ },
    onMethodChanged = { },
)
```

### Call the API from your own UI

Card numbers and security codes travel in `SensitiveDigits` buffers. Collect them into `CharArray`s, never
`String`s, which can't be erased. `SensitiveDigits.of` copies the array, so wipe yours once the buffer is
built, and close the `SensitiveDigits` once the call returns; `use` does that.

```kotlin
import com.payabli.sdk.payin.model.ExpiryValue
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

`authorize` holds an amount on a card, a stored card or a cloud device without charging it. The form authorizes
a card only. `captureAuthorizedTransaction` captures that
authorization later, and `voidTransaction` releases it or voids a transaction that hasn't settled.

### Retry safely

A charge always sends an idempotency key, which the SDK mints and holds; you don't set one. Name each payment
with `PayInTransactionOptions.paymentReference`, or let the SDK name it. A charge whose outcome is unknown fails
with `PayInException.Unsettled`, which carries that reference. Find the transaction first where you can. To try
the same payment again, submit it with the same `paymentReference` and `attempt = PayabliAttempt.Retry`: the SDK
resends that payment's key, so the service recognizes the repeat. If the SDK holds no key for that reference, or
the service would no longer recognize it, the retry goes out under a new key. Calling again without declaring a
retry is a new payment, and naming a reference whose outcome is still unknown fails with `PAYMENT_REFERENCE_OPEN`
and isn't sent. So does the same call on a payment another `PayabliPayIn` is still sending, a declared retry
included, and a capture or void of a transaction another one is still capturing or voiding. The held key lives in
memory only, so after a restart read the transaction back.

If the service refuses a declared retry, it arrives as `PayInException.Unsettled` with type `RETRY_REFUSED`: the
earlier attempt reached the service, and its outcome is still unknown. Check that payment before trying again.

A retry describes the same payment: the same amount, service fee, surcharge, currency and kind of payment method,
and the same `orderId` when the first attempt set one. A retry that describes a different payment fails with
`RETRY_NOT_SAME_PAYMENT` and isn't sent, and the earlier payment's key is kept for the retry that matches.

## Outcomes and errors

Every call returns a `Result`, except when it is cancelled: cancellation is rethrown as
`CancellationException`. A cancelled `storeMethod` may already have saved the method, so read the stored
methods back before storing again. A form reports through `onCompleted` and `onFailed`: a success arrives as
a `PayInFormSuccess`, and a failure as the exception the table below matches.

A success means what the call did, which depends on the call:

| Call | A success means |
|---|---|
| `capture` | The payment was charged |
| `authorize` | An amount is held on the card. Nothing is charged until you capture it |
| `captureAuthorizedTransaction` | The held amount was charged |
| `voidTransaction` | The transaction was voided. A refused void doesn't mean the original payment wasn't charged |
| `storeMethod` | The method was saved |

The outcomes are the ones in the root README's [Handle the outcome](../README.md#handle-the-outcome). For
`authorize`, `captureAuthorizedTransaction` and `voidTransaction`, read "charged" as "held", "captured"
or "voided". Check for `PayInException.Unsettled` before you branch on `type` or `category`: an unsettled failure
keeps its cause's type and category, such as `TOKEN_EXPIRED`, so the category alone never says the payment may
have gone through.

| Result | Outcome | What to do |
|---|---|---|
| `Result.success` | Charged | Store the transaction ID |
| `PayInException.Refused`, for example a decline | Not charged | You can retry |
| `PayInException.InvalidInput` | Not charged; the request was refused before it was sent | Fix the named field |
| `PayInException.Unsettled`, or a cancellation of `capture`, `authorize`, `captureAuthorizedTransaction` or `voidTransaction` after it was called | Unknown | Read the transaction back with `readTransaction` before charging again: by `Unsettled.paymentTransId` when there is one, otherwise find it in the Payabli portal by the `orderId` you set on the request, so set and keep one on each request. After `captureAuthorizedTransaction` or `voidTransaction`, read back the transaction ID you passed. |
| `PayInException.AlreadySubmitting` | Not charged; a submission is already running | Wait for it |
| `PayInException.ServiceError`, `PayInException.Undecodable`, on `storeMethod` | The service answered with an error, or its answer couldn't be read | Read the stored methods back before storing again. On a charge, these arrive as `Unsettled` |
| A core `PayabliException`, such as a refused credential, a rate limit or `TOKEN_PROVIDER_FAILED` | On a charge, not charged: an unknown outcome arrives as `Unsettled` instead. On `storeMethod`, a network failure may have saved the method | Branch on its `type`, or its `category` for the remedy. After a network failure on `storeMethod`, read the stored methods back before storing again |
| `PayInException.Interrupted` (form only) | On a charge, cancelled before anything was sent. On `StoreMethod`, the method may have been saved | Retry a charge. Read the stored methods back before storing again |

When a form submission fails, `onFailed` receives the exception, and whether it is a
`PayInException.Unsettled` is what says a payment may be outstanding. Check for that type before anything
else: `Unsettled` carries its cause's `type`, so the exception's `type` says why the payment failed but
never whether one may be outstanding. The form marks the fields the service rejected.

`PayInException.Refused` and `PayInException.ServiceError` carry `failure`, a `PayInFailure` with the service's
`code`, `reason`, `explanation` and `action`, and `paymentTransId`, the transaction it belongs to when the
service named one. Reconcile from either. Show `reason`, `explanation` and `action`, but don't log them: they
can repeat what was submitted.

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
| `authorize(request)` | Authorizes a card, a stored card or a cloud device |
| `captureAuthorizedTransaction(request)` | Captures an earlier authorization |
| `voidTransaction(transId)` | Voids a transaction that hasn't settled |
| `readTransaction(transId)` | Reads a transaction back and returns its `status`. A failure of type `TRANSACTION_NOT_FOUND` means nothing is visible under that ID yet, which isn't proof no payment was made: read again later rather than charging again |
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

`defaultCardSections()` and `defaultBankSections()` return the instrument's fields followed by a summary
section of the amounts, titled "Payment". An inputs section with no title draws no heading. A summary section lists only `Amount`, `ServiceFee` and
`SurchargeFee`. Any other field there is refused when the configuration is built.

`PayabliPayInForm` takes `labels`, a `PayInFormLabels` for the title, subtitle, submit button, the total row's
label (`total`), field labels and placeholders.
With no `submitButton`, the button names the operation: "Pay" for a capture, "Authorize" for an
authorization and "Save" for a stored method, with "Paying…", "Authorizing…" and "Saving…" while it runs.

`PayInSummaryRows` gives each summary row as the form draws it, for an app that draws its own:
`labelText` and `totalLabelText` for the labels, `rowAmount` and `totalRowAmount` for the figures, and
`formattedAmount` for a figure as text. A `null` figure means the row isn't drawn. `rowAmount` returns the
Amount figure whatever `showsBaseAmount` is set to, so check that setting before drawing the Amount row.

### Styling

The form takes its colors, type and shapes from your app's `MaterialTheme`, so light, dark and dynamic
color apply with nothing passed. `PayabliPayInFormDefaults.style(PayInFormStyleOverrides(...))` changes
single values, and `style` on the form or `LocalPayInFormStyle` applies a `PayInFormStyle` to one form or
to every form in a tree.

### Accessibility

- Each field is named for a screen reader when its visible label is hidden or drawn above it.
- A field's error is reported on the control itself.
- The reveal and hide control, the expiry picker and the card-brand mark carry labels.
- The security code is masked, and so is the account number while `masksAccountNumber` is on, which is the
  default.
- Secret fields open a number-password keyboard.
- The summary rows are read as one item.
- Expiry picker rows are at least 48dp, with radio roles.

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
