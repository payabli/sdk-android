# Payabli SDK sample app

What integrating the Payabli Android SDK looks like.

| Screen | What it lets you do |
|---|---|
| **Payment method** | Store a card or bank account and get a reusable token back, inline and in a bottom sheet. |
| **Capture** | Charge a card or bank account, and read the whole transaction response. |
| **Tap to pay** | See whether this device can take a contactless payment and why not. Turn the terminal on, restart the session, charge, activate the device, and watch the session state and the sample's own log of what it did. |
| **Setup** | Read back every value the SDK was configured with, and check the token endpoint is reachable. |

## Running it

```bash
./gradlew :example:installDebug
adb shell am start -n com.payabli.example.app/.MainActivity
```

### Settings

Copy `secrets.properties.example` to `secrets.properties` and fill it in. It is gitignored and holds no
credential; the token is minted at runtime by `example-server/`. Any setting can be passed for a single
run instead: `-Ppayabli.demo.entryPoint=entry0000`.

**Sources, most specific first:** the `-P` flag, then an environment variable, then
`secrets.properties`. The variable is the setting uppercased with dots as underscores, so
`payabli.demo.entryPoint` is `PAYABLI_DEMO_ENTRYPOINT`. That is the one to use from a shell you have already
exported into, or from CI, where there is no file to edit:

```bash
PAYABLI_DEMO_ENTRYPOINT=entry0000 ./gradlew :example:installWithTelemetryDebug
```

The default is what the build falls back to when nothing is set. The template prefills two of them,
and only `payabli.demo.appId` prefills something other than its build default.

| Setting | Default | Notes |
|---|---|---|
| `payabli.demo.entryPoint` | | The entry point of the paypoint the sample app takes payments for, not the organization's. Exists in one environment, so set it with the row below. |
| `payabli.demo.environment` | `sandbox` | Which environment this build talks to, from the row below. |
| `payabli.demo.extraEnvironments` | | Environments the picker offers beyond sandbox and production. A name the SDK was not built with is dropped; adding one to the SDK is `payabli.sdk.extraEnvironments`. |
| `payabli.demo.appId` | | `secrets.properties.example` prefills `com.payabli.example.app`; the build itself falls back to blank. Compared against the running package by the readiness check, so a `-P` run without the template fails that check. |
| `payabli.demo.signingCertificate` | | SHA-256 as the Play Console shows it; case and punctuation ignored. Blank means the signing key is not verified. `keytool -printcert -jarfile <apk>` prints it for a file, and the Setup screen shows what is installed. |
| `payabli.demo.tokenHost` | | Blank resolves per run; see below. |
| `payabli.demo.emulatorTokenHost` | `10.0.2.2` | |
| `payabli.demo.deviceTokenHost` | `127.0.0.1` | |
| `payabli.demo.tokenPort` | `8787` | |
| `payabli.demo.diagnostics` | `true` | Redacted request and response logging on the payment screens. |
| `payabli.demo.prefill` | `false` | Fills the payment form with the sample identity, for a walkthrough that is not about typing. |

With nothing set, the Setup screen shows a dash and says what is missing.

### The token server

`example-server/` mints the token. Start it, then:

| Target | Address | Setup |
|---|---|---|
| Emulator | `10.0.2.2:8787` | nothing, it is the default |
| Physical device | `127.0.0.1:8787` | `adb reverse tcp:8787 tcp:8787` |
| LAN | whatever you pass | an entry in `src/debug/res/xml/network_security_config.xml`, and `PAYABLI_LOCAL_TOKEN_SERVER_HOST=0.0.0.0` on the server. Prefer `adb reverse`: the wide bind publishes an unauthenticated token endpoint to the network. |

Override without rebuilding:

```bash
adb shell am start -n com.payabli.example.app/.MainActivity -e payabliTokenHost 192.168.1.10:8787
```

The Setup screen's **Chosen because** row states which rule picked the address.

## How it is put together

**Every call into the SDK is in `sdk/` or `demo/simple/`.** `sdk/` is this app's integration layer, which
the screens share and which hands back types the app owns; the rest of `demo/` is scaffolding around it and
names no SDK type. `demo/simple/` is the exception: one screen that calls the SDK directly, so the fewest
calls a capture takes can be read in one file. `AppContainer.kt`, `MainActivity.kt` and
`PayabliDemoApplication.kt` stay at the root, where the manifest expects them.
`SdkCallsAreInOnePackageTest` reads `src/main` and fails naming any file outside those two packages whose
source contains `com.payabli.sdk.`, so a fully qualified call is caught as an import is. What it cannot see
is a `demo/` file reaching an SDK type through one of `sdk/`'s `internal` properties, which names no
package: Kotlin has no package-private, and `PaymentFormHost.kt` needs those values from the files that
hold them.

```
com/payabli/example/app/
  AppContainer.kt   MainActivity.kt   PayabliDemoApplication.kt
  sdk/     the integration
  demo/    ui/  flow/  payment/  net/  config/  terminal/  diagnostics/  preflight/
    simple/  the one screen that calls the SDK directly
```

