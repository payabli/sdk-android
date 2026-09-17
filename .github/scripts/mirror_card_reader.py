#!/usr/bin/env python3
"""Mirror the card reader onto the SDK artifact origin: fetch from the vendor, publish to the bucket.

Payabli distributes the card reader to its integrators rather than sending them to a third-party
feed, so the origin carries a copy. This produces and publishes that copy, in one run.

    python3 .github/scripts/mirror_card_reader.py --version 1.1.4.1

Ordinarily nobody runs this by hand. `card-reader-mirror.yml` dispatches it against a GitHub
Environment whose required reviewers gate the run, and the AWS identity is assumed over OIDC, so no
credential is held on anyone's machine. A by-hand run needs the environment below filled in and an
AWS identity that can write the bucket; read-only is what an SSO portal hands out by default and it
fails every upload after fetching everything.

WHAT IS MIRRORED, AND WHY ONLY THIS

`:taptopay` declares one dependency, the reader, whose POM pulls one private transitive plus fifteen
public artifacts — kotlin-stdlib, androidx, material, rxjava, okhttp, retrofit, play integrity,
safetynet, mediarouter. Every one of those resolves from `google()` or `mavenCentral()`, which an
integrator already declares, so only the two private ones are mirrored.

The transitive has no Gradle module metadata — the registry answers 404 for `.module` — which is
expected rather than a missing file, and Gradle falls back to the POM.

WHY A VERSION MUST BE PINNED BEFORE IT CAN BE MIRRORED

`RELEASES` is the record of which reader version each SDK release was certified against, so a
version is added when a release changes the reader and nothing is ever removed: an integrator on an
older SDK must still resolve the reader that version was certified with.

That is why `--version` will not take a version this file does not carry. A dispatch naming an
unpinned version fails and prints the block to add, which is a one-line pull request and a review of
the one decision worth reviewing — that this version is the one we intend to certify against.
Cross-check a new block against `gradle/libs.versions.toml`, which pins what the build consumes.

WHY --if-none-match

The condition makes S3 itself refuse a write whose key already exists, so a key already mirrored
answers 412 PreconditionFailed. That is success — the bytes are there and are immutable — and is
reported as `present`. Neither `aws s3 cp` nor `aws s3 sync` can set the header, which is why every
upload is a single-part `put-object`; that is valid to 5 GB against a largest artifact of 8 MB.

The publishing role's policy also denies an overwrite, but that binds the role and not the bucket, so
an admin identity running this by hand is not subject to it. The header is then the only thing
preventing a published coordinate from being replaced. Do not remove it to force a re-upload.

CONFIGURATION

Everything comes from the environment, and nothing that names an account or a bucket is written in
this file: this repository is public, and a role ARN carries an AWS account id.

    GPR_TOKEN              classic PAT with read:packages, for the vendor registry
    AWS_SDK_CDN_BUCKET     origin bucket
    AWS_SDK_CDN_ACCOUNT    account the bucket is in; the run refuses an identity elsewhere
    AWS_REGION             standard AWS variable, set by the credentials action
"""

# Annotations are lazy so the union syntax parses on the system Python, which is 3.9 on some benches.
from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import subprocess
import sys
import tempfile

REGISTRY = "https://maven.pkg.github.com/Fiserv/ch-ttp-androidsdk"

# Objects under maven/ are added and never replaced, so they carry the long immutable header.
CACHE_CONTROL = "max-age=31536000, immutable"

# Reader version -> every coordinate certified with it, as (group path, artifact, version,
# extensions). Append a block; never edit or remove one.
RELEASES = {
    "1.1.4.1": [
        ("com/fiserv/ch", "ttp-payment", "1.1.4.1", ("pom", "aar", "module")),
        ("com", "magiccube", "3.4.1", ("pom", "aar")),
    ],
}


def coordinates(version: str):
    """The pinned coordinates for a reader version, or a refusal naming what to add."""
    if version in RELEASES:
        return RELEASES[version]
    known = ", ".join(sorted(RELEASES)) or "none"
    sys.exit(
        f"reader version {version} is not pinned, so there is nothing recorded to mirror.\n"
        f"pinned: {known}\n\n"
        "Add it to RELEASES in this file first, cross-checked against gradle/libs.versions.toml:\n\n"
        f'    "{version}": [\n'
        f'        ("com/fiserv/ch", "ttp-payment", "{version}", ("pom", "aar", "module")),\n'
        '        ("com", "magiccube", "<its transitive version>", ("pom", "aar")),\n'
        "    ],\n\n"
        "The list is the record of what each release was certified against, which is why adding a\n"
        "version is a reviewed change rather than a dispatch input."
    )


def keys(version: str):
    for group, artifact, ver, exts in coordinates(version):
        for ext in exts:
            yield f"{group}/{artifact}/{ver}/{artifact}-{ver}.{ext}"


