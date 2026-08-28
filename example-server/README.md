# PayabliDemo Local Token Server

Tiny development server for exercising the PayIn payment flows. It gives the Android sample a
backend-shaped endpoint without putting Payabli credentials in the APK.

The server supports two modes:

- Direct token mode: return a sandbox API token that can call
  `/api/TokenStorage/add` and the v2 MoneyIn auth/capture endpoints.
- Credential exchange mode: post a sandbox `clientId` and `clientSecret`
  to a configurable Payabli token endpoint, then return the token from that
  response as `accessToken`.

The sample app calls this server. Its Setup and Tap to pay screens post to
`/payabli/exchange-token` and get `/health`, and report what came back. That is
the app fetching its own token over `HttpURLConnection`, not an SDK call: no
session exists yet to hold a token provider.

The live workflows run this server too, on the runner, so CI and the bench exercise one
path rather than two. Nothing else in `.github/` starts it, and the ordinary per-pull-request
jobs do not: they run no test that needs a token.

## Requirements

Node 18 or newer. `server.mjs` uses the global `fetch`, which stopped requiring
the `--experimental-fetch` flag in Node v18.0.0. There are no dependencies and
no `package.json`, only Node built-ins.

## Setup

```bash
cd example-server
cp .env.example .env
```

Edit `.env`:

```bash
PAYABLI_ACCESS_TOKEN=<a short-lived sandbox access token>
```

Start the server:

```bash
node server.mjs
```

By default the server binds only to `127.0.0.1`, the loopback interface of the
development machine it runs on. Keep that default for emulator testing, so local
credentials and returned access tokens are not exposed on the LAN.

## Which address to use

One server under three names, and which one is correct depends on where the caller
runs. This is the first thing that fails when it is wrong:

| Caller | Address | Server bind |
|---|---|---|
| The app, on an emulator | `10.0.2.2` | default `127.0.0.1` |
| The app, on a phone with `adb reverse` | `127.0.0.1` | default `127.0.0.1` |
| The app, on a phone with no adb connection | the development machine's LAN IP | `0.0.0.0` |
| `curl` or a shell, on the development machine | `127.0.0.1` | default `127.0.0.1` |

Three of the four reach the server on its default loopback bind. The LAN row does
not, and it is the one exception: a LAN address cannot reach a process listening
only on loopback, so that route also needs
`PAYABLI_LOCAL_TOKEN_SERVER_HOST=0.0.0.0`. That is what makes it the fallback
rather than the default, and Physical Device Notes covers the exposure it carries.

The device rows are not alternative spellings of `127.0.0.1`. In Android's own
words, `127.0.0.1` is "the emulated device loopback interface", while `10.0.2.2`
is a "special alias to your host loopback interface (127.0.0.1 on your
development machine)". From inside an emulator, `127.0.0.1` reaches the emulator
itself, so a server on the development machine never sees the request.

Every URL below is written for one of those callers, and says which.

For the app on an emulator:

```text
http://10.0.2.2:8787/payabli/exchange-token
```

The server keeps its `127.0.0.1` bind. `10.0.2.2` reaches a process bound to the
host's loopback without the server listening on any other interface, so an
emulator needs no `PAYABLI_LOCAL_TOKEN_SERVER_HOST=0.0.0.0`. A phone does not
need one either, as long as adb is connected; see Physical Device Notes.

## Permitting cleartext to the server

This server speaks plain HTTP, and Android denies cleartext by default for any
app targeting API 28 or higher. `:example` targets 36, so a request to
`10.0.2.2` fails until the app permits it.

Scope the permission to the debug source set, so no release build of the sample
can carry it, and to the single address rather than a blanket
`android:usesCleartextTraffic`. Create
`example/src/debug/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

Both addresses, because the permission is keyed on the address the app dials and
that differs by how the device reaches the server: `10.0.2.2` from an emulator,
`127.0.0.1` from a phone using `adb reverse`. A phone with no adb connection
needs its LAN address added here as well.

Reference it from a debug manifest at `example/src/debug/AndroidManifest.xml`, which
the build merges into the debug variant only:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
```

`core/src/androidTest/res/xml/network_security_config.xml` is the working
precedent in this repository, for the instrumented transport tests. It permits
both `127.0.0.1` and `10.0.2.2`, and its comments explain why those are two
different hosts rather than two spellings of one.

