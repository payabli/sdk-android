import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { dirname, isAbsolute, join } from "node:path";
import { fileURLToPath } from "node:url";

const serverDir = dirname(fileURLToPath(import.meta.url));
// PAYABLI_ENV_FILE picks the file, so a second environment is a second file rather than an edit to
// this one. A relative name resolves beside this server. An explicitly named file that is not there
// is fatal: the alternative is falling back to the sandbox defaults below and reporting nothing.
const envFileName = (process.env.PAYABLI_ENV_FILE || ".env").trim();
const envFilePath = isAbsolute(envFileName) ? envFileName : join(serverDir, envFileName);
if (process.env.PAYABLI_ENV_FILE && !existsSync(envFilePath)) {
  console.error(`PAYABLI_ENV_FILE=${envFileName} does not exist at ${envFilePath}`);
  process.exit(1);
}
loadEnv(envFilePath);

const port = Number.parseInt(process.env.PORT || "8787", 10);
const bindHost = stringValue(process.env.PAYABLI_LOCAL_TOKEN_SERVER_HOST) || "127.0.0.1";
const defaultApiBaseUrl = process.env.PAYABLI_API_BASE_URL || "https://api-sandbox.payabli.com/api";
const defaultTokenPath = process.env.PAYABLI_TOKEN_PATH || "/v2/token/serverside";
// The entry point the card-present routes act on when a request names none.
const defaultEntry = stringValue(process.env.PAYABLI_ENTRY);
const responseTokenField = (process.env.PAYABLI_RESPONSE_TOKEN_FIELD || "").trim();
const cacheTtlSeconds = integerSetting("PAYABLI_TOKEN_CACHE_TTL_SECONDS", 300);
const maxRequestBodyBytes = integerSetting("PAYABLI_MAX_REQUEST_BODY_BYTES", 32768);
const allowedApiHosts = parseCsvSet(
  process.env.PAYABLI_ALLOWED_API_HOSTS ||
    "api-sandbox.payabli.com,api-qa.payabli.com,api.payabli.com"
);
const configuredCorsOrigins = parseCsvSet(process.env.PAYABLI_ALLOWED_CORS_ORIGINS || "");
const tokenCache = new Map();

// Observed values. Anything else is passed through as its raw number rather
// than guessed at.
const DEVICE_STATUS_ACTIVE = 1;
const DEVICE_STATUS_PENDING = 2;

class LocalTokenServerError extends Error {
  constructor(statusCode, message) {
    super(message);
    this.statusCode = statusCode;
  }
}

const server = createServer((req, res) => {
  handleRequest(req, res).catch((error) => {
    console.error(redactSensitiveText(error instanceof Error ? error.stack || error.message : String(error)));
    const statusCode = error instanceof LocalTokenServerError ? error.statusCode : 500;
    sendJson(res, statusCode, {
      error: "Local token server failed",
      detail: publicErrorMessage(error)
    });
  });
});

async function handleRequest(req, res) {
  const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);

  const corsAllowed = setCorsHeaders(req, res);

  if (req.method === "OPTIONS") {
    res.writeHead(corsAllowed ? 204 : 403);
    res.end();
    return;
  }

  if (!corsAllowed) {
    sendJson(res, 403, { error: "Origin not allowed" });
    return;
  }

  if (url.pathname === "/health" && req.method === "GET") {
    sendJson(res, 200, { ok: true });
    return;
  }

  if (url.pathname === "/payabli/access-token" && ["GET", "POST"].includes(req.method || "")) {
    const body = req.method === "POST" ? await readJsonBody(req) : {};
    const token = await resolveAccessToken(body);
    sendJson(res, 200, { accessToken: token });
    return;
  }

  if (url.pathname === "/payabli/devices" && ["GET", "POST"].includes(req.method || "")) {
    const body = req.method === "POST" ? await readJsonBody(req) : {};
    const entry = stringValue(body.entry) || stringValue(url.searchParams.get("entry")) || defaultEntry;
    // The entry only. Anything else on the body would reach payabliApi as upstream options, where
    // apiBaseUrl, accessToken, clientId and clientSecret are all honoured, so a caller could spend
    // the env file's credential against any allowed host, production included. Which upstream is in
    // use is a property of the run, chosen by the env file.
    const devices = await listTapToPayDevices(entry, {});
    sendJson(res, 200, { entry, devices });
    return;
  }

  if (url.pathname === "/payabli/activation-code" && req.method === "POST") {
    const body = await readJsonBody(req);
    // The two fields this route documents, for the reason above.
    sendJson(res, 200, await requestActivationCode({
      entry: stringValue(body.entry),
      deviceId: stringValue(body.deviceId)
    }));
    return;
  }

  if (url.pathname === "/payabli/exchange-token" && req.method === "POST") {
    const body = await readJsonBody(req);
    const exchange = await exchangeCredentials(body, { forceRefresh: true });
    sendJson(res, 200, {
      accessToken: exchange.token,
      upstreamStatus: exchange.upstreamStatus,
      source: "credential-exchange"
    });
    return;
  }

  sendJson(res, 404, { error: "Not found" });
}