def require(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        sys.exit(f"{name} is not set. See CONFIGURATION in this file's header.")
    return value


def check_token(tok: str) -> None:
    """Fail on a dead token here rather than as a 401 on every artifact.

    A dead token answers 401 on every path including artifacts that plainly exist, which reads as a
    missing artifact rather than a stale credential.
    """
    code = subprocess.run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "20",
         "-H", f"Authorization: Bearer {tok}", "https://api.github.com/user"],
        capture_output=True, text=True,
    ).stdout.strip()
    if code != "200":
        sys.exit(
            f"the vendor token is not valid (api.github.com/user -> {code}).\n"
            "Renew it: a classic PAT with read:packages."
        )


def check_aws(expect_account: str) -> str:
    """Confirm the identity is in the origin's account, before anything is transferred.

    S3 bucket names are global but not reserved to us, so a credential pointing somewhere else does
    not reliably fail: it can AccessDenied per file, or find a bucket of this name that is not ours.

    This is not a write check. A read-only identity in the right account passes here, fetches
    everything and then fails every upload, which was measured on 2026-09-17.
    """
    r = subprocess.run(
        ["aws", "sts", "get-caller-identity", "--query", "[Arn,Account]", "--output", "text"],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        sys.exit(f"no usable AWS credentials.\n{r.stderr.strip()}")
    arn, _, account = r.stdout.strip().partition("\t")
    account = account.strip()
    if account != expect_account:
        sys.exit(
            f"that identity is in account {account}, and the origin is in {expect_account}.\n"
            f"  {arn}"
        )
    return arn


def fetch(tok: str, version: str, out: pathlib.Path):
    print(f"{'key':<62} {'bytes':>10}  sha256")
    print("-" * 96)
    got, failed = [], 0
    for rel in keys(version):
        dest = out / "maven" / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["curl", "-sL", "-H", f"Authorization: Bearer {tok}",
             "-o", str(dest), "--max-time", "300", f"{REGISTRY}/{rel}"],
            check=True,
        )
        data = dest.read_bytes()
        # A refusal body is short text or JSON. An artifact is neither.
        if len(data) < 2048 and b"<project" not in data:
            print(f"maven/{rel:<56} {'FAILED':>10}  {data[:60]!r}")
            dest.unlink(missing_ok=True)
            failed += 1
            continue
        print(f"maven/{rel:<56} {len(data):>10}  {hashlib.sha256(data).hexdigest()[:16]}…")
        got.append((rel, dest))
    if failed:
        sys.exit(f"\n{failed} file(s) failed to fetch. Nothing partial was kept, nothing published.")
    print("-" * 96)
    print(f"{sum(p.stat().st_size for _, p in got):,} bytes")
    return got


def publish(files, bucket: str) -> int:
    print(f"\n{'key':<62} result")
    print("-" * 96)
    uploaded = present = failed = 0
    for rel, path in files:
        key = f"maven/{rel}"
        r = subprocess.run(
            ["aws", "s3api", "put-object", "--bucket", bucket, "--key", key,
             "--body", str(path), "--if-none-match", "*", "--cache-control", CACHE_CONTROL],
            capture_output=True, text=True,
        )
        if r.returncode == 0:
            print(f"{key:<62} uploaded")
            uploaded += 1
        elif "PreconditionFailed" in r.stderr or "412" in r.stderr:
            # Already mirrored, and these bytes are immutable, so this is the steady state on every
            # run after the first rather than a failure.
            print(f"{key:<62} present")
            present += 1
        else:
            tail = r.stderr.strip().splitlines()[-1][:58] if r.stderr.strip() else ""
            print(f"{key:<62} FAILED  {tail}")
            failed += 1
    print("-" * 96)
    print(f"{uploaded} uploaded, {present} already present, {failed} failed")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--version", required=True,
                    help="reader version to mirror; must be pinned in RELEASES")
    ap.add_argument("--fetch-only", action="store_true", help="do not publish")
    ap.add_argument("--out", type=pathlib.Path,
                    help="keep the maven/ tree here instead of a temporary directory")
    args = ap.parse_args()

    # Before anything else, so an unpinned version costs no network at all.
    coordinates(args.version)

    # Every configuration value is read before the first network call, so a run missing one says so
    # instantly rather than after a token round trip.
    tok = require("GPR_TOKEN")
    bucket = account = None
    if not args.fetch_only:
        bucket = require("AWS_SDK_CDN_BUCKET")
        account = require("AWS_SDK_CDN_ACCOUNT")

    check_token(tok)
    if not args.fetch_only:
        # Before the download, so a wrong identity is not found after nine megabytes of transfer.
        print(f"publishing to {bucket} as {check_aws(account)}\n")

    tmp = None
    out = args.out
    if out is None:
        tmp = tempfile.TemporaryDirectory(prefix="card-reader-mirror-")
        out = pathlib.Path(tmp.name)

    try:
        files = fetch(tok, args.version, out)
        if args.fetch_only:
            print(f"\nFetched only, into {out / 'maven'}. Re-run without --fetch-only to publish.")
            return 0
        return publish(files, bucket)
    finally:
        if tmp is not None:
            tmp.cleanup()


if __name__ == "__main__":
    sys.exit(main())
