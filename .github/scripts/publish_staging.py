#!/usr/bin/env python3
"""Upload a Gradle staging repository to the SDK artifact origin, one object at a time.

    python3 .github/scripts/publish_staging.py --prefix maven-qa --version 0.1.0-QA.20260201143000

`./gradlew publish` writes a complete Maven layout under `build/staging-repo`. Gradle sends nothing:
the IAM policy denies PutObject without `If-None-Match: *`, which Gradle's Maven publisher cannot
set, so the upload is this separate step.

Ordinarily nobody runs this by hand. `qa-snapshot.yml` and `release.yml` assume an AWS identity over
OIDC, so no credential is held on anyone's machine, and the access portal grants ReadOnlyAccess on
this account anyway.

WHY --if-none-match

The condition makes S3 refuse a write whose key already exists, so an occupied key answers 412
PreconditionFailed. That says the key is taken and nothing about what took it, so the object is read
back and its digest compared before the run calls it `present`.

On `/maven/*` that is the steady state of a re-run: the policy denies overwrite, coordinates are
write-once, and a half-finished run has to be completable. On `/maven-qa/*` overwrite is permitted,
but a 412 there still means two builds produced one identifier, which the timestamp exists to make
impossible — so it is reported rather than overwritten.

Neither `aws s3 cp` nor `aws s3 sync` can set the header, which is why every upload is a single-part
`put-object`.

CONFIGURATION

Everything comes from the environment, and nothing naming an account or a bucket is written here:
this repository is public, and a role ARN carries an AWS account id.

    AWS_SDK_CDN_BUCKET     origin bucket
    AWS_SDK_CDN_ACCOUNT    account the bucket is in; the run refuses an identity elsewhere
    AWS_REGION             standard AWS variable, set by the credentials action
"""

import argparse
import hashlib
import os
import pathlib
import subprocess
import sys
import tempfile

# Objects under maven/ are written once and never replaced, so they carry the long immutable header.
# A QA coordinate is republished under a new identifier every build and must not be held by a cache
# between the upload and the consumer that asked for it.
CACHE_CONTROL = {
    "maven": "max-age=31536000, immutable",
    "maven-qa": "no-cache",
}