## Declaring the INTERNET permission

Permitting cleartext is not sufficient on its own. A network security config
relaxes which protocol is allowed; it does not grant the app network access at
all. Without `android.permission.INTERNET` every request fails regardless of the
cleartext entry above.

The SDK declares no permissions. That is deliberate: a library merging a
permission into the app that embeds it would inflate that app's declared
permissions without the app developer's say. So the host app declares it, and in
this repository the host app is `:example`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

This one belongs in the main manifest rather than the debug manifest above, since
every variant that talks to Payabli needs it and a release build would otherwise
be unable to reach any endpoint. Only the cleartext config is debug-scoped.

## Card-present routes

Two routes back the Tap to pay screen's device and activation steps. Both act on
`PAYABLI_ENTRY` unless the request names an `entry` of its own.

| Route | Method | Returns |
|---|---|---|
| `/payabli/devices` | GET or POST | the SoftPOS devices registered to the entry point, with a readable status |
| `/payabli/activation-code` | POST | an activation code for a device |

```bash
curl -s -X POST -H 'Content-Type: application/json' -d '{}' \
  http://127.0.0.1:8787/payabli/devices

curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"deviceId":"4da924ef-..."}' \
  http://127.0.0.1:8787/payabli/activation-code
```

`deviceId` targets one device. Omitted, the newest **pending** device is used and the response says so
in `resolvedFrom`, which is a convenience for a single-device setup rather than a guess to rely on. A
serial number cannot stand in: it is the app's install identifier and every record a reinstall leaves
behind shares it.

`/payabli/devices` also returns `unavailable`, the devices whose per-device lookup was declined, with
the code and text. They are named rather than dropped from the list.

These mirror the iOS demo's token server, which has served them for longer. The sample
app's terminal is still `DemoTerminalController`, so nothing in the app calls them yet.

## Selecting an environment

The server serves one upstream per run, chosen by `PAYABLI_API_BASE_URL` in the env file
it loads. `PAYABLI_ENV_FILE` picks the file, so a second environment is a second file:

```bash
node server.mjs                                  # .env
PAYABLI_ENV_FILE=.env.sandbox node server.mjs    # .env.sandbox
```

`.gitignore` covers `.env.*`, so the second file stays untracked. Naming a file that does
not exist exits rather than falling back to the built-in sandbox defaults.

An entry point exists in one environment. The server's upstream and the app's
`-Ppayabli.demo.environment` have to agree, or the refusal reads as a bad entry point.

## Token caching and the SDK's provider contract

`PayabliTokenProvider`'s documented contract is to "mint a token rather than
return a cached one", because the SDK calls it when a token has just been
rejected. Handing the same value back leaves nothing rotated. The three ways to
reach a token here do not all satisfy that.

`/payabli/access-token` in credential exchange mode serves from a cache keyed on
the credentials and endpoint, for `PAYABLI_TOKEN_CACHE_TTL_SECONDS` seconds,
default `300`. That suits browsing a payment form and does not suit a provider.

`POST /payabli/exchange-token` forces a refresh on every call, so it always
reaches the upstream token endpoint and always returns a newly minted token. It
reads `PAYABLI_CLIENT_ID` and `PAYABLI_CLIENT_SECRET` from `.env` when the POST
body omits them, so the body can be empty. This is the route for a provider.

Direct token mode cannot rotate anything: a static `PAYABLI_ACCESS_TOKEN` comes
back unchanged on every call. It is fine for a first request and useless for
exercising refresh.

Alternatively, keep `/payabli/access-token` and disable the cache:

```bash
PAYABLI_TOKEN_CACHE_TTL_SECONDS=0
```

## Credential Exchange Mode

To have the local endpoint exchange sandbox credentials, leave
`PAYABLI_ACCESS_TOKEN` blank and configure:

```bash
PAYABLI_CLIENT_ID=<a sandbox client id>
PAYABLI_CLIENT_SECRET=<a sandbox client secret>
PAYABLI_API_BASE_URL=https://<api-host>/api
PAYABLI_TOKEN_PATH=/v2/token/serverside
```

`<api-host>` is the Payabli API host for the deployment the credentials belong to, which is
`api.payabli.com` in production. Payabli provides the host for any other deployment along with the
credentials for it.

That maps to Payabli's server-side token call:

