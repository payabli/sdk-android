# Payabli SDK sample app

What integrating the Payabli Android SDK looks like.

| Screen | What it lets you do |
|---|---|
| **Payment method** | Store a card or bank account and get a reusable token back, inline and in a bottom sheet. |
| **Capture** | Charge a card or bank account, and read the whole transaction response. |
| **Tap to pay** | See whether this device can take a contactless payment and why not. Turn the terminal on, restart the session, charge, activate the device, and watch the session state and event stream. |
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

**Three sources, most specific first:** the `-P` flag, then an environment variable, then
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
| `payabli.demo.entryPoint` | | Partner identifier. Exists in one environment, so set it with the row below. |
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

The Setup screen's **Chosen because** row states which rule picked the address. The iOS demo differs: it
falls back to the development machine's Bonjour name, where a device here gets `127.0.0.1`.

## How it is put together

**Every call into the SDK is in `sdk/` or `demo/simple/`.** `sdk/` is this app's integration layer, which
four screens share and which hands back types the app owns; the rest of `demo/` is scaffolding around it and
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

Card-present has no SDK yet, so `demo/terminal/TerminalController.kt` stands in for one and `AppContainer.kt`
marks it with `⟵ swap point`.

### The smallest capture there is

`demo/simple/SimpleCaptureScreen.kt` is one file and three calls, and it is the thing to read first. The
calls are numbered in it:

1. **A session**, once per process. The app's own backend mints an access token and `PayabliSession` is
   configured with it. Nothing can be sent until this has answered, which is why the form is not drawn yet.
2. **A flow**, once per screen. `PayabliPayInPaymentFlow(session, entryPoint, scope)` holds what the payer
   types, so the scope it is given has to outlive a configuration change. A `viewModelScope` does, and a
   rotation keeps both the form's contents and a submission in flight. Built in the composition instead, the
   flow is recreated on rotation and both are lost, along with the key that makes a retry safe.
3. **The form.** `PayabliPayInForm` collects, validates and submits, and the outcome arrives on `onCompleted`
   or `onFailed`.

Not reproduced here. A fenced block is not compiled, so a signature change would leave this page describing
an integration that no longer builds, and the file is short enough to open. What the file adds beyond the
three calls is the part an integration also has to get right: the customer fields a capture is refused
without, the amount read back from the operation rather than typed, and `Failed.retryKey` sent on a second
attempt so a retry after an unknown outcome settles the first charge instead of making a second one.

`SdkCallsAreInOnePackageTest` allows `demo/simple/` alongside `sdk/` precisely so this file can call the SDK
directly. Everywhere else in `demo/` still may not.

## Things that will bite

- `SdkState`, `PayabliSession.state` and `PayabliSession.transport` are `@RestrictTo(LIBRARY_GROUP)` and
  `:example` has no group, so naming any of them is a Lint **error**, with no baseline in CI. Use
  `initialize()` and `setLogLevel()`.
- Compose's own lint checks ship at error severity, also with no baseline. Run `:example:lint` per
  commit.
- `payabli.quality` enables unit-test coverage for application modules too, so JaCoCo measures this
  one. Sonar then drops `**/example/app/demo/ui/**` through `sonar.coverage.exclusions` in the root
  `build.gradle.kts`, which is a separate setting from `payabli.quality`. The view models under that
  package are unit tested and measured locally, and absent from the number Sonar reports, so read the
  JaCoCo report before concluding they are uncovered.

## Styling

The palette is the PAY_Style-Guide Figma file token for token, with the names kept in `Color.kt`. Where
the guide names only the ends, the middle Material 3 container tones are blended and marked as such;
every pair the app leans on clears WCAG 4.5:1 in both schemes. A passing check reads teal, because the
guide has no green. There is no dynamic-colour option: it would replace the brand with the user's
wallpaper on any Android 12+ device.

This app is branded and the SDK's form is not. Every colour here is a Material 3 role, so a form that
reads `MaterialTheme` picks up this scheme with nothing passed to it, and an integrator's scheme in
their app. What the component's own defaults should be is a public-surface question for both platforms.

The guide specifies **Poppins**, which needs font files that are not in the repository. On the follow-up
list.

## Verifying

```bash
./gradlew :example:assembleDebug :example:test :example:ktlintCheck :example:lint
./gradlew :example:ktlintFormat --no-configuration-cache   # the flag is required
./gradlew :example:connectedAndroidTest                    # local only; CI has no emulator
```

**The emulator answers almost nothing here.** It reports no NFC and fails the host check, so the
readiness screen looks identical on every emulator however wrong the code is. Run on real hardware and
say which targets ran.

### Manual device checks

No job runs these, and none can until there is a service providing remote physical devices. Walk them on
every attached device and report the models and API levels that ran.

**Readiness follows NFC.** Open Tap to pay with NFC on, and step 1 lists no NFC problem. Switch NFC off
in Settings, return, and the step reports `NFC switched off` and the verdict drops off ready. Switch it
back on, return, and both clear. Nothing is pressed in either direction: the checks are re-read on
window focus, not on resume, because the quick settings shade takes focus without pausing the activity.
There is no broadcast for this. `ACTION_ADAPTER_STATE_CHANGED` was registered with
`android.permission.NFC` held and the receiver confirmed in `dumpsys activity broadcasts`, and it was
delivered on none of a Pixel 7a, a Galaxy S22 Ultra or a Galaxy A13, spanning API 33 and 36.

**The NFC problem offers the switch.** With NFC off, the row carries `Turn it on`. From API 29 it opens
the settings panel over this screen, and dismissing it clears the problem; below API 29 it opens the
full NFC settings screen. No app can switch NFC on, and even the adb shell uid is refused.

**Card-present needs the reader.** Everything past step 1 on Tap to pay runs against the demo
controller. A real terminal session, an activation and a charge need hardware and the card reader
dependency.