def require(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        sys.exit(f"{name} is not set.")
    return value


def check_aws(expect_account: str) -> str:
    """Refuse an identity outside the origin's account, and return it for the log.

    Not a write check: a read-only identity in the right account passes here and then fails every
    upload. The account portal grants exactly that, so it is the likely shape of a run by hand.
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
        sys.exit(f"that identity is in account {account}, and the origin is in {expect_account}.\n  {arn}")
    # The resource half only. A full ARN carries the account id, and this run's log is world-readable.
    resource = arn.split(":", 5)[-1]
    parts = resource.split("/")
    return "/".join(parts[:2]) if parts[0] == "assumed-role" else resource


def collect(staging: pathlib.Path, version: str):
    """Every publishable file under the staging tree, with the key suffix it publishes at.

    `maven-metadata.xml` and its checksums are left behind. Gradle writes one per artifact listing the
    versions this tree holds, which is the one build that produced it. On `/maven/*` the key cannot be
    replaced, so the first release would fix a listing naming a single version for good, and every later
    release would report it present with different bytes. Nothing reads it: a consumer pins an exact
    version, which is what the BOM is for.

    The version is asserted rather than trusted. Gradle writes into this tree and never cleans it, so a
    run whose build failed, or one following a build at another version, leaves artifacts that would
    otherwise be published as though this run had produced them.
    """
    if not staging.is_dir():
        sys.exit(f"{staging} does not exist. Run `./gradlew publish` first.")
    files = sorted(
        p for p in staging.rglob("*")
        if p.is_file() and not p.name.startswith("maven-metadata.xml")
    )
    if not files:
        sys.exit(f"{staging} is empty. Run `./gradlew publish` first.")
    stray = [p for p in files if f"/{version}/" not in f"/{p.relative_to(staging).as_posix()}"]
    if stray:
        sys.exit(
            f"{len(stray)} file(s) under {staging} are not at version {version}, so this tree holds more\n"
            "than this run produced. Delete it and publish again. First few:\n  "
            + "\n  ".join(str(p.relative_to(staging)) for p in stray[:5])
        )
    return [(p, p.relative_to(staging).as_posix()) for p in files]


def put(bucket: str, account: str, key: str, path: pathlib.Path, cache_control: str):
    return subprocess.run(
        ["aws", "s3api", "put-object", "--bucket", bucket, "--key", key,
         "--body", str(path), "--if-none-match", "*", "--cache-control", cache_control,
         # The bucket has to belong to the account as well as the caller: S3 names are global and not
         # reserved to us, so a mistyped bucket can resolve to somebody else's.
         "--expected-bucket-owner", account],
        capture_output=True, text=True,
    )


def read_back(bucket: str, account: str, key: str):
    """The sha256 of what is at a key, or None if it could not be read."""
    with tempfile.TemporaryDirectory(prefix="publish-readback-") as d:
        dest = pathlib.Path(d) / "object"
        r = subprocess.run(
            ["aws", "s3api", "get-object", "--bucket", bucket, "--key", key,
             "--expected-bucket-owner", account, str(dest)],
            capture_output=True, text=True,
        )
        if r.returncode != 0 or not dest.exists():
            return None
        return hashlib.sha256(dest.read_bytes()).hexdigest()


def publish(files, bucket: str, account: str, prefix: str) -> int:
    cache_control = CACHE_CONTROL[prefix]
    print(f"\n{'key':<72} result")
    print("-" * 96)
    uploaded = present = failed = 0
    for path, rel in files:
        key = f"{prefix}/{rel}"
        r = put(bucket, account, key, path, cache_control)
        # `ConditionalRequestConflict` by name, never a bare status: stderr carries the bucket, the
        # region and the key, so a substring test for a number reclassifies a real failure whenever one
        # of those happens to contain it.
        if r.returncode != 0 and "ConditionalRequestConflict" in r.stderr:
            # A concurrent write to the same key, which S3 documents as retryable.
            r = put(bucket, account, key, path, cache_control)
        if r.returncode == 0:
            print(f"{key:<72} uploaded")
            uploaded += 1
        elif "PreconditionFailed" in r.stderr:
            remote = read_back(bucket, account, key)
            local = hashlib.sha256(path.read_bytes()).hexdigest()
            if remote is None:
                print(f"{key:<72} FAILED  present, and could not be read back to verify")
                failed += 1
            elif remote != local:
                print(f"{key:<72} FAILED  present with different bytes")
                print(f"    at origin {remote}")
                print(f"    this run  {local}")
                failed += 1
            else:
                print(f"{key:<72} present")
                present += 1
        else:
            tail = r.stderr.strip().splitlines()[-1][:58] if r.stderr.strip() else ""
            print(f"{key:<72} FAILED  {tail}")
            failed += 1
    print("-" * 96)
    print(f"{uploaded} uploaded, {present} already present, {failed} failed")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    # A choice rather than free text: a mistyped prefix writes a tree to a path the distribution does
    # not serve, and nothing downstream would report it.
    ap.add_argument("--prefix", required=True, choices=sorted(CACHE_CONTROL),
                    help="origin prefix to publish under")
    ap.add_argument("--version", required=True,
                    help="the version this run built; every file in the tree must be at it")
    ap.add_argument("--staging-dir", type=pathlib.Path, default=pathlib.Path("build/staging-repo"),
                    help="the Maven layout ./gradlew publish wrote")
    args = ap.parse_args()

    # Read before the first network call, so a run missing one says so instantly.
    bucket = require("AWS_SDK_CDN_BUCKET")
    account = require("AWS_SDK_CDN_ACCOUNT")
    # Required here rather than left to the AWS CLI: STS is global, so the account check passes without
    # a region and the first thing to report its absence would be the first upload.
    require("AWS_REGION")

    files = collect(args.staging_dir, args.version)
    print(f"publishing {len(files)} object(s) to {bucket}/{args.prefix} as {check_aws(account)}")
    return publish(files, bucket, account, args.prefix)


if __name__ == "__main__":
    sys.exit(main())