```bash
curl --location 'https://<api-host>/api/v2/token/serverside' \
  --header 'Content-Type: application/json' \
  --data '{
    "clientId": "{clientId}",
    "clientSecret": "{clientSecret}"
  }'
```

A bare host works too: the server accepts `<api-host>/api` and adds `https://`.

Token exchange is restricted to the hosts below, and a base URL pointing anywhere else is refused rather
than ignored. Reaching a deployment that is not listed means naming it here for that run:

```bash
PAYABLI_ALLOWED_API_HOSTS=<api-host>,api.payabli.com
```

Only add hosts for trusted local test infrastructure. Do not point credential
exchange at arbitrary URLs, because that would send the configured
`clientSecret` to that host.

This mode does not change the app's address. Still, for the app on an emulator:

```text
http://10.0.2.2:8787/payabli/exchange-token
```

Credentials can also be passed per request for quick experiments. This one runs
on the development machine rather than on a device, so it uses `127.0.0.1`:

```bash
curl -X POST http://127.0.0.1:8787/payabli/exchange-token \
  -H 'Content-Type: application/json' \
  -d '{
    "clientId": "sandbox-client-id",
    "clientSecret": "sandbox-client-secret"
  }'
```

Upstream request details are configurable from either `.env` or the POST body,
subject to the allowed-host guard:

```json
{
  "clientId": "...",
  "clientSecret": "...",
  "apiBaseUrl": "https://<api-host>/api",
  "tokenPath": "/v2/token/serverside",
  "responseTokenField": "access_token"
}
```

If `responseTokenField` is blank, the server tries `access_token`,
`accessToken`, then `token`.

## Physical Device Notes

`10.0.2.2` is an emulator alias and means nothing on real hardware, and a phone
dialing `127.0.0.1` reaches itself. The fix is to give the phone a `127.0.0.1`
that leads back to the development machine, which is what `adb reverse` does:

```bash
adb reverse tcp:8787 tcp:8787
```

The app then uses the same address a shell on the development machine does:

```text
http://127.0.0.1:8787/payabli/exchange-token
```

The server keeps its loopback bind, so nothing is exposed to the network. The
forward lasts as long as the adb connection and is dropped by
`adb reverse --remove tcp:8787`. With more than one device attached, name the
target with `-s <serial>`, since a bare `adb reverse` refuses to guess.

Prefer this to a wide bind. Nothing on these routes authenticates a caller:
`/payabli/exchange-token` returns an access token to anyone who asks, and
`/payabli/access-token` does the same. Binding to `0.0.0.0` publishes both to
every host on the network, which is the exposure the loopback default exists to
prevent.

### When adb forwarding is not available

For a device that cannot use adb, bind beyond loopback and dial the development
machine's LAN address:

```bash
PAYABLI_LOCAL_TOKEN_SERVER_HOST=0.0.0.0 node server.mjs
```

```text
http://<machine-lan-ip>:8787/payabli/exchange-token
```

Do this only on a trusted network, stop the process when finished, and prefer
short-lived sandbox credentials. Add that LAN address to the debug network
security config too, since the cleartext permission is keyed on the address the
app dials.

Browser CORS responses are restricted to localhost origins by default. The
sample's requests are native and do not need CORS.

## Contract

`GET /payabli/access-token`, `POST /payabli/access-token`, and
`POST /payabli/exchange-token` return:

```json
{ "accessToken": "..." }
```

`GET /health` answers `{"ok":true}` without touching credentials, which is the
cheapest way to confirm the process is up. From the development machine:

```bash
curl -sS http://127.0.0.1:8787/health
```

The same check runs from the device, which confirms an emulator alias or an
`adb reverse` forward without waiting for an app to be wired. Android ships no
`curl` or `wget`, and the shell does have `nc`:

```bash
adb shell '{ printf "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"; sleep 2; } | nc 127.0.0.1 8787'
```

The response is chunked, so it ends with `{"ok":true}` between a length line and
a `0`. Use whichever address the table above gives for that caller: `127.0.0.1`
with a forward in place, `10.0.2.2` from an emulator.

Keep the `sleep`. Piping the request alone lets `nc` close the connection before
the response arrives, and the command then prints nothing at all, which looks
identical to a forward that is not working. Measured on phones at API 33 and 36:
the bare form returns no output, the form above returns the body.

A `PayabliTokenProvider` implementation reads `accessToken` from the response and
returns it as a `String`.
