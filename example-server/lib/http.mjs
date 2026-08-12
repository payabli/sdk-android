// Reading a request and writing a response. Nothing here knows what Payabli is.

import { LocalTokenServerError } from "./errors.mjs";
import { configuredCorsOrigins, maxRequestBodyBytes } from "./settings.mjs";

export function sendJson(res, status, body) {
  res.writeHead(status, {
    "Cache-Control": "no-store",
    "Content-Type": "application/json; charset=utf-8",
    "X-Content-Type-Options": "nosniff"
  });
  res.end(JSON.stringify(body));
}

export function setCorsHeaders(req, res) {
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

export async function readJsonBody(req) {
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
