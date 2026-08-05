# PayabliDemo Local Token Server

Tiny development server for PayIn payment flow QA. It gives the Android sample a
backend-shaped endpoint without putting Payabli credentials in the APK.

The server supports two local QA modes:

- Direct token mode: return a sandbox API token that can call
  `/api/TokenStorage/add` and the v2 MoneyIn auth/capture endpoints.
- Credential exchange mode: post a sandbox `clientId` and `clientSecret`
  to a configurable Payabli token endpoint, then return the token from that
  response as `accessToken`.

Nothing in `:example` calls this server yet. It is a local developer tool in the
same category as `connectedAndroidTest`: no workflow in `.github/` knows about
it, and none should.

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

One server, one bind address, three names for it. Which one is correct depends
entirely on where the caller runs, and this is the first thing that fails when it
is wrong:

| Caller | Address |
|---|---|
| The app, on an emulator | `10.0.2.2` |
| The app, on a wired phone | the development machine's LAN IP |
| `curl` or a shell, on the development machine | `127.0.0.1` |

The device rows are not alternative spellings of `127.0.0.1`. In Android's own
words, `127.0.0.1` is "the emulated device loopback interface", while `10.0.2.2`
is a "special alias to your host loopback interface (127.0.0.1 on your
development machine)". From inside an emulator, `127.0.0.1` reaches the emulator
itself, so a server on the development machine never sees the request.

Every URL below is written for one of those three callers, and says which.

For the app on an emulator:

```text
http://10.0.2.2:8787/payabli/access-token
```

The server keeps its `127.0.0.1` bind either way. `10.0.2.2` reaches a process
bound to the host's loopback without the server listening on any other
interface, so an emulator needs no `PAYABLI_LOCAL_TOKEN_SERVER_HOST=0.0.0.0`. A
wired phone does, because it is a separate machine on the network; see Physical
Device Notes.

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
    </domain-config>
</network-security-config>
```

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
PAYABLI_API_BASE_URL=https://api-sandbox.payabli.com/api
PAYABLI_TOKEN_PATH=/v2/token/serverside
```

That maps to Payabli's server-side token call:

```bash
curl --location 'https://api-sandbox.payabli.com/api/v2/token/serverside' \
  --header 'Content-Type: application/json' \
  --data '{
    "clientId": "{clientId}",
    "clientSecret": "{clientSecret}"
  }'
```

For QA, use:

```bash
PAYABLI_API_BASE_URL=https://api-qa.payabli.com/api
```

The server also accepts `api-sandbox.payabli.com/api` or
`api-qa.payabli.com/api` and will add `https://` automatically.
Token exchange is restricted to Payabli hosts by default:

```bash
PAYABLI_ALLOWED_API_HOSTS=api-sandbox.payabli.com,api-qa.payabli.com,api.payabli.com
```

Only add hosts for trusted local test infrastructure. Do not point credential
exchange at arbitrary URLs, because that would send the configured
`clientSecret` to that host.

This mode does not change the app's address. Still, for the app on an emulator:

```text
http://10.0.2.2:8787/payabli/access-token
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
  "apiBaseUrl": "https://api-sandbox.payabli.com/api",
  "tokenPath": "/v2/token/serverside",
  "responseTokenField": "access_token"
}
```

If `responseTokenField` is blank, the server tries `access_token`,
`accessToken`, then `token`.

## Physical Device Notes

Neither device address above applies to a wired phone. `127.0.0.1` points at the
phone itself, and `10.0.2.2` is an emulator alias with no meaning on real
hardware. Use the development machine's LAN IP:

```text
http://<machine-lan-ip>:8787/payabli/access-token
```

The server has to listen beyond loopback to be reachable, so bind to all
interfaces while testing:

```bash
PAYABLI_LOCAL_TOKEN_SERVER_HOST=0.0.0.0 node server.mjs
```

Use this only on a trusted network, stop the process when finished, and prefer
short-lived sandbox credentials. The debug network security config also needs
that LAN address alongside `10.0.2.2`, since the cleartext permission is keyed
on the address the app dials.

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

There is no device-side equivalent: Android ships neither `curl` nor `wget` in
the shell, so `adb shell` cannot make the request. Reachability from a device is
what the app itself demonstrates.

A `PayabliTokenProvider` implementation reads `accessToken` from the response and
returns it as a `String`.