server.listen(port, bindHost, () => {
  console.log(`Payabli local token server listening on http://${bindHost}:${port}`);
  // The upstream and the file it came from. Without these, two runs on two environments are
  // indistinguishable in the log, and a refusal from the wrong one reads as a bad entry point.
  console.log(`Upstream:              ${defaultApiBaseUrl}`);
  console.log(`Env file:              ${envFilePath}`);
  if (defaultEntry) {
    console.log(`Entry point:           ${defaultEntry}`);
  }
  console.log(`Access token endpoint: http://${bindHost}:${port}/payabli/access-token`);
});

async function resolveAccessToken(options = {}) {
  const directToken = stringValue(options.accessToken) || stringValue(process.env.PAYABLI_ACCESS_TOKEN);
  if (directToken) {
    return directToken;
  }

  const exchange = await exchangeCredentials(options);
  return exchange.token;
}

async function exchangeCredentials(options = {}, { forceRefresh = false } = {}) {
  const clientId = stringValue(options.clientId) || stringValue(process.env.PAYABLI_CLIENT_ID);
  const clientSecret = stringValue(options.clientSecret) || stringValue(process.env.PAYABLI_CLIENT_SECRET);
  const apiBaseUrl = normalizeBaseUrl(stringValue(options.apiBaseUrl) || defaultApiBaseUrl);
  const tokenPath = normalizeTokenPath(stringValue(options.tokenPath) || defaultTokenPath);
  const tokenField = stringValue(options.responseTokenField) || responseTokenField;

  if (!clientId || !clientSecret) {
    throw new Error(
      "Set PAYABLI_ACCESS_TOKEN, or provide PAYABLI_CLIENT_ID and PAYABLI_CLIENT_SECRET for credential exchange."
    );
  }

  // Resolve and check before anything else uses these values, so a refused endpoint cannot serve a
  // cached token either.
  const endpoint = new URL(tokenPath.replace(/^\/+/, ""), ensureTrailingSlash(apiBaseUrl));
  assertAllowedEndpoint(endpoint, "The resolved token endpoint");

  const cacheKey = JSON.stringify({
    clientIdHash: sha256(clientId),
    clientSecretHash: sha256(clientSecret),
    apiBaseUrl,
    tokenPath,
    tokenField
  });
  const cached = tokenCache.get(cacheKey);
  if (!forceRefresh && cached && cached.expiresAt > Date.now()) {
    return { token: cached.token, upstreamStatus: 200 };
  }

  // redirect: "manual" so a 3xx comes back as a response instead of being followed. fetch follows
  // redirects by default, and a 307 or 308 replays the method and body, so an allowed host answering
  // with a Location on another origin would hand the credential to that origin. The endpoint check
  // above cannot see that: it runs before the request, and a redirect target only exists afterwards.
  // "manual" rather than "error" because it keeps the target readable, and a bare fetch rejection is
  // reported as "fetch failed" with nothing to distinguish it from a host being down.
  const upstream = await fetch(endpoint, {
    method: "POST",
    redirect: "manual",
    headers: {
      "Accept": "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ clientId, clientSecret })
  });

  if (upstream.status >= 300 && upstream.status < 400) {
    throw new LocalTokenServerError(
      502,
      `Token exchange to ${endpoint.origin} answered HTTP ${upstream.status} redirecting to ` +
        `${upstream.headers.get("location") || "an unnamed target"}. The redirect was not followed, ` +
        "because the credential would be sent to the target."
    );
  }

  const text = await upstream.text();
  let payload;
  try {
    payload = text ? JSON.parse(text) : {};
  } catch {
    payload = { raw: text };
  }

  if (!upstream.ok) {
    throw new Error(
      `Payabli token exchange failed with HTTP ${upstream.status}: ${safeJson(payload)}`
    );
  }

  const token = extractToken(payload, tokenField);
  if (!token) {
    throw new Error(
      `Payabli token exchange response did not include a token field. Response keys: ${Object.keys(payload).join(", ")}`
    );
  }

  if (cacheTtlSeconds > 0) {
    tokenCache.set(cacheKey, {
      token,
      expiresAt: Date.now() + cacheTtlSeconds * 1000
    });
  }

  return { token, upstreamStatus: upstream.status };
}

function sendJson(res, status, body) {
  res.writeHead(status, {
    "Cache-Control": "no-store",
    "Content-Type": "application/json; charset=utf-8",
    "X-Content-Type-Options": "nosniff"
  });
  res.end(JSON.stringify(body));
}

function setCorsHeaders(req, res) {
  const origin = req.headers.origin;
  res.setHeader("Vary", "Origin");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization");
  if (!origin) {
    return true;
  }
  if (!isAllowedCorsOrigin(origin)) {
    return false;
  }
  res.setHeader("Access-Control-Allow-Origin", origin);
  return true;
}

function loadEnv(path) {
  if (!existsSync(path)) {
    return;
  }

  const lines = readFileSync(path, "utf8").split(/\r?\n/);
  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) {
      continue;
    }

    const separatorIndex = line.indexOf("=");
    if (separatorIndex === -1) {
      continue;
    }

    const key = line.slice(0, separatorIndex).trim();
    const value = stripQuotes(line.slice(separatorIndex + 1).trim());
    if (key && process.env[key] === undefined) {
      process.env[key] = value;
    }
  }
}

