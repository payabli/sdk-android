#!/usr/bin/env python3
"""A deliberately slow stand-in for a merchant's token broker, on the development machine.

Exists so a manual test can measure how much of the SDK's provider deadline a real token round trip
consumes when the round trip is slow. Two things have to be true for that measurement to mean
anything, and each is why a piece of this exists.

**The traffic has to be real, over a socket the client does not control.** So this runs on the host,
where an emulator reaches it at 10.0.2.2, rather than inside the test process. A server on 127.0.0.1
inside the device is the device's own loopback interface and never leaves it.

**Something has to actually be slow, and it cannot be the emulator.** Measured on emulator 36.6.11:
launched with `-netspeed edge -netdelay edge`, `adb emu network status` reports the profile back
correctly, 473600 bits/s with 80 to 400 ms of latency, and then does not apply it. A 4 MiB body
arrived in 85 ms, about 394 Mbps, and ping to 10.0.2.2 stayed at 0.16 ms against a claimed 80 ms
floor. Neither interface is shaped: the route goes over wlan0 by default and forcing it to eth0 by
disabling Wi-Fi made it faster, not slower. So the throttle lives here instead, where it is enforced
by this process and cannot be silently ignored. That also makes it reproducible, which emulator
shaping never was.

    python3 scripts/slow_broker.py                 # 127.0.0.1:8080, reachable at 10.0.2.2:8080
    python3 scripts/slow_broker.py --port 9000

The response is one line holding a token, then padding to the requested size, written at a capped
rate. Padding and pacing are both the point: the caller must read to EOF, and the transfer has to
take real time. `?bytes=` and `?kbps=` set both per request, so the operator retunes without
restarting. `kbps=0` disables pacing, which is how a run proves the client can tell a fast link from
a slow one.

Binds 127.0.0.1 only. Nothing here should be reachable from the network, and the emulator does not
need it to be.

The token is a fixed synthetic string. It is not a credential, it is not accepted by anything, and it
carries no type tag that would make it resemble one. Nothing here mints, stores or logs a real token.
"""

import argparse
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# Obviously synthetic, and deliberately shaped like nothing real. The client only checks it is non-blank
# and differs from the token it is replacing.
STAND_IN_TOKEN = "not-a-real-token-manual-tier-stand-in"

# The token line is sent whole or not at all, so it is the floor on an exact body. Below it the response
# would be larger than the size asked for, and a paced transfer would take correspondingly longer than the
# operator's arithmetic predicts.
MIN_BYTES = len((STAND_IN_TOKEN + "\n").encode())

DEFAULT_BYTES = 256 * 1024
MAX_BYTES = 16 * 1024 * 1024

# EDGE/EGPRS down, the emulator's own figure for the profile it declines to enforce.
DEFAULT_KBPS = 473

# Small enough that pacing is smooth rather than a few long stalls, large enough not to syscall per byte.
CHUNK_BYTES = 4 * 1024


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _int_param(self, query, name, default, low, high):
        """Returns the parameter, or None after answering 400."""
        try:
            value = int(query.get(name, [default])[0])
        except ValueError:
            self.send_error(400, f"{name} must be an integer")
            return None
        if not low <= value <= high:
            self.send_error(400, f"{name} must be in {low}..{high}")
            return None
        return value

    def do_GET(self):  # noqa: N802 - name fixed by BaseHTTPRequestHandler
        query = parse_qs(urlparse(self.path).query)
        size = self._int_param(query, "bytes", DEFAULT_BYTES, MIN_BYTES, MAX_BYTES)
        if size is None:
            return
        # 0 means send as fast as the socket allows. A test uses it to show its own floor can fail.
        kbps = self._int_param(query, "kbps", self.server.default_kbps, 0, 10_000_000)
        if kbps is None:
            return

        # Exactly `size`, which the MIN_BYTES floor is what makes true: the token line always fits, so the
        # padding is never negative and the body never exceeds what was asked for.
        first_line = (STAND_IN_TOKEN + "\n").encode()
        body = first_line + b"." * (size - len(first_line))

        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()

        if kbps == 0:
            self.wfile.write(body)
            return

        # Paced against a fixed start rather than sleeping a constant per chunk, so the achieved rate does
        # not drift with however long each write actually took.
        bytes_per_second = kbps * 1000 / 8
        started = time.monotonic()
        for sent in range(0, len(body), CHUNK_BYTES):
            chunk = body[sent : sent + CHUNK_BYTES]
            due = (sent + len(chunk)) / bytes_per_second
            behind = due - (time.monotonic() - started)
            if behind > 0:
                time.sleep(behind)
            self.wfile.write(chunk)
            self.wfile.flush()

    def log_message(self, fmt, *args):
        # One line per request, without the token. The default handler logs the request line only, which
        # is already token-free, but this keeps that true if the path ever carries more.
        print(f"{self.command} {urlparse(self.path).path} -> 200")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument(
        "--kbps",
        type=int,
        default=DEFAULT_KBPS,
        help=f"default send rate when a request names none; 0 for unpaced (default {DEFAULT_KBPS})",
    )
    args = parser.parse_args()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.default_kbps = args.kbps
    print(f"stand-in broker on http://127.0.0.1:{args.port}/token")
    print(f"the emulator reaches it at   http://10.0.2.2:{args.port}/token")
    print(f"default rate {args.kbps} kbps; override per request with ?kbps=")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
