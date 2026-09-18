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
unpinned version fails and prints the block to add. Adding it is a pull request, and a review of the
one decision worth reviewing — that this version is the one we intend to certify against.
Cross-check a new block against `gradle/libs.versions.toml`, which pins what the build consumes.

WHY --if-none-match

The condition makes S3 itself refuse a write whose key already exists, so a key already mirrored
answers 412 PreconditionFailed. That says the key is occupied and nothing about what occupies it, so
the object is read back and its digest compared before the run calls it `present`. A coordinate some
bad run populated would otherwise report present for ever, and nothing here could put it right.

Neither `aws s3 cp` nor `aws s3 sync` can set the header, which is why every upload is a single-part
`put-object`; that is valid to 5 GB against a largest artifact of 8 MB.

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

import argparse
import contextlib
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
# {extension: sha256}). Append a block; never edit or remove one.
#
# The digests are what make a fetch verifiable. A key written here is immutable, so bytes that are
# not what we mean to publish cannot be corrected afterwards by this tool or any other: the write is
# refused, and every later run reports the key as already present. So the artifact is checked against
# a digest recorded when the version was certified, rather than trusted because the transfer returned.
#
# A digest may be recorded as None, which means the coordinate is known and its bytes are not yet.
# `--fetch-only` accepts that and prints what it got; publishing refuses it. That is how a new version
# is added without the recorded digest having to exist before anything can fetch the bytes it
# describes: add a coordinate with None, run --fetch-only, read the digests, commit them. Both halves
# are reviewed, and no unverified byte reaches the origin in between. The order in full, including how
# the private transitive's own version is discovered, is what an unpinned version prints, and that
# refusal is the one copy of it.
RELEASES = {
    "1.1.4.1": [
        ("com/fiserv/ch", "ttp-payment", "1.1.4.1", {
            "pom": "bc958328f0f14e0ed2a6707211c2fbeb07b25da58f84a62f7915ac2519b91c8e",
            "aar": "9fae867347e664511a0615f9021b383c5096fd11a5824406399b38c13d9f5500",
            "module": "7f860bdad099f0112dab8fbd4bedf1075098b7d5dc0ed8f99428add6820fb7c0",
        }),
        ("com", "magiccube", "3.4.1", {
            "pom": "17abd03b0bb856e2cfe0142472b59bd7a6456f951d4174c3ea530be9d9fe9886",
            "aar": "629d36584c66cc0c468ca27747e148b12efbafbf7882773e9bbd8013f1c52cd2",
        }),
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
        "Add it to RELEASES in this file, cross-checked against gradle/libs.versions.toml. The\n"
        "reader's own coordinate goes in first and alone, with no digests:\n\n"
        f'    "{version}": [\n'
        f'        ("com/fiserv/ch", "ttp-payment", "{version}", '
        '{"pom": None, "aar": None, "module": None}),\n'
        "    ],\n\n"
        "Then fetch it, keeping the tree:\n\n"
        f"    --version {version} --fetch-only --out DIR\n\n"
        "--out is required for this step rather than optional: without it the tree is a temporary\n"
        "directory this script deletes, and the POM goes with it. That run prints the three digests\n"
        "to record and writes the POM which declares the private transitive's version:\n\n"
        f"    DIR/maven/com/fiserv/ch/ttp-payment/{version}/ttp-payment-{version}.pom\n\n"
        "Add the transitive with the version read out of it, again with no digests:\n\n"
        '        ("com", "magiccube", "<the version that POM declares>", '
        '{"pom": None, "aar": None}),\n\n'
        "A second --fetch-only prints its two. A coordinate naming a version the registry does not\n"
        "carry answers 404 and fails the run, which is why the transitive is not guessed and not\n"
        "left as a placeholder.\n\n"
        "Publishing refuses any coordinate whose digest is still None, so both sets of recorded\n"
        "values are committed before anything is uploaded, and no unverified byte reaches the\n"
        "origin in between. The list is the record of what each release was certified against,\n"
        "which is why adding a version is a reviewed change rather than a dispatch input."
    )


def keys(version: str):
    """Each mirrored key for a reader version, with the digest it is required to have."""
    for group, artifact, ver, digests in coordinates(version):
        for ext, want in digests.items():
            yield f"{group}/{artifact}/{ver}/{artifact}-{ver}.{ext}", want


@contextlib.contextmanager
def auth_config(tok: str):
    """A 0600 curl config carrying the Authorization header, removed on the way out.

    The header is not passed as an argument because a process's argv is readable by any other process
    on the machine — `ps`, or /proc — and Actions' log masking does nothing about that. The by-hand
    run this file documents is on a workstation, where that matters most.

    curl is kept rather than urllib for one property: it drops the Authorization header on a redirect
    to another host, and the registry redirects artifact bodies to backing storage.
    """
    fd, path = tempfile.mkstemp(prefix="mirror-auth-")
    try:
        with os.fdopen(fd, "w") as f:
            f.write(f'header = "Authorization: Bearer {tok}"\n')
        yield path
    finally:
        os.unlink(path)


def require(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        sys.exit(f"{name} is not set. See CONFIGURATION in this file's header.")
    return value


def check_token(cfg: str) -> None:
    """Fail on a dead token here rather than as a 401 on every artifact.

    A dead token answers 401 on every path including artifacts that plainly exist, which reads as a
    missing artifact rather than a stale credential.
    """
    code = subprocess.run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "20",
         "--config", cfg, "https://api.github.com/user"],
        capture_output=True, text=True,
    ).stdout.strip()
    if code in ("", "000"):
        # curl reports 000 when no response arrived: DNS, connect, proxy, or the timeout. Calling that
        # a bad credential sends someone to rotate a working one.
        sys.exit("could not reach api.github.com to check the vendor token. Network, not credential.")
    if code != "200":
        sys.exit(
            f"the vendor token is not valid (api.github.com/user -> {code}).\n"
            "Renew it: a classic PAT with read:packages.\n"
            "This answers 200 for any live token, whatever its scopes, so a token without\n"
            "read:packages passes here and is refused per artifact with HTTP 401 instead."
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
    # The resource half only. A full ARN carries the account id, and this run's log is world-readable
    # on a public repository — which is the same reason the id is not written in this file.
    resource = arn.split(":", 5)[-1]
    parts = resource.split("/")
    return "/".join(parts[:2]) if parts[0] == "assumed-role" else resource


def fetch(cfg: str, version: str, out: pathlib.Path):
    """Fetch every key for a version, and accept one only if its digest is the recorded one.

    `-f` so an HTTP refusal is never written to the file: curl exits 0 on a 404 or a 401 and would
    otherwise save the refusal body under the artifact's name, where only its shape distinguishes it
    from the artifact. The digest is what settles that, and a size threshold is not: small artifacts
    are ordinary, and a proxy's error page is not small.

    Nothing here raises on a non-zero exit: a CalledProcessError carries the whole command into a
    traceback, and an argv is the wrong place for anything to end up that should not be read.
    """
    print(f"{'key':<62} {'bytes':>10}  sha256")
    print("-" * 96)
    got, failed = [], 0
    for rel, want in keys(version):
        dest = out / "maven" / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        r = subprocess.run(
            ["curl", "-sfL", "--config", cfg,
             "-o", str(dest), "--max-time", "300", "-w", "%{http_code}", f"{REGISTRY}/{rel}"],
            capture_output=True, text=True,
        )
        code = r.stdout.strip() or "000"
        if r.returncode != 0:
            # 000 is curl's answer when no response arrived at all: DNS, connect, or the timeout.
            why = f"HTTP {code}" if code not in ("", "000") else f"no response (curl {r.returncode})"
            print(f"maven/{rel:<56} {'FAILED':>10}  {why}")
            dest.unlink(missing_ok=True)
            failed += 1
            continue
        data = dest.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if want is None:
            # Recorded as unknown, so there is nothing to check against. Full digest, because this is
            # the run whose output becomes the recorded value.
            print(f"maven/{rel:<56} {len(data):>10}  {digest}  UNVERIFIED")
            got.append((rel, dest, want))
            continue
        if digest != want:
            # Both in full, on their own lines. Truncated they are unreadable in the case that
            # matters: two digests of the same artifact differ somewhere, and rarely in the first
            # sixteen characters, so an abbreviated pair reads as identical.
            print(f"maven/{rel:<56} {'FAILED':>10}  digest mismatch")
            print(f"    got      {digest}")
            print(f"    recorded {want}")
            dest.unlink(missing_ok=True)
            failed += 1
            continue
        print(f"maven/{rel:<56} {len(data):>10}  {digest}")
        got.append((rel, dest, want))
    if failed:
        sys.exit(f"\n{failed} file(s) failed to fetch or did not match. Nothing was published.")
    print("-" * 96)
    print(f"{sum(p.stat().st_size for _, p, _w in got):,} bytes")
    return got


def put(bucket: str, account: str, key: str, path: pathlib.Path):
    return subprocess.run(
        ["aws", "s3api", "put-object", "--bucket", bucket, "--key", key,
         "--body", str(path), "--if-none-match", "*", "--cache-control", CACHE_CONTROL,
         # The bucket has to belong to the account as well as the caller. Checking the caller's
         # account alone leaves a mistyped bucket name resolving to somebody else's bucket, since
         # S3 names are global but not reserved to us.
         "--expected-bucket-owner", account],
        capture_output=True, text=True,
    )


def read_back(bucket: str, account: str, key: str):
    """The sha256 of what is actually at a key, or None if it could not be read.

    The publishing role is granted s3:GetObject on the vendor prefixes for exactly this.
    """
    with tempfile.TemporaryDirectory(prefix="mirror-readback-") as d:
        dest = pathlib.Path(d) / "object"
        r = subprocess.run(
            ["aws", "s3api", "get-object", "--bucket", bucket, "--key", key,
             "--expected-bucket-owner", account, str(dest)],
            capture_output=True, text=True,
        )
        if r.returncode != 0 or not dest.exists():
            return None
        return hashlib.sha256(dest.read_bytes()).hexdigest()


def publish(files, bucket: str, account: str) -> int:
    unverified = [rel for rel, _, want in files if want is None]
    if unverified:
        sys.exit(
            "these coordinates have no recorded digest, so their bytes were accepted unchecked:\n  "
            + "\n  ".join(unverified)
            + "\n\nRecord the digests printed above in RELEASES, then publish. Nothing was published."
        )
    print(f"\n{'key':<62} result")
    print("-" * 96)
    uploaded = present = failed = 0
    for rel, path, _want in files:
        key = f"maven/{rel}"
        r = put(bucket, account, key, path)
        # `PreconditionFailed` alone, never the bare status: stderr carries the bucket, the region and
        # the key, so a substring test for "412" reclassifies a real failure as success whenever one
        # of those happens to contain it, and the run then exits 0 having uploaded nothing.
        if r.returncode != 0 and "ConditionalRequestConflict" in r.stderr:
            # A concurrent write to the same key. S3 documents this as retryable, and it is reachable
            # here because the workflow's concurrency group cannot serialise a by-hand run beside it.
            r = put(bucket, account, key, path)
        if r.returncode == 0:
            print(f"{key:<62} uploaded")
            uploaded += 1
        elif "PreconditionFailed" in r.stderr:
            # Already mirrored, which is the steady state on every run after the first. It says the
            # key is occupied and nothing about what occupies it, so the bytes are read back and
            # compared: a coordinate populated by a bad run or by hand would otherwise report present
            # for ever, and --if-none-match means no later run can put it right.
            remote = read_back(bucket, account, key)
            if remote is None:
                print(f"{key:<62} FAILED  present, and could not be read back to verify")
                failed += 1
            elif remote != _want:
                print(f"{key:<62} FAILED  present with different bytes")
                print(f"    at origin {remote}")
                print(f"    recorded  {_want}")
                failed += 1
            else:
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
        # Required here rather than left to the AWS CLI, because the two checks below do not need it and
        # every upload does: `put` and `read_back` name no --region, so the region comes from the
        # environment. STS is global, so check_aws passes without one and the whole fetch follows, and the
        # first thing that reports a missing region is the first upload. The workflow's publishing job has
        # it from configure-aws-credentials; a run by hand is what this catches.
        require("AWS_REGION")
        # Before the download, so a wrong identity is not found after nine megabytes of transfer.
        print(f"publishing to {bucket} as {check_aws(account)}\n")

    tmp = None
    out = args.out
    if out is None:
        tmp = tempfile.TemporaryDirectory(prefix="card-reader-mirror-")
        out = pathlib.Path(tmp.name)

    try:
        with auth_config(tok) as cfg:
            check_token(cfg)
            files = fetch(cfg, args.version, out)
        if args.fetch_only:
            # Only name a path that still exists afterwards. Without --out the tree is a temporary
            # directory this function removes, so naming it sends someone to look at nothing.
            where = f" into {out / 'maven'}" if args.out else ", keeping nothing (pass --out to keep it)"
            print(f"\nFetched only{where}. Re-run without --fetch-only to publish.")
            return 0
        return publish(files, bucket, account)
    finally:
        if tmp is not None:
            tmp.cleanup()


if __name__ == "__main__":
    sys.exit(main())
