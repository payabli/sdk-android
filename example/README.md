# Payabli SDK sample app

What integrating the Payabli Android SDK looks like. Four capability areas:

| Screen | What it lets you do |
|---|---|
| **Payment method** | Store a card or bank account and get a reusable token back, inline and in a bottom sheet. |
| **Capture** | Charge a card or bank account, and read the whole transaction response. |
| **Tap to pay** | See whether this device can take a contactless payment and why not. Turn the terminal on, restart the session, charge, activate the device, and watch the session state and event stream. |
| **Setup** | Read back every value the SDK was configured with, and check the token endpoint is reachable. |

## The SDK is not wired yet

Nothing here calls the SDK. `:core` currently exposes `initialize` and `setLogLevel` and nothing else a
sample would use, and the capability modules are empty. The app is UI-complete against two seams:

- `ui/payment/PaymentFormHost.kt` — the payment form. **The only file that changes when the SDK's
  form component lands**: its body becomes a call to that composable plus two mapping functions onto
  `PaymentResult` and `PaymentError`.
- `terminal/TerminalController.kt` — the card-present terminal. `DemoTerminalController` stands in and
  is swapped for the real one in a single line of `AppContainer`.

The app owns the call sites, the configuration objects, the result and error models, the sheet chrome
and the outcome screens. Those do not move.

`AppContainer.kt` marks each swap point with `⟵ swap point`.

**The payment form deliberately renders nothing.** Collecting a card number, masking a security code
and deciding whether an expiry is in the past are the component's, and a sample app that did them
would be putting instrument capture in a file with none of the protections the component has, while
inviting anyone reading it to copy the pattern. The Setup screen lists what the form is configured to
collect, so the configuration is visible without the app implementing it.

## Running it

```bash
./gradlew :example:installDebug
adb shell am start -n com.payabli.example.app/.MainActivity
```

### Settings

Copy `secrets.properties.example` to `secrets.properties` and fill it in. That file is gitignored;
the template is tracked. Nothing in it is a credential — the entry point and app id are identifiers,
and the access token is minted at runtime by `example-server/`.

Any setting can be passed for a single run instead:

```bash
./gradlew :example:installDebug -Ppayabli.demo.entryPoint=test6
```

With nothing set, the Setup screen shows a dash and says what is missing, which is the intended first
run.

### The token server

`example-server/` mints the token. Start it, then:

| Target | Address | Setup |
|---|---|---|
| Emulator | `10.0.2.2:8787` | nothing, it is the default |
| Physical device | `127.0.0.1:8787` | `adb reverse tcp:8787 tcp:8787` |
| LAN | whatever you pass | see below, and prefer `adb reverse` |

The Setup screen's **Chosen because** row states which rule picked the address, so a failed probe
names its own fix.

Override without rebuilding:

```bash
adb shell am start -n com.payabli.example.app/.MainActivity \
  -e payabliTokenHost 192.168.1.10:8787
```

A LAN address also needs an entry in `src/debug/res/xml/network_security_config.xml`, because the
cleartext permission is keyed on the address the app dials, and `PAYABLI_LOCAL_TOKEN_SERVER_HOST=0.0.0.0`
on the server. Prefer `adb reverse`: the wide bind publishes an unauthenticated token endpoint to the
network.

**This differs from the iOS demo**, which falls back to the development machine's Bonjour name. A
device here gets `127.0.0.1`, which needs no name resolution and no wide bind.

## Things that will bite

**`@RestrictTo`, for whoever wires the SDK.** `SdkState`, `PayabliSession.state` and
`PayabliSession.transport` are `@RestrictTo(LIBRARY_GROUP)`, and the group is `io.github.payabli`.
`:example` applies only `payabli.quality`, so it has no group: naming any of the three is a Lint
**error**, and CI runs `:example:lint` with no baseline and no config. The intended path is to avoid
them — `initialize()` and `setLogLevel()` are public, the state is not.

**Compose's own lint checks ship at error severity.** `UnrememberedMutableState`,
`CoroutineCreationDuringComposition`, `FlowOperatorInvokedInComposition` and friends, with no baseline
anywhere in the repository. Run `:example:lint` per commit, not per phase.

**Coverage.** `payabli.quality` enables unit-test coverage for application modules as well as
libraries, and CI produces `:example:createDebugUnitTestCoverageReport`. Without both, the root build
points Sonar at a report nothing generates and every new line here reads as uncovered. Composables are
excluded from coverage by package: everything under `app/ui/**` needs a device, so every unit-testable
type lives outside it.

## Colours and type

The palette is the PAY_Style-Guide Figma file, token for token, with the names kept in `Color.kt`.
Material 3 needs five container tones per scheme where the guide names the ends, so three values are
blended between them; they are marked in the file. Every pair the app leans on was measured with the
WCAG formula and clears 4.5:1 in both schemes.

The guide has no green, so a passing check reads teal. Inventing a green would put a colour in the app
that appears in no token list.

Dynamic colour is **off**. It would derive the scheme from the user's wallpaper, replacing the brand on
any Android 12+ device. The parameter is still on `PayabliDemoTheme` if you want to see what an
integrator's app would look like.

### Why this app is branded, and why the SDK's form should not be

A deliberate choice, and the less common one. A survey of shipped payment SDKs found sample apps split
three ways: an untouched platform template, a fictional merchant's own colours, or the vendor's brand.
Only a minority take the third. This app takes it because it is a shop window rather than a pretend
merchant.

The same survey found the *component* answer close to unanimous in the other direction: a vendor's
drop-in payment form ships neutral and inherits the host app's theme. Several large SDKs default their
card form to the platform's own accent rather than to any brand colour.

That is the shape to build toward here. Every colour in this app is a Material 3 role, so a form that
reads `MaterialTheme` picks up this scheme with nothing passed to it, and picks up an integrator's
scheme in an integrator's app. The dark scheme comes along for free by the same route, which is worth
noting because dark mode was the weakest area in everything surveyed.

Deciding what the payment component's own defaults are is a public-surface question for both
platforms, not something to settle in this app.

The survey is at `reverse-eng/payments-sdks/reports/sample-app-and-component-styling-report.md`. It
names vendors and versions, so cite the path rather than repeating its findings in a commit message,
a pull request or a ticket.

The guide specifies **Poppins**, which this app does not yet use — it needs font files that are not in
the repository. It is on the follow-up list.

## Verifying

```bash
./gradlew :example:assembleDebug :example:test :example:ktlintCheck :example:lint
./gradlew :example:ktlintFormat --no-configuration-cache   # the flag is required
./gradlew :example:connectedAndroidTest                    # local only; CI has no emulator
```

**The emulator answers almost nothing here.** It reports no NFC and fails the host check, so the
readiness screen looks identical on every emulator however wrong the code is. Run on real hardware and
say which targets ran.