Inside `sdk/`:

- `PayInSessionSource.kt` mints a token and initializes the session, which is the one piece an integration
  writes for itself.
- `PayInFlowGate.kt` and `PayInStartup.kt` turn that session into the flow a screen submits through.
- `PaymentFormHost.kt` calls `PayabliPayInForm`, configured in `PayInForms.kt`, and passes nothing about
  appearance: the form reads this app's `MaterialTheme`. The form submits, a tap runs the operation through
  the flow it was handed, and the outcome arrives on the `onCompleted` or `onFailed` the host supplied. Both
  are required, and neither has anything to acknowledge afterwards.
- `PayInOutcomes.kt` maps what the SDK answers onto this app's own `PaymentResult` and `PaymentError`, so a
  screen reads a demo type.

Card-present runs on the Tap to Pay SDK: `AppContainer.kt` builds `sdk/TapToPayTerminal.kt`, which
implements `demo/terminal/TerminalController.kt` so the screens never name the SDK.

### The smallest capture there is

`demo/simple/SimpleCaptureScreen.kt` is one file, and it is the thing to read first. The calls are numbered
in it:

1. **A session**, once per process. The app's own backend mints an access token and `PayabliSession` is
   configured with it. Nothing can be sent until this has answered, which is why the form is not drawn yet.
2. **A flow**, once per screen. `PayabliPayIn(session, entryPoint, scope)` holds what the payer
   types, so the scope it is given has to outlive a configuration change. A `viewModelScope` does, and a
   rotation keeps both the form's contents and a submission in flight. Built in the composition instead, the
   flow is recreated on rotation and both are lost, along with the key that makes a retry safe.
3. **The form.** `PayabliPayInForm` collects, validates and submits, and the outcome arrives on `onCompleted`
   or `onFailed`.

The file also fills in the customer fields a capture needs and reads the amount back from the operation.
After an unknown outcome, look the transaction up first, as
[Handle the outcome](../README.md#handle-the-outcome) says.

## Known issues

- `SdkState`, `PayabliSession.state` and `PayabliSession.transport` are restricted to the SDK's own
  modules, so an app can't name them. Use `initialize()` and `setLogLevel()`.

## Styling

The palette is Payabli's brand palette, with the names kept in `Color.kt`. Where
the guide names only the ends, the middle Material 3 container tones are blended and marked as such;
every pair the app leans on clears WCAG 4.5:1 in both schemes. A passing check reads teal, because the
guide has no green. There is no dynamic-color option: it would replace the brand with the user's
wallpaper on any Android 12+ device.

This app is branded and the SDK's form is not. Every color here is a Material 3 role, so a form that
reads `MaterialTheme` picks up this scheme with nothing passed to it, and an integrator's scheme in
their app.

The brand typeface, **Poppins**, needs font files that aren't in the repository, so the app uses the
system font.

## Verifying

```bash
./gradlew :example:assembleDebug :example:test :example:ktlintCheck :example:lint
./gradlew :example:ktlintFormat --no-configuration-cache   # the flag is required
./gradlew :example:connectedAndroidTest                    # local only; CI has no emulator
```

Naming `SdkState`, `PayabliSession.state` or `PayabliSession.transport` is a Lint error, and Compose's own
lint checks run at error severity, neither with a baseline. Sonar leaves `**/example/app/demo/ui/**` out
of coverage, so read the JaCoCo report for the view models there.

An emulator reports no NFC and fails the host check. Run on real hardware.

### Manual device checks

No job runs these. Walk them on every attached device and report the models and API levels that ran.

**Readiness follows NFC.** Open Tap to pay with NFC on, and step 1 lists no NFC problem. Switch NFC off
in Settings, return, and the step reports `NFC switched off` and the verdict drops off ready. Switch it
back on, return, and both clear. Nothing is pressed in either direction: the checks are re-read on
window focus, not on resume, because the quick settings shade takes focus without pausing the activity.

**The NFC problem offers the switch.** With NFC off, the row carries `Turn it on`. From API 29 it opens
the settings panel over this screen, and dismissing it clears the problem; below API 29 it opens the
full NFC settings screen. No app can switch NFC on, and even the adb shell uid is refused.

**Card-present needs the reader.** Everything past step 1 on Tap to pay needs a phone, a paypoint and a
build that meet the [Tap to Pay guide](../taptopay/README.md#requirements)'s requirements, including the
card reader repository's credentials.

**A charge walks its states.** With the terminal ready, take a payment. The chip reads `Opening payment`,
then `Tap a card` while the reader waits, then `Closing payment` after the tap, and returns to `Ready`
when the charge ends. The setup hint stays hidden throughout. Repeat with the charge cancelled at
`Tap a card`: the chip still passes through `Closing payment`, because the opened payment is closed,
and returns to `Ready`.