function stripQuotes(value) {
  if (
    (value.startsWith('"') && value.endsWith('"')) ||
    (value.startsWith("'") && value.endsWith("'"))
  ) {
    return value.slice(1, -1);
  }

  return value;
}

function extractToken(payload, configuredField) {
  if (configuredField) {
    return stringValue(valueAtPath(payload, configuredField));
  }

  for (const field of ["access_token", "accessToken", "token"]) {
    const token = stringValue(valueAtPath(payload, field));
    if (token) {
      return token;
    }
  }

  return "";
}

function valueAtPath(value, path) {
  return path.split(".").reduce((current, key) => {
    if (current && typeof current === "object" && key in current) {
      return current[key];
    }
    return undefined;
  }, value);
}

function stringValue(value) {
  return typeof value === "string" ? value.trim() : "";
}

function ensureTrailingSlash(url) {
  return url.endsWith("/") ? url : `${url}/`;
}

function normalizeBaseUrl(url) {
  const trimmed = url.trim();
  const normalized = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
  const parsed = new URL(normalized);
  assertAllowedEndpoint(parsed, "PAYABLI_API_BASE_URL");
  return parsed.toString();
}

// Checks a URL that is about to receive the credentials. Applied to the configured base and, more
// importantly, to the endpoint actually resolved from base + path: a path can steer that resolution
// onto another origin, so validating the base alone leaves the credential reachable.
function assertAllowedEndpoint(parsed, label) {
  if (parsed.protocol !== "https:" && process.env.PAYABLI_ALLOW_INSECURE_UPSTREAM !== "true") {
    throw new LocalTokenServerError(400, `${label} must use https.`);
  }

  if (!allowedApiHosts.has(parsed.hostname.toLowerCase())) {
    throw new LocalTokenServerError(
      400,
      `${label} host is not allowed. Allowed hosts: ${Array.from(allowedApiHosts).join(", ")}`
    );
  }
}

