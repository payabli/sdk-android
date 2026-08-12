// Getting an access token: straight from the env file, or by exchanging client credentials.
//
// The cache is keyed on the credential and the endpoint, so two environments in one process cannot
// serve each other's token.

import { createHash } from "node:crypto";
import { LocalTokenServerError, safeJson } from "./errors.mjs";
import {
  cacheTtlSeconds,
  defaultApiBaseUrl,
  defaultTokenPath,
  responseTokenField,
  stringValue
} from "./settings.mjs";
import { ensureTrailingSlash, normalizeBaseUrl, normalizeTokenPath, assertAllowedEndpoint } from "./upstream.mjs";

const tokenCache = new Map();

// Observed values. Anything else is passed through as its raw number rather
// than guessed at.

export async function resolveAccessToken(options = {}) {
  const directToken = stringValue(options.accessToken) || stringValue(process.env.PAYABLI_ACCESS_TOKEN);
  if (directToken) {
    return directToken;
  }

  const exchange = await exchangeCredentials(options);
  return exchange.token;
}

export async function exchangeCredentials(options = {}, { forceRefresh = false } = {}) {
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

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