function normalizeTokenPath(path) {
  const trimmed = path.trim();
  if (/^[a-z][a-z0-9+.-]*:/i.test(trimmed)) {
    throw new LocalTokenServerError(400, "PAYABLI_TOKEN_PATH must be a path, not an absolute URL.");
  }
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

function safeJson(value) {
  try {
    return redactSensitiveText(JSON.stringify(value));
  } catch {
    return redactSensitiveText(String(value));
  }
}

async function readJsonBody(req) {
  const chunks = [];
  let totalBytes = 0;
  for await (const chunk of req) {
    totalBytes += chunk.length;
    if (totalBytes > maxRequestBodyBytes) {
      throw new LocalTokenServerError(413, `Request body is too large. Maximum is ${maxRequestBodyBytes} bytes.`);
    }
    chunks.push(chunk);
  }

  const raw = Buffer.concat(chunks).toString("utf8").trim();
  if (!raw) {
    return {};
  }

  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new LocalTokenServerError(400, "Request body must be valid JSON.");
  }

  // Callers read this as an options bag, so anything else is refused here rather than reaching a
  // property access. null is the case that matters: it is valid JSON, it is not caught by a default
  // parameter, and reading a property of it throws a TypeError that surfaces as a 500. Arrays and
  // primitives were accepted instead, and silently behaved as if no options had been sent.
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new LocalTokenServerError(400, "Request body must be a JSON object.");
  }

  return parsed;
}

// A malformed numeric setting stops the server at startup instead of quietly changing behaviour. Two
// values defeat a comparison guard rather than merely being wrong, and both have to be refused here.
// NaN, from a non-numeric value, loses every comparison. Infinity, from a digit string too long to
// represent, wins every one. Either leaves `totalBytes > limit` false for a body of any size, so the
// digit test alone is not enough and the parsed number is checked as well.
function integerSetting(name, fallback) {
  const raw = (process.env[name] || "").trim();
  if (!raw) {
    return fallback;
  }

  const parsed = Number(raw);
  if (!/^\d+$/.test(raw) || !Number.isSafeInteger(parsed)) {
    throw new Error(
      `${name} must be a non-negative integer no greater than ${Number.MAX_SAFE_INTEGER}. ` +
        `Received: ${JSON.stringify(raw)}`
    );
  }

  return parsed;
}

function parseCsvSet(value) {
  return new Set(
    value
      .split(",")
      .map((item) => item.trim().toLowerCase())
      .filter(Boolean)
  );
}

function isAllowedCorsOrigin(origin) {
  if (configuredCorsOrigins.has(origin.toLowerCase())) {
    return true;
  }

  if (configuredCorsOrigins.size > 0) {
    return false;
  }

  try {
    const parsed = new URL(origin);
    return ["127.0.0.1", "localhost", "::1", "[::1]"].includes(parsed.hostname.toLowerCase());
  } catch {
    return false;
  }
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function publicErrorMessage(error) {
  return redactSensitiveText(error instanceof Error ? error.message : String(error));
}

function redactSensitiveText(value) {
  return value
    .replace(/(bearer\s+)[a-z0-9._~+/-]+=*/gi, "$1[REDACTED]")
    .replace(
      /("(?:access_token|accessToken|token|clientSecret|client_secret|secret)"\s*:\s*)"[^"]*"/gi,
      '$1"[REDACTED]"'
    )
    .replace(
      /((?:access_token|accessToken|token|clientSecret|client_secret|secret)=)[^\s&]+/gi,
      "$1[REDACTED]"
    );
}

async function payabliApi(path, { method = "GET", body = null, options = {} } = {}) {
  const apiBaseUrl = normalizeBaseUrl(stringValue(options.apiBaseUrl) || defaultApiBaseUrl);
  const token = await resolveAccessToken(options);
  const endpoint = new URL(path.replace(/^\/+/, ""), ensureTrailingSlash(apiBaseUrl));
  assertAllowedEndpoint(endpoint, "The resolved API endpoint");

  // redirect: "manual", as the credential exchange does and for the same reason: the endpoint check
  // above runs before the request, so it cannot see a redirect target, and a 307 or 308 replays the
  // method, body and Authorization header to whatever origin the Location names.
  const upstream = await fetch(endpoint, {
    method,
    redirect: "manual",
    headers: {
      "Accept": "application/json",
      "Content-Type": "application/json",
      "Authorization": `Bearer ${token}`
    },
    body: body === null ? undefined : JSON.stringify(body)
  });

  if (upstream.status >= 300 && upstream.status < 400) {
    throw new LocalTokenServerError(
      502,
      `${endpoint.origin} answered HTTP ${upstream.status} redirecting to ` +
        `${upstream.headers.get("location") || "an unnamed target"}. The redirect was not followed, ` +
        "because the access token would be sent to the target."
    );
  }

  const text = await upstream.text();
  let payload;
  try {
    payload = text ? JSON.parse(text) : {};
  } catch {
    payload = { raw: text };
  }

  if (!upstream.ok) {
    throw new LocalTokenServerError(
      upstream.status >= 500 ? 502 : upstream.status,
      `Payabli ${path} failed with HTTP ${upstream.status}: ${safeJson(payload)}`
    );
  }

  return payload;
}

function envelopeDecline(payload) {
  if (!payload || payload.isSuccess !== false) {
    return null;
  }

  const data = payload.responseData || {};
  return {
    code: Number(data.resultCode) || 0,
    text: stringValue(data.resultText) || stringValue(payload.responseText) || "Declined"
  };
}

function deviceStatusLabel(status) {
  if (status === DEVICE_STATUS_ACTIVE) return "active";
  if (status === DEVICE_STATUS_PENDING) return "pending";
  return `status-${status}`;
}

async function describeDevice(entry, deviceId, options = {}) {
  const payload = await payabliApi(
    `/Device/get/${encodeURIComponent(entry)}/${encodeURIComponent(deviceId)}`,
    { options }
  );
  return envelopeDecline(payload) ? null : payload.responseData || null;
}

async function listTapToPayDevices(entry, options = {}) {
  if (!entry) {
    throw new LocalTokenServerError(400, "Set PAYABLI_ENTRY in .env, or pass entry in the request.");
  }

  const payload = await payabliApi(`/Cloud/list/${encodeURIComponent(entry)}`, { options });
  const decline = envelopeDecline(payload);
  if (decline) {
    throw new LocalTokenServerError(400, `Device list declined (${decline.code}): ${decline.text}`);
  }

  const rows = Array.isArray(payload.responseList) ? payload.responseList : [];
  const described = [];
  for (let index = 0; index < rows.length; index += 6) {
    const batch = await Promise.all(
      rows.slice(index, index + 6).map((row) => describeDevice(entry, row.deviceId, options))
    );
    described.push(...batch);
  }

  return described
    .filter((device) => device && stringValue(device.deviceType).toLowerCase() === "softpos")
    .map((device) => ({
      deviceId: device.deviceId,
      status: deviceStatusLabel(device.deviceStatus),
      deviceStatus: device.deviceStatus,
      model: device.model,
      serialNumber: device.serialNumber,
      friendlyName: device.friendlyName,
      createdAt: device.createdAt,
      updatedAt: device.updatedAt
    }))
    .sort((a, b) => String(b.createdAt || "").localeCompare(String(a.createdAt || "")));
}

async function requestActivationCode(options = {}) {
  const entry = stringValue(options.entry) || defaultEntry;
  if (!entry) {
    throw new LocalTokenServerError(400, "Set PAYABLI_ENTRY in .env, or pass entry in the request.");
  }

  let deviceId = stringValue(options.deviceId);
  let resolvedFrom = "request";

  // A serial number is the app's identifierForVendor and is shared by every
  // record a reinstall leaves behind, so it cannot pick one device out. Only a
  // deviceId does. Falling back to the newest pending device is a convenience
  // for a single-device QA setup, and reports itself as such.
  if (!deviceId) {
    const devices = await listTapToPayDevices(entry, options);
    const pending = devices.filter((device) => device.deviceStatus === DEVICE_STATUS_PENDING);

    if (pending.length === 0) {
      throw new LocalTokenServerError(
        404,
        `No pending Tap to Pay devices on ${entry}. Pass deviceId to target a specific device.`
      );
    }

    deviceId = stringValue(pending[0].deviceId);
    resolvedFrom = pending.length === 1 ? "onlyPendingDevice" : `newestOf${pending.length}Pending`;
  }

  const payload = await payabliApi("/v2/device/taptopay/activate/challenge", {
    method: "POST",
    body: { entry, deviceId },
    options
  });

  const decline = envelopeDecline(payload);
  if (decline) {
    throw new LocalTokenServerError(
      decline.code === 404 ? 404 : 400,
      `Activation challenge declined (${decline.code}): ${decline.text}`
    );
  }

  const data = payload.responseData || {};
  return {
    entry,
    deviceId,
    resolvedFrom,
    code: stringValue(data.code),
    expiresAt: stringValue(data.expiresAt),
    alreadyIssued: Boolean(data.alreadyIssued)
  };
}
