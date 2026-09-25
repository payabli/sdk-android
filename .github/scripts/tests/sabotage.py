#!/usr/bin/env python3
"""Break each claimed behaviour of the nightly reporter, confirm a check goes red, restore.

Every mutation rewrites a copy in a scratch directory and never the file in the working tree, so a run that
dies halfway through leaves nothing behind to repair. That is what makes this safe to run in CI and safe to
interrupt locally.

Three safeguards the repo's PR rules require, because a sabotage harness that lies is worse than none:

  * the anchor must match exactly once. A patch that matches twice is aimed at the wrong site, and one
    that matches zero times silently tests nothing
  * the patched file must still compile. A patch that breaks the parse would make the harness fail for
    the wrong reason, or in the collector's case fail to run at all and be scored as "not caught"
  * the file is restored from the pristine copy every time, pass or fail

Prints a table in the format the PR body wants: a high number in the right column is the good outcome.
"""

from __future__ import annotations

import os
import py_compile
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - the message is the point
    # A traceback with no verdict is the one outcome this harness rules out: zero FAIL lines reads exactly
    # like zero failures. CI has PyYAML on the image and asserts it in the job; a bench that does not says so
    # here, in one line, with the way to fix it.
    print("This harness needs PyYAML to read the workflow files: python3 -m pip install pyyaml")
    raise SystemExit(2)

HERE = Path(__file__).resolve().parent
# Not `os.environ.get("NIGHTLY_SDK", HERE.parents[2])`: a default argument is evaluated eagerly, so the
# fallback would raise IndexError for a copy of this file sitting fewer than three directories deep even
# when the override is set, which is the one situation the override exists for.
_sdk = os.environ.get("NIGHTLY_SDK")
SDK = Path(_sdk) if _sdk else HERE.parents[2]
VERIFY = HERE / "verify.py"

# COLLECTOR and POSTER are the copies the mutations rewrite. SOURCE maps each one back to the file in the
# working tree, which is opened for reading only: it supplies every anchor, and it is what each copy is
# restored from. Nothing here ever writes into the repository.
WORK = Path(tempfile.mkdtemp(prefix="nightly-sabotage-"))
COLLECTOR = WORK / "nightly_report.py"
POSTER = WORK / "nightly_slack.py"
# The archive a nightly test job hands to `verdict`.
PACK = WORK / "pack_results.sh"
# The live workflows are mutated too, because what keeps a client credential out of the emulator step and out
# of a fork's reach is how those files are written. A copy each, in the same scratch directory, so the same
# guarantee holds: nothing here writes into the repository.
WORKFLOW_DIR = WORK / "workflows"
WORKFLOW_DIR.mkdir(exist_ok=True)
LIVE_FLOWS = WORKFLOW_DIR / "live-flows.yml"
LIVE_QA = WORKFLOW_DIR / "live-qa.yml"
LIVE_SANDBOX = WORKFLOW_DIR / "live-sandbox.yml"
# The harness workflow, for its path filter alone: a check that never runs on the file it guards is not
# a check, and the filter is what decides.
SCRIPTS = WORKFLOW_DIR / "scripts.yml"
# The nightly: its liveness owner, its module list, and where the emulator action may run.
NIGHTLY = WORKFLOW_DIR / "nightly.yml"
# The card reader mirror, whose two jobs are the only thing keeping the credential that writes the artifact
# origin out of a run that publishes nothing. Every mutation below widens that back, and none of them looks
# like more than a tidy-up in a diff.
MIRROR = WORKFLOW_DIR / "card-reader-mirror.yml"

# The QA snapshot, whose channel, trigger and identifier are each green while wrong: a tree at the wrong
# prefix uploads, a shared coordinate publishes, and a stamp that stops sorting still builds.
QA = WORKFLOW_DIR / "qa-snapshot.yml"

# The uploader. Its branches decide whether a key already at the origin is accepted, and every wrong
# answer is a publish that reports success.
PUBLISHER = WORK / "publish_staging.py"

# Per-pull-request CI, for the card reader credential alone. :taptopay resolves the reader from a
# credentialed repository, and this file keeps that credential out of the job the third-party emulator
# action runs in. The mutations below put it back, each as something that reads like a convenience.
CI = WORKFLOW_DIR / "ci.yml"

# The sample app's invocation, quoted in two mutations below. One spelling, because a mutation whose anchor
# no longer matches the file reports itself invalid rather than caught, and two copies drift apart silently.
WALKTHROUGH_COMMAND = (
    "            ./gradlew :example:connectedWithTelemetryDebugAndroidTest "
    "-Ppayabli.sampleWalkthrough=true -Ppayabli.demo.prefill=true"
)
# The nightly's instrumented module list, quoted by four mutations below. One spelling, for the reason the
# walkthrough command has one: an anchor that no longer matches reports itself invalid rather than caught.
INSTRUMENTED_MODULES_LINE = "          INSTRUMENTED_MODULES: core,payin"
# The live reporter, whose allowlist is what keeps a submitted value out of the channel.
LIVE_POSTER = WORK / "live_slack.py"
SOURCE = {
    COLLECTOR: SDK / ".github/scripts/nightly_report.py",
    POSTER: SDK / ".github/scripts/nightly_slack.py",
    PACK: SDK / ".github/scripts/pack_results.sh",
    LIVE_POSTER: SDK / ".github/scripts/live_slack.py",
    LIVE_FLOWS: SDK / ".github/workflows/live-flows.yml",
    LIVE_QA: SDK / ".github/workflows/live-qa.yml",
    LIVE_SANDBOX: SDK / ".github/workflows/live-sandbox.yml",
    NIGHTLY: SDK / ".github/workflows/nightly.yml",
    SCRIPTS: SDK / ".github/workflows/scripts.yml",
    MIRROR: SDK / ".github/workflows/card-reader-mirror.yml",
    QA: SDK / ".github/workflows/qa-snapshot.yml",
    PUBLISHER: SDK / ".github/scripts/publish_staging.py",
    CI: SDK / ".github/workflows/ci.yml",
}

# (description, target file, half to run, anchor, replacement)
MUTATIONS = [
    ("QA snapshot gains a trigger of its own, so it publishes beside CI rather than after it", QA,
     "workflows",
     "  workflow_call:\n", "  workflow_call:\n  push:\n    branches: [main]\n"),

    ("QA snapshot triggers on a tag, which the snapshot role cannot assume", QA, "workflows",
     "on:\n  workflow_dispatch:\n", "on:\n  workflow_dispatch:\n  push:\n    tags: ['*']\n"),

    ("QA snapshot names a checkout ref, so it builds something other than what CI tested", QA,
     "workflows",
     "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7\n",
     "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7\n"
     "        with:\n          ref: main\n"),

    ("CI hands the publisher every secret the repository holds", CI, "workflows",
     "    secrets:\n      PAYABLI_MAVEN_US_PROD: ${{ secrets.PAYABLI_MAVEN_US_PROD }}\n"
     "      PAYABLI_MAVEN_PW_PROD: ${{ secrets.PAYABLI_MAVEN_PW_PROD }}\n",
     "    secrets: inherit\n"),

    ("CI grants the publishing token at the workflow level, so every job inherits it", CI, "workflows",
     "permissions:\n  contents: read\n", "permissions:\n  contents: read\n  id-token: write\n"),

    ("QA snapshot declares the card reader credential for the whole workflow", QA, "workflows",
     "permissions:\n  contents: read\n", "env:\n  PAYABLI_MAVEN_USER: x\npermissions:\n  contents: read\n"),

    ("CI publishes without waiting for any job", CI, "workflows",
     "    needs: [card-present, sonar]\n", ""),

    ("CI publishes before the instrumented suites finish", CI, "workflows",
     "    needs: [card-present, sonar]", "    needs: [card-present, build]"),

    ("A job CI waits for is allowed to fail without failing the run", CI, "workflows",
     "  instrumented:\n", "  instrumented:\n    continue-on-error: true\n"),

    ("A step of a job CI waits for is allowed to fail, leaving the job green", CI, "workflows",
     "      - name: Unit tests\n        run: ./gradlew :core:test",
     "      - name: Unit tests\n        continue-on-error: true\n        run: ./gradlew :core:test"),

    ("A dispatched QA snapshot masks a failed suite with || true", QA, "workflows",
     ":taptopay:test :example:test", ":taptopay:test :example:test || true"),

    # `-e` does not apply to a command in an AND list other than the last, so the suite's failure is read
    # as an answer and the step carries on to the line after it. Nothing is declared and no idiom from any
    # denylist appears.
    ("CI masks a failed unit suite on the left of an AND list", CI, "workflows",
     "        run: ./gradlew :core:test :payin:test :telemetry:test :testutils:test\n",
     "        run: |\n          ./gradlew :core:test :payin:test :telemetry:test :testutils:test"
     " && echo passed\n          echo finished\n"),

    # Split across a continuation, so neither physical line is a finding on its own: the first is an
    # unterminated escape that tokenises to nothing, and the second is an operator with no command in
    # front of it. The shell runs one masked command.
    ("CI masks a failed unit suite across a line continuation", CI, "workflows",
     "        run: ./gradlew :core:test :payin:test :telemetry:test :testutils:test\n",
     "        run: |\n          ./gradlew :core:test :payin:test :telemetry:test :testutils:test \\\n"
     "          || true\n"),

    # Nothing follows the command at all: the shell inverts its status, so the red suite reports success
    # and errexit does not apply to a command it negates. Every suite name is still there.
    #
    # Quoted, because unquoted it would not be this at all: a leading `!` in a plain scalar is YAML's tag
    # indicator and the loader strips it, so the shell receives the command it always ran and the
    # mutation would pass for a reason that has nothing to do with the check.
    ("CI negates a unit suite, so a red one reports success", CI, "workflows",
     "        run: ./gradlew :core:test :payin:test :telemetry:test :testutils:test\n",
     '        run: "! ./gradlew :core:test :payin:test :telemetry:test :testutils:test"\n'),

    # Nothing is declared: no continue-on-error, no shell override, and the command still exits 0 after
    # a red suite. The job the publish waits for is green and the snapshot goes out behind it.
    ("CI masks a failed unit suite with a command the publish never reads", CI, "workflows",
     "        run: ./gradlew :core:test :payin:test :telemetry:test :testutils:test\n",
     "        run: ./gradlew :core:test :payin:test :telemetry:test :testutils:test || echo ignored\n"),

    ("A dispatched QA snapshot runs the suites under a shell without -e", QA, "workflows",
     "      - name: Unit tests\n        if:", "      - name: Unit tests\n        shell: bash {0}\n        if:"),

    ("CI cancels a run on main, and with it a publish part way through its upload", CI, "workflows",
     "  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}", "  cancel-in-progress: true"),

    ("CI cancels a run on main through an expression that is not the word true", CI, "workflows",
     "  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}",
     "  cancel-in-progress: ${{ github.ref == github.ref }}"),

    ("QA snapshot names the uploader in an echo, so its flags are read instead", QA, "workflows",
     "          python3 .github/scripts/publish_staging.py --prefix maven-qa --version \"$VERSION\"",
     "          echo publish_staging.py --prefix maven-qa\n"
     "          python3 .github/scripts/publish_staging.py --prefix maven --version \"$VERSION\""),

    ("QA snapshot names gradlew in an echo and publishes a fixed version", QA, "workflows",
     '        run: ./gradlew publish -Ppayabli.version="$VERSION"',
     '        run: |\n          echo gradlew -Ppayabli.version="$VERSION"\n'
     '          ./gradlew publish -Ppayabli.version=0.1.0'),

    ("QA snapshot echoes the QA prefix and hands the uploader the release one", QA, "workflows",
     "          python3 .github/scripts/publish_staging.py --prefix maven-qa --version \"$VERSION\"",
     "          echo --prefix maven-qa\n"
     "          python3 .github/scripts/publish_staging.py --version \"$VERSION\" --prefix maven"),

    ("QA snapshot publishes a version the workflow fixed rather than the stamp", QA, "workflows",
     'run: ./gradlew publish -Ppayabli.version="$VERSION"',
     'run: |\n          echo -Ppayabli.version=$VERSION\n'
     '          ./gradlew publish -Ppayabli.version=0.1.0'),

    ("QA snapshot quotes the release prefix and names the QA one in a trailing comment", QA, "workflows",
     "          python3 .github/scripts/publish_staging.py --prefix maven-qa --version \"$VERSION\"",
     "          python3 .github/scripts/publish_staging.py --prefix \"maven\" --version \"$VERSION\"  "
     "# --prefix maven-qa"),

    ("QA snapshot publishes without the credential that resolves the card reader", QA, "workflows",
     "          VERSION: ${{ steps.name.outputs.version }}\n"
     "          PAYABLI_MAVEN_USER: ${{ secrets.PAYABLI_MAVEN_US_PROD }}\n"
     "          PAYABLI_MAVEN_PASSWORD: ${{ secrets.PAYABLI_MAVEN_PW_PROD }}\n"
     "        run: ./gradlew publish",
     "          VERSION: ${{ steps.name.outputs.version }}\n        run: ./gradlew publish"),

    ("QA snapshot names the QA prefix in a comment and publishes to the release one", QA, "workflows",
     "          python3 .github/scripts/publish_staging.py --prefix maven-qa --version \"$VERSION\"",
     "          # --prefix maven-qa\n"
     "          python3 .github/scripts/publish_staging.py --prefix maven --version \"$VERSION\""),

    ("QA snapshot reads the role from a secret, with the variable still named beside it", QA, "workflows",
     "          role-to-assume: ${{ vars.AWS_MAVEN_QA_PUBLISH_ROLE_ARN }}",
     "          role-to-assume: ${{ secrets.ROLE || vars.AWS_MAVEN_QA_PUBLISH_ROLE_ARN }}"),

    ("QA snapshot gains a trigger that is neither a dispatch nor a call", QA, "workflows",
     "on:\n  workflow_dispatch:\n", "on:\n  workflow_dispatch:\n  pull_request:\n"),

    ("QA snapshot skips the suites on a dispatch while publishing anyway", QA, "workflows",
     "        if: github.event_name == 'workflow_dispatch'\n",
     "        if: github.event_name != 'workflow_dispatch'\n"),

    ("QA snapshot checks out another repository's default branch", QA, "workflows",
     "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7\n",
     "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7\n"
     "        with:\n          repository: other/repo\n"),

    ("QA snapshot runs an action on a moving tag", QA, "workflows",
     "      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1",
     "      - uses: actions/setup-java@v6"),

    ("CI publishes from a pull request, including a fork's", CI, "workflows",
     "    if: github.event_name == 'push' && github.ref == 'refs/heads/main'\n", "    if: always()\n"),

    ("CI calls the publisher without granting it a token, so nothing here mints one deliberately", CI,
     "workflows",
     "      id-token: write\n    uses: ./.github/workflows/qa-snapshot.yml",
     "    uses: ./.github/workflows/qa-snapshot.yml"),


    ("An occupied key holding the same bytes fails, so a half-finished run cannot be completed",
     PUBLISHER, "publisher",
     '        elif "PreconditionFailed" in r.stderr:', "        elif False:"),

    ("The uploader accepts an occupied key holding different bytes", PUBLISHER, "publisher",
     "            elif remote != local:", "            elif False:"),

    ("The uploader treats an unreadable key as published", PUBLISHER, "publisher",
     "            if remote is None:", "            if False:"),

    ("The uploader publishes a tree left by another build", PUBLISHER, "publisher",
     "    if stray:", "    if False:"),

    ("The uploader accepts a tree holding no artifact", PUBLISHER, "publisher",
     '        sys.exit(f"{staging} is empty. Run `./gradlew publish` first.")',
     "        return []"),

    ("The uploader accepts a staging tree that was never written", PUBLISHER, "publisher",
     "    if not staging.is_dir():", "    if False:"),

    ("The uploader exits 0 with failures", PUBLISHER, "publisher",
     "    return 1 if failed else 0", "    return 0"),

    ("A concurrent write is not retried, so a retryable conflict fails the publish", PUBLISHER,
     "publisher",
     '        if r.returncode != 0 and "ConditionalRequestConflict" in r.stderr:',
     "        if False:"),

    # The QA snapshot. Each of these publishes something: a tree at the wrong prefix, a coordinate every
    # build shares, or a stamp that stops sorting. All three succeed, and the run is green.
    ("QA snapshot uploads to the release prefix", QA, "workflows",
     "--prefix maven-qa", "--prefix maven"),

    # Every argument the checks read is still correct on the first of the two, so a check that reads one
    # invocation is green while the release prefix is written beside the QA one.
    ("QA snapshot uploads again to the release prefix, on a second line", QA, "workflows",
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION"\n',
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION"\n'
     '          python3 .github/scripts/publish_staging.py --prefix maven --version "$VERSION"\n'),

    ("QA snapshot uploads again to the release prefix, on the same line", QA, "workflows",
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION"\n',
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION" '
     '&& python3 .github/scripts/publish_staging.py --prefix maven --version "$VERSION"\n'),

    # A wrapper carrying its own options, which the executable-position scan stops on. The mention count
    # is what refuses this, since the second upload still names the script.
    ("QA snapshot uploads again to the release prefix, behind a wrapper with options", QA, "workflows",
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION"\n',
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION"\n'
     '          env -i /usr/bin/python3 .github/scripts/publish_staging.py --prefix maven'
     ' --version "$VERSION"\n'),

    # Two wrappers where the reader skipped one, so the program name it lands on is the interpreter and
    # the uploader on that line is not seen at all.
    ("QA snapshot uploads again to the release prefix, behind a second command wrapper", QA, "workflows",
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION"\n',
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION"\n'
     '          env python3 .github/scripts/publish_staging.py --prefix maven --version "$VERSION"\n'),

    # A subshell is still a command. A reader that takes the opening bracket for the program name finds
    # no uploader in it and counts one invocation where the shell runs two.
    ("QA snapshot uploads again to the release prefix, inside a subshell", QA, "workflows",
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION"\n',
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION" '
     '&& (python3 .github/scripts/publish_staging.py --prefix maven --version "$VERSION")\n'),

    # Nothing is masked in the command: the pipeline reports tee's status, and without pipefail the red
    # suite left of it is dropped. The suite names are all still there.
    ("CI runs its steps without pipefail, so a piped suite reports the pipe", CI, "workflows",
     "defaults:\n  run:\n    shell: bash\n", ""),

    ("QA snapshot runs its steps without pipefail", QA, "workflows",
     "defaults:\n  run:\n    shell: bash\n", ""),

    # The publishing job still serialises, and the gate is inside the group with it: a dispatch waiting
    # for a reviewer holds the slot, and snapshots from main queue behind it or are replaced.
    ("The QA gate waits for a reviewer while holding the publishing slot", QA, "workflows",
     "defaults:\n  run:\n    shell: bash\n",
     "concurrency:\n  group: qa-snapshot\n  cancel-in-progress: false\n\n"
     "defaults:\n  run:\n    shell: bash\n"),

    # Whitespace is what a word-splitting reader separates on, so the operator written against the word
    # beside it stays inside that word and the two commands read as one.
    ("QA snapshot uploads again to the release prefix, behind an operator with no space", QA, "workflows",
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION"\n',
     'python3 .github/scripts/publish_staging.py --prefix maven-qa --version "$VERSION";'
     'python3 .github/scripts/publish_staging.py --prefix maven --version "$VERSION"\n'),

    ("QA snapshot publishes the committed version, so every build shares one coordinate", QA, "workflows",
     'run: ./gradlew publish -Ppayabli.version="$VERSION"', "run: ./gradlew publish"),

    ("QA snapshot overrides the version with a literal, which shares a coordinate just as well", QA,
     "workflows",
     'run: ./gradlew publish -Ppayabli.version="$VERSION"', "run: ./gradlew publish -Ppayabli.version=0.1.0"),

    # The checked command is still there and still correct, and the committed coordinate is published
    # beside the stamped one.
    ("QA snapshot publishes the committed version too, on a second command", QA, "workflows",
     'run: ./gradlew publish -Ppayabli.version="$VERSION"',
     'run: ./gradlew publish -Ppayabli.version="$VERSION" && ./gradlew publish'),

    # The name is the step's own, so a list keyed on PAYABLI_MAVEN does not see it and a non-Gradle step
    # holds the registry credential while every check about which steps hold one stays green.
    ("A non-Gradle QA step is handed the registry credential under another name", QA, "workflows",
     "      - name: Authenticate to AWS\n",
     "      - name: Authenticate to AWS\n        env:\n"
     "          TOKEN: ${{ secrets.PAYABLI_MAVEN_PW_PROD }}\n"),

    # The other half of the same rule: the expected name, and no secret referenced anywhere, because the
    # credential is written in by hand.
    ("A non-Gradle QA step carries the registry credential as a literal", QA, "workflows",
     "      - name: Authenticate to AWS\n",
     "      - name: Authenticate to AWS\n        env:\n"
     "          PAYABLI_MAVEN_PASSWORD: written-in-by-hand\n"),

    ("The card reader credential is job-level again, so every step receives it", QA, "workflows",
     "      id-token: write\n    steps:",
     "      id-token: write\n    env:\n      PAYABLI_MAVEN_USER: x\n      PAYABLI_MAVEN_PASSWORD: y\n    steps:"),

    ("A dispatched QA snapshot skips the sample app's suite", QA, "workflows",
     " :taptopay:test :example:test", " :taptopay:test"),

    ("A dispatched QA snapshot skips the convention plugin tests", QA, "workflows",
     "          ./gradlew -p build-logic test\n", ""),

    ("A dispatched QA snapshot publishes without running the suites", QA, "workflows",
     "      - name: Unit tests\n        if: github.event_name == 'workflow_dispatch'",
     "      - name: Unit tests\n        if: false"),

    ("QA snapshot cannot be dispatched, so no candidate can be cut on demand", QA, "workflows",
     "on:\n  workflow_dispatch:\n", "on:\n"),

    ("A QA snapshot can be dispatched by anyone with write access, with nobody approving it", QA,
     "workflows", "    environment: release\n", ""),

    # The environment is still named and the reviewers still approve. The subject the run presents is
    # `environment:release` with no ref, which the snapshot role does not trust, so the publish fails at
    # the assume instead of being gated.
    ("The QA gate is put onto the job that assumes the role, losing the ref in its subject", QA,
     "workflows",
     "    permissions:\n      contents: read\n      id-token: write\n",
     "    environment: release\n    permissions:\n      contents: read\n      id-token: write\n"),

    ("The QA gate runs on the called path instead, holding every merge to main", QA, "workflows",
     "    if: github.event_name == 'workflow_dispatch'\n    runs-on: ubuntu-latest\n"
     "    environment: release\n",
     "    if: github.event_name != 'workflow_dispatch'\n    runs-on: ubuntu-latest\n"
     "    environment: release\n"),

    # The gate still runs and the reviewers still approve, and the publish no longer waits for the answer.
    ("A refused QA approval publishes anyway", QA, "workflows",
     "    if: >-\n      ${{ !cancelled() && (needs.approve.result == 'success'\n"
     "      || (github.event_name == 'push' && github.ref == 'refs/heads/main')) }}\n",
     "    if: ${{ always() }}\n"),

    # A skipped gate is not a failure, so this accepts every caller that skipped it. Any workflow in the
    # repository can call this one, and one added on a feature branch publishes with no CI and no
    # approval while presenting a subject the role trusts.
    ("The QA publish accepts any caller that skipped the gate", QA, "workflows",
     "    if: >-\n      ${{ !cancelled() && (needs.approve.result == 'success'\n"
     "      || (github.event_name == 'push' && github.ref == 'refs/heads/main')) }}\n",
     "    if: ${{ !cancelled() && needs.approve.result != 'failure' }}\n"),

    # The approval half alone, which refuses the automatic run from main rather than the feature branch.
    ("The QA publish requires an approval the called path can never get", QA, "workflows",
     "    if: >-\n      ${{ !cancelled() && (needs.approve.result == 'success'\n"
     "      || (github.event_name == 'push' && github.ref == 'refs/heads/main')) }}\n",
     "    if: ${{ !cancelled() && needs.approve.result == 'success' }}\n"),

    ("The QA publish stops waiting for the gate", QA, "workflows",
     "    needs: [approve]\n", ""),

    # The publisher declares these two names and reads them, so the aliases still match what it declares
    # while the credential arriving under one of them is the analysis token.
    ("CI hands the publisher a different secret under the name it declares", CI, "workflows",
     "      PAYABLI_MAVEN_US_PROD: ${{ secrets.PAYABLI_MAVEN_US_PROD }}\n",
     "      PAYABLI_MAVEN_US_PROD: ${{ secrets.SONAR_TOKEN }}\n"),

    ("QA snapshot serialises per ref, so two refs can stamp the same second", QA, "workflows",
     "  group: qa-snapshot\n", "  group: qa-snapshot-${{ github.ref }}\n"),

    # The one the guard was written for and the one it was never shown refusing: with no block at all,
    # two runs stamp and upload at once, and whichever writes a key first keeps the coordinate.
    ("QA snapshot serialises nothing, because the publish has no group", QA, "workflows",
     "    concurrency:\n      group: qa-snapshot\n      cancel-in-progress: false\n", ""),

    ("QA subject check accepts a tag, whose role never grants the write", QA, "workflows",
     "EXPECTED: repo:payabli@139794672/sdk-android@1311286517:ref:refs/heads/",
     "EXPECTED: repo:payabli@139794672/sdk-android@1311286517"),

    ("QA stamp taken in local time, so it stops sorting across a DST change", QA, "workflows",
     "date -u +%Y%m%d%H%M%S", "date +%d%m%y%H%M%S"),

    ("QA role ARN masked as a secret, making every AccessDenied unreadable", QA, "workflows",
     "role-to-assume: ${{ vars.AWS_MAVEN_QA_PUBLISH_ROLE_ARN }}",
     "role-to-assume: ${{ secrets.AWS_MAVEN_QA_PUBLISH_ROLE_ARN }}"),

    ("QA snapshot drops the OIDC subject check, so a moved setting reads as an IAM fault", QA, "workflows",
     '"$ACTIONS_ID_TOKEN_REQUEST_URL&audience=sts.amazonaws.com" |', '"" |'),
    ("Thread reply posted without thread_ts", POSTER, "poster",
     '"thread_ts": thread_ts,', '"_thread_ts_removed": thread_ts,'),

    ("Green fallback runs the red-only commit lookup again", POSTER, "poster",
     "None if green else commits_since_last_green()", "commits_since_last_green()"),

    ("A failed sweep counted as a successful reset again", POSTER, "poster",
     "    if not cancel_stale_switches(token, channel, keep=armed[0], keep_post_at=armed[1], marker=marker):",
     "    if cancel_stale_switches(token, channel, keep=armed[0], keep_post_at=armed[1], marker=marker) "
     "and False:"),

    ("Green fallback re-arms after posting, duplicating the alarm", POSTER, "poster",
     "    if owns_liveness_switch() and not green:", "    if owns_liveness_switch():"),

    ("Alarm blames Actions minutes again, which cannot happen on a public repo", POSTER, "poster",
     '"3. the Actions service itself is healthy, at <https://www.githubstatus.com|githubstatus.com>"',
     '"3. Actions minutes have not run out"'),

    # Paging. The cursor is read but never sent, so page two is requested as page one forever: the
    # cap stops it spinning, which is what makes this a silent wrong answer rather than a hang.
    ("Cursor read but never sent, so paging re-reads page one", POSTER, "poster",
     '        if cursor:\n            params["cursor"] = cursor',
     '        if False:\n            params["cursor"] = cursor'),

    ("Exhausted sweep truncates silently instead of warning", POSTER, "poster",
     '    warn(f"More than {MAX_SWITCH_PAGES} pages of scheduled messages are pending, so the oldest alarms were "',
     '    _suppressed = (f"More than {MAX_SWITCH_PAGES} pages are pending, "'),

    # Anchor re-pointed once a comment came to sit between the guard and its return.
    ("Thread posted even when the parent post failed", POSTER, "poster",
     "    if parent is None or not parent.get(\"ok\"):\n        # Deliberately not reset here.",
     "    if parent is None:\n        # Deliberately not reset here."),

    ("mrkdwn escaping made a no-op", POSTER, "poster",
     'escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")',
     "escaped = text"),

    ("Truncation loop removed, so the block limit is ignored", POSTER, "poster",
     "        if len(text) <= SLACK_BLOCK_LIMIT or not entries:\n            break",
     "        break"),

    ("Absent credentials no longer skip", POSTER, "poster",
     "    if not token or not channel:", "    if False:"),

    # Anchor re-pointed three times now: the reset moved below the guard, then gained a `not green` condition.
    # It anchors on the guard's own return, identified by the reset that follows it.
    ("A Slack failure fails the step instead of warning", POSTER, "poster",
     "        return 0\n    if owns_liveness_switch() and not green:",
     "        return 1\n    if owns_liveness_switch() and not green:"),

    ("Culprit mentions default to on", POSTER, "poster",
     'mention = os.environ.get("SLACK_MENTION_CULPRITS", "").strip().lower() == "true"',
     "mention = True"),

    # Anchor follows the cells refactor; the old one pointed at a line that no longer exists,
    # which the runner reported as invalid rather than passing it off as caught.
    ("An unwritten coverage report reported as 'no classes yet'", POSTER, "poster",
     '                cells.append((name, "no report written", True))',
     '                cells.append((name, "no classes yet", True))'),

    ("Attributions on one commit no longer merged", POSTER, "poster",
     "    for commit, whats in merge_by_commit(failure[\"culprits\"]):",
     "    for commit, whats in [(c, [c['what']]) for c in failure['culprits']]:"),

    ("A culprit that was already green is blamed again", POSTER, "poster",
     "    return not any(full.startswith(short) for full in shas)",
     "    return False and any(full.startswith(short) for full in shas)"),

    ("A truncated compare read as a complete one, so every culprit looks unchanged", POSTER, "poster",
     "    return shas if len(shas) == total else None", "    return shas"),

    ("Thread reply no longer told which commits were already green", POSTER, "poster",
     "        detail = thread_blocks(facts, token, mention, since_green)",
     "        detail = thread_blocks(facts, token, mention)"),

    # The conflation the second review round found: an empty comparison read as an impossible one, which put
    # the blame back on exactly the two cases that prove nobody is to blame.
    ("An empty comparison read as an unknown one, so a re-run gets blamed", POSTER, "poster",
     "    if shas is None or not short:", "    if not shas or not short:"),

    ("A re-run of the green commit treated as an unknown comparison", POSTER, "poster",
     '        return {**facts, "count": 0, "shas": [], "empty": True}\n\n    compared = github_get',
     "        return None\n\n    compared = github_get"),

    ("A checkout behind the green baseline treated as an unknown comparison", POSTER, "poster",
     '    if status in ("behind", "identical"):\n        return {**facts, "count": 0, "shas": [], "empty": True}',
     '    if status in ("behind", "identical"):\n        return None'),

    ("Summary renders a range for a comparison that came out empty", POSTER, "poster",
     '    if since_green and not since_green.get("empty"):', "    if since_green:"),

    ("No-report message drops the platform name", POSTER, "poster",
     '"text": {"type": "mrkdwn", "text": f"{icon} *{platform} · Nightly · no report*\\n{cause}"}}',
     '"text": {"type": "mrkdwn", "text": f"{icon} *Nightly · no report*\\n{cause}"}}'),

    ("Mention lookup budget removed", POSTER, "poster",
     "    deadline = time.monotonic() + LOOKUP_BUDGET_SECONDS if mention else None",
     "    deadline = None"),

    ("Slack link promises a full trace again", POSTER, "poster",
     "<{run_url}|stack trace>", "<{run_url}|full trace>"),

    ("Trace truncation keeps only the head", COLLECTOR, "collector",
     "    return (\n        trace[:head]",
     "    return (\n        trace[:MAX_TRACE_CHARS] + \"\" if True else trace[:head]"),

    ("Summary budget counted in characters, not bytes", COLLECTOR, "collector",
     '    return len(text.encode("utf-8"))', "    return len(text)"),

    # Anchor widened once reset_liveness_switch also called trusted_run_links(), so the bare line is
    # no longer unique and the runner reported it rather than silently patching the wrong site.
    ("Run URLs trusted from the artifact again", POSTER, "poster",
     "    run = trusted_run_links()\n    trail = f\"<{run['url']}|Open the run>\"",
     "    run = facts[\"run\"]\n    trail = f\"<{run['url']}|Open the run>\""),

    ("Notification fallback rendered unescaped", POSTER, "poster",
     '    return blocks, f"{mrkdwn(facts[\'platform\'])} {verdict.lower()}: {suite_text}"',
     '    return blocks, f"{facts[\'platform\']} {verdict.lower()}: {suite_text}"'),

    ("Verdict trusted from the artifact, not reconciled", POSTER, "poster",
     "    red = claimed_red or unfinished", "    red = claimed_red"),

    ("Coverage label rendered unescaped", POSTER, "poster",
     '        lines.append(f"*Coverage ({safe_label})* {measured}")',
     '        lines.append(f"*Coverage ({label})* {measured}")'),

    ("Malformed facts no longer gated on shape", POSTER, "poster",
     "    if not isinstance(raw, dict):", "    if False:"),

    ("Attribution unbounded again", COLLECTOR, "collector",
     "    for failure in all_failures[:MAX_ATTRIBUTED_FAILURES]:",
     "    for failure in all_failures:"),

    # The per-variant check, reduced to the module-wide glob it replaced. One flavor's results then cover a
    # flavor that stopped running, the suite total is never zero, and a whole tier goes missing green.
    ("The variant is ignored, so one flavor's results cover a flavor that stopped", COLLECTOR, "collector",
     '            if parse_results([results.format(module=module, variant=variant or "**")])[0] == 0:',
     '            if parse_results([results.format(module=module, variant="**")])[0] == 0:'),

    # The other direction, and the one a red verdict alone cannot tell apart: a path that matches nothing
    # reads as every variant being silent, which is red for a reason that has nothing to do with a flavor
    # stopping. Only the label distinguishes them, which is why C22 asserts what it names.
    ("Every variant path matches nothing, so a broken path reads as a silent flavor", COLLECTOR, "collector",
     "connected/{variant}/TEST-*.xml", "connected/nowhere/{variant}/TEST-*.xml"),

    ("Coverage phrases repeated per module again", POSTER, "poster",
     "            if shareable:\n                rendered.append(\", \".join(names) + f\" {phrase}\")\n            else:\n                rendered.extend(f\"{name} {phrase}\" for name in names)",
     "            rendered.extend(f\"{name} {phrase}\" for name in names)"),

    ("Coverage grouping reorders modules by state", POSTER, "poster",
     "        for (shareable, phrase), run in itertools.groupby(cells, key=lambda cell: (cell[2], cell[1])):",
     "        for (shareable, phrase), run in itertools.groupby(sorted(cells, key=lambda c: c[1]), key=lambda cell: (cell[2], cell[1])):"),

    ("Green posts to the channel again", POSTER, "poster",
     '    green = facts is not None and facts["verdict"] != "red" and job_result == "success"',
     "    green = False"),

    # Returns None rather than a bare return, because arm_liveness_switch now returns a tuple and the
    # caller unpacks it: a bare return made the harness crash instead of reporting a caught break.
    ("Liveness switch never armed", POSTER, "poster",
     "    post_at = int(time.time()) + SWITCH_HOURS * 3600",
     "    return None\n    post_at = int(time.time()) + SWITCH_HOURS * 3600"),

    # Anchor re-pointed once the loop body gained a post_at filter below this line.
    ("Previous alarm never cancelled, so duplicates accumulate", POSTER, "poster",
     '        message_id = message.get("id")', "        message_id = None"),

    ("Liveness window tightened below the measured schedule delay", POSTER, "poster",
     "SWITCH_HOURS = 26", "SWITCH_HOURS = 24"),

    ("Scheduled alarm given a metadata parameter, which stops it posting", POSTER, "poster",
     '        "unfurl_links": False,',
     '        "unfurl_links": False,\n        "metadata": {"event_type": "x", "event_payload": {}},'),

    ("Any run may reset the liveness switch again", POSTER, "poster",
     '    return os.environ.get("LIVENESS_OWNER", "").strip().lower() == "true"',
     "    return True"),

    ("Liveness marker no longer scoped per platform", POSTER, "poster",
     '    return f"nightly-liveness:{platform_name()}"', '    return "nightly-liveness"'),

    ("Stack traces written to the job summary unescaped", COLLECTOR, "collector",
     'f"<pre>{html.escape(trace)}</pre>\\n\\n</details>\\n\\n"',
     'f"<pre>{trace}</pre>\\n\\n</details>\\n\\n"'),

    ("A suite that wrote no results counted as green", COLLECTOR, "collector",
     '    unit_missing = unit_step == "success" and (unit_total == 0 or bool(unit_silent))',
     "    unit_missing = False"),

    ("An instrumented module that wrote no results hidden by its sibling", COLLECTOR, "collector",
     "    inst_missing = inst_step == \"success\" and (inst_total == 0 or bool(inst_silent))",
     "    inst_missing = inst_step == \"success\" and inst_total == 0"),

    ("Verdict no longer published for the gate", COLLECTOR, "collector",
     'handle.write(f"verdict={\'red\' if red else \'green\'}\\n")',
     'handle.write("")'),

    # The live workflows. Each of these is a change that would read as reasonable on its own and would hand a
    # client credential somewhere it does not belong.
    ("A pull request can trigger the qa live run, so a fork reaches the secrets", LIVE_QA, "workflows",
     "on:\n  workflow_dispatch:", "on:\n  pull_request:\n  workflow_dispatch:"),

    ("pull_request_target on the sandbox live run, which runs with the base repo's secrets",
     LIVE_SANDBOX, "workflows",
     "on:\n  workflow_dispatch:", "on:\n  pull_request_target:\n  workflow_dispatch:"),

    ("The emulator step is handed the client secret again", LIVE_FLOWS, "workflows",
     "          PAYABLI_LIVETEST_TOKEN_HOST: 10.0.2.2:8787",
     "          PAYABLI_LIVETEST_TOKEN_HOST: 10.0.2.2:8787\n"
     "          PAYABLI_LIVETEST_CLIENT_SECRET: ${{ secrets.client-secret }}"),

    # Only the other half of the credential. Worth its own row because a check written for the secret alone
    # passes this, which is what the review found.
    ("The emulator step is handed the client id, and only that", LIVE_FLOWS, "workflows",
     "          PAYABLI_LIVETEST_TOKEN_HOST: 10.0.2.2:8787",
     "          PAYABLI_LIVETEST_TOKEN_HOST: 10.0.2.2:8787\n"
     "          PAYABLI_LIVETEST_CLIENT_ID: ${{ secrets.client-id }}"),

    ("The token host is dropped, so the tests fall back to the compiled-in address", LIVE_FLOWS, "workflows",
     "          PAYABLI_LIVETEST_TOKEN_HOST: 10.0.2.2:8787",
     "          PAYABLI_LIVETEST_TOKEN_HOST_DISABLED: 10.0.2.2:8787"),

    ("A live setting is passed as a gradle argument, putting it in a command line", LIVE_FLOWS, "workflows",
     WALKTHROUGH_COMMAND,
     "            ./gradlew :example:connectedWithTelemetryDebugAndroidTest "
     "-Ppayabli.sampleWalkthrough=true "
     "-Ppayabli.liveTest.entryPoint=\"$PAYABLI_LIVETEST_ENTRY_POINT\""),

    # The guard that keeps the run's verdict and the channel's from disagreeing. Dropping a module from it is
    # how one silent suite gets hidden by the other's results.
    ("The results guard stops naming the sample app", LIVE_FLOWS, "workflows",
     "          INSTRUMENTED_MODULES: payin example", "          INSTRUMENTED_MODULES: payin"),

    # A module the instrumented step does not run. The step runs :core and :payin only, so naming any
    # third module puts the value and the tasks out of agreement, and a green nightly would go red for a
    # tier nobody asked to produce anything.
    ("The nightly names a module the instrumented step does not run", NIGHTLY, "workflows",
     INSTRUMENTED_MODULES_LINE,
     "          INSTRUMENTED_MODULES: core,payin,example:debug/flavors/withTelemetry"),

    # The same disagreement from the other side. A module dropped from the value is checked by nothing, so
    # it can go silent under a sibling's results with nothing about the remaining entries looking wrong.
    ("The nightly stops naming a module the instrumented step runs", NIGHTLY, "workflows",
     INSTRUMENTED_MODULES_LINE,
     "          INSTRUMENTED_MODULES: payin"),

    # **Two cases were removed here, and W10's shape checks are vacuous until they come back.** They broke
    # the results-directory form — `example:withTelemetryDebug`, the value that shipped on 2026-08-27 and
    # left the nightly red for fourteen nights while its tests passed, and `example:release/...`, a well
    # formed path naming a build type the step does not run. Both need a flavored module in the value to
    # break, and this branch names none: :example depends on :taptopay, whose native library is arm64 only,
    # so its test APK cannot install on the x86_64 emulator this job uses. W10 still carries the shape
    # checks and nothing now proves they are not vacuous, which is the pair this file exists to keep.
    # Restore both cases in the same change that returns :example to INSTRUMENTED_MODULES.

    # Substitution happens before the script runs, so the guard inside the script cannot see the value that
    # replaced it. This is the form the file used to carry.
    ("The environment is interpolated into the script instead of reaching it as a variable", LIVE_FLOWS,
     "workflows", '          case "$ENVIRONMENT" in', '          case "${{ inputs.environment }}" in'),

    # The action splits the script on newlines, so a continuation is not one. Both halves of the split are
    # broken and neither says so: the command loses its arguments and the arguments become a command.
    ("The emulator script is written with a line continuation again", LIVE_FLOWS, "workflows",
     WALKTHROUGH_COMMAND,
     "            ./gradlew \\\n"
     "              :example:connectedWithTelemetryDebugAndroidTest "
     "-Ppayabli.sampleWalkthrough=true -Ppayabli.demo.prefill=true"),

    # The origin an environment outside the checkout arrives by. Written in the file it is the address this
    # repository exists not to carry; dropped, the run authenticates against whatever the fallback names.
    ("The qa caller writes its origin into the public repository", LIVE_QA, "workflows",
     "      api-origin: ${{ vars.PAYABLI_API_BASE_URL_QA }}",
     "      api-origin: https://api-qa.payabli.com"),

    ("The qa caller takes its origin from a secret, where an address cannot be reviewed", LIVE_QA,
     "workflows", "      api-origin: ${{ vars.PAYABLI_API_BASE_URL_QA }}",
     "      api-origin: ${{ secrets.PAYABLI_API_BASE_URL_QA }}"),

    ("The qa caller stops carrying an origin, so its environment falls back", LIVE_QA, "workflows",
     "      api-origin: ${{ vars.PAYABLI_API_BASE_URL_QA }}",
     "      # api-origin: ${{ vars.PAYABLI_API_BASE_URL_QA }}"),

    ("The run's environment is not added to the SDK, so the sample and the token server disagree",
     LIVE_FLOWS, "workflows",
     "          PAYABLI_SDK_EXTRAENVIRONMENTS: >-",
     "          PAYABLI_SDK_EXTRAENVIRONMENTS_DISABLED: >-"),

    # The guard reads as if it closes the case wherever it sits, so its position is the thing to mutate.
    ("The production refusal moves below the branch that reads the origin", LIVE_FLOWS, "workflows",
     '          if [ "$ENVIRONMENT" = production ]; then\n'
     "            echo \"::error::production is not run by this workflow\"\n"
     "            exit 1\n"
     "          fi\n",
     ""),

    ("The allow-list keeps the origin's port, which matches no hostname the server compares", LIVE_FLOWS,
     "workflows",
     '            export PAYABLI_ALLOWED_API_HOSTS="${authority%%:*}"',
     '            export PAYABLI_ALLOWED_API_HOSTS="$authority"'),

    ("The sample app is not offered the run's environment", LIVE_FLOWS, "workflows",
     "          PAYABLI_DEMO_EXTRAENVIRONMENTS: ${{ inputs.environment }}",
     "          PAYABLI_DEMO_EXTRAENVIRONMENTS:"),

    # Named as text on the line rather than run. A check that only looks for `./gradlew` somewhere in the
    # line passes on this, and the step goes green having run no suite at all.
    ("The live script prints its command instead of running it", LIVE_FLOWS, "workflows",
     "            ./gradlew :payin:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="
     "com.payabli.sdk.payin.payment.PayInLiveFlowsInstrumentedTest",
     "            echo ./gradlew :payin:connectedAndroidTest "
     "-Pandroid.testInstrumentationRunnerArguments.class="
     "com.payabli.sdk.payin.payment.PayInLiveFlowsInstrumentedTest"),

    ("The pay-in suite loses its class filter and runs the whole instrumented suite", LIVE_FLOWS, "workflows",
     "            ./gradlew :payin:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="
     "com.payabli.sdk.payin.payment.PayInLiveFlowsInstrumentedTest",
     "            ./gradlew :payin:connectedAndroidTest\n"
     "            -Pandroid.testInstrumentationRunnerArguments.class="
     "com.payabli.sdk.payin.payment.PayInLiveFlowsInstrumentedTest"),

    # The live reporter's liveness alarm. Going quiet on green is only safe because the alarm exists, so each
    # of these turns the quiet back into the unmonitored silence it replaced, and none of them is visible in
    # the channel until the day something stops running.
    ("The live reporter posts on green again, so the channel stops being read", LIVE_POSTER, "live",
     "    if not red and reset_liveness_switch(token, channel, marker=marker, subject=subject):",
     "    if False:"),

    ("The live reporter stops arming its alarm, leaving silence unmonitored", LIVE_POSTER, "live",
     "    if red and owns_liveness_switch():", "    if False:"),

    # Slack renders `blocks` and keeps `text` for the notification, so an icon in `text` alone never reaches
    # the channel. This is the form the file carried: red and green posts arrived identical, and ten daily
    # failures read as a routine status line.
    ("The verdict icon is left in the notification fallback only", LIVE_POSTER, "live",
     '    blocks = [{"type": "section", "text": {"type": "mrkdwn", "text": f"{icon} *{mrkdwn(headline)}*"}}]',
     '    blocks = [{"type": "section", "text": {"type": "mrkdwn", "text": f"*{mrkdwn(headline)}*"}}]'),

    # The order that shipped. A job dying before any flow ran wrote no results because it died, so testing
    # the absent artifact first announces the artifact and never the cause.
    ("The absent artifact is announced ahead of the job that caused it", LIVE_POSTER, "live",
     '    if failed:\n        headline = f"{where} · {len(failed)} of {len(found)} refused"\n'
     '    elif job_result != "success":\n        headline = f"{where} · the job reported {job_result}"\n'
     '    elif silent:\n        headline = f"{where} · no results written"',
     '    if silent:\n        headline = f"{where} · no results written"\n'
     '    elif failed:\n        headline = f"{where} · {len(failed)} of {len(found)} refused"\n'
     '    elif job_result != "success":\n        headline = f"{where} · the job reported {job_result}"'),

    # Without the thread, the one case where the channel cannot infer the cause is the one carrying none.
    ("A run that wrote nothing loses its thread, so the post carries no cause", LIVE_POSTER, "live",
     "    detail = thread_body(failed) if failed else (\n"
     "        no_flows_cause(job_result, wrote_results) if silent else \"\")",
     '    detail = thread_body(failed) if failed else ""'),

    # Both artifact transfers are non-blocking, so an empty results directory is as likely to be a lost
    # upload as an excluded suite. Assuming results arrived reports the lost artifact as a deliberate
    # exclusion, which is the same mistake as the headline naming the artifact instead of the job.
    ("A lost artifact is reported as a deliberately excluded suite", LIVE_POSTER, "live",
     '    wrote_results = any(results.glob("**/TEST-*.xml"))', "    wrote_results = True"),

    # `Live flows` carries no `continue-on-error`, so a refused flow fails the job after writing its results,
    # and the upload that would have carried them is allowed to fail. Claiming the job stopped first is false
    # of exactly the case worth reading: a real refusal whose evidence was lost in transfer.
    ("A failed job is said to have stopped before any flow wrote results", LIVE_POSTER, "live",
     'f"The job ended `{mrkdwn(job_result)}` and no flow results reached the reporter. It may have "\n'
     '            "stopped before any flow ran, or written results that the upload or the download then '
     'lost. The "\n            "run log separates the two."',
     'f"The job ended `{mrkdwn(job_result)}` before any flow wrote results. The run log names which."'),

    ("A refused arm counts as a reset, so green goes silent with nothing watching", LIVE_POSTER, "live",
     "    if not red and reset_liveness_switch(token, channel, marker=marker, subject=subject):",
     "    if not red and (reset_liveness_switch(token, channel, marker=marker, subject=subject) or True):"),

    ("The alarm is pushed out even though the report never reached the channel", LIVE_POSTER, "live",
     "        return 0\n    if red and owns_liveness_switch():",
     "        pass\n    if red and owns_liveness_switch():"),

    ("Every live run resets the alarm, so a dead schedule is masked by a dispatch", LIVE_POSTER, "live",
     '    return os.environ.get("LIVENESS_OWNER", "").strip().lower() == "true"', "    return True"),

    ("The live alarm takes the nightly's marker, so each cancels the other's", LIVE_POSTER, "live",
     '    return f"live-liveness:{platform}:{environment}"', '    return f"nightly-liveness:{platform}"'),

    ("Both environments share one alarm, so sandbox going quiet is masked by qa", LIVE_POSTER, "live",
     '    return f"live-liveness:{platform}:{environment}"', '    return f"live-liveness:{platform}"'),

    # Distinct per environment and still containing the nightly's marker, so only the cross-reporter check
    # catches it. The sweep matches markers as substrings, so a live run would delete the nightly's alarm.
    ("The live marker contains the nightly's, so a live run deletes its alarm", LIVE_POSTER, "live",
     '    return f"live-liveness:{platform}:{environment}"',
     '    return f"nightly-liveness:{platform}:{environment}"'),

    ("The live workflow stops naming the alarm's owner, so no run ever arms it", LIVE_FLOWS, "workflows",
     "          LIVENESS_OWNER: ${{ github.event_name == 'schedule'",
     "          LIVENESS_OWNER_DISABLED: ${{ github.event_name == 'schedule'"),

    # Either condition rather than both. A dispatch on the default branch then owns the alarm and pushes it
    # out, so a schedule that has already stopped stays masked for as long as anyone keeps dispatching.
    ("Any default-branch run owns the live alarm, not only a scheduled one", LIVE_FLOWS, "workflows",
     "github.event_name == 'schedule' && github.ref_name",
     "github.event_name == 'schedule' || github.ref_name"),

    ("Any default-branch run owns the nightly alarm, not only a scheduled one", NIGHTLY, "workflows",
     "github.event_name == 'schedule' && github.ref_name",
     "github.event_name == 'schedule' || github.ref_name"),

    # The operator carries the whole meaning, and inverting it reads as a typo rather than as a change of
    # policy: every run that is not the scheduled one would then own the alarm.
    ("The live alarm is owned by every run except the scheduled one", LIVE_FLOWS, "workflows",
     "github.event_name == 'schedule' && github.ref_name ==",
     "github.event_name != 'schedule' && github.ref_name !="),

    ("The nightly alarm is owned by every run except the scheduled one", NIGHTLY, "workflows",
     "github.event_name == 'schedule' && github.ref_name ==",
     "github.event_name != 'schedule' && github.ref_name !="),

    ("A green dispatch resets the live alarm, masking a schedule that has stopped", LIVE_POSTER, "live",
     "    if not red and not owns_liveness_switch():", "    if False:"),

    # The alarm that fires is the only thing a responder sees, and it fires a day after anyone could have
    # noticed. Naming the wrong suite in it sends them to a nightly that never stopped.
    ("The live alarm keeps the default subject, so it announces the nightly instead", LIVE_POSTER, "live",
     '    subject = f"live flows ({environment})"', '    subject = "nightly"'),

    # The filter decides whether any of the above ever runs on the file it is about. Dropping a workflow from
    # it leaves every assertion in place and none of them reachable by the change that breaks them.
    # Anchored through the trailing `push:` because the two blocks are identical, and a mutation that
    # matched both would be testing something else. That tail carries the mirror and ci.yml as well, so
    # each anchor below names every line between the one it breaks and `push:`. Adding an entry to the
    # filter therefore moves every anchor after it, and leaves them matching nothing.
    ("The harness stops running when the nightly changes", SCRIPTS, "workflows",
     "      - '.github/workflows/nightly.yml'\n      - '.github/workflows/card-reader-mirror.yml'\n      - '.github/workflows/ci.yml'\n      - '.github/workflows/qa-snapshot.yml'\n  push:",
     "      - '.github/workflows/nightly-disabled.yml'\n"
     "      - '.github/workflows/card-reader-mirror.yml'\n      - '.github/workflows/ci.yml'\n      - '.github/workflows/qa-snapshot.yml'\n  push:"),

    ("The harness stops running when a live workflow changes", SCRIPTS, "workflows",
     "      - '.github/workflows/live-*.yml'\n      - '.github/workflows/nightly.yml'\n"
     "      - '.github/workflows/card-reader-mirror.yml'\n      - '.github/workflows/ci.yml'\n      - '.github/workflows/qa-snapshot.yml'\n  push:",
     "      - '.github/workflows/live-disabled-*.yml'\n      - '.github/workflows/nightly.yml'\n"
     "      - '.github/workflows/card-reader-mirror.yml'\n      - '.github/workflows/ci.yml'\n      - '.github/workflows/qa-snapshot.yml'\n  push:"),

    ("The harness stops running when the card reader mirror changes", SCRIPTS, "workflows",
     "      - '.github/workflows/card-reader-mirror.yml'\n      - '.github/workflows/ci.yml'\n      - '.github/workflows/qa-snapshot.yml'\n  push:",
     "      - '.github/workflows/card-reader-mirror-disabled.yml'\n"
     "      - '.github/workflows/ci.yml'\n      - '.github/workflows/qa-snapshot.yml'\n  push:"),

    # The mirror's permission split. The token is what turns repository-controlled code into an identity
    # that can write the origin, and every one of these hands it to a run that publishes nothing.
    ("The mirror grants the OIDC token at the top, so both jobs inherit it", MIRROR, "workflows",
     "permissions:\n  contents: read\n\nconcurrency:",
     "permissions:\n  contents: read\n  id-token: write\n\nconcurrency:"),

    ("The fetch job is granted the token it has no use for", MIRROR, "workflows",
     "      id-token: none", "      id-token: write"),

    ("The publishing job's grant is dropped, so publishing cannot authenticate", MIRROR, "workflows",
     "      id-token: write", "      id-token: none"),

    # The jobs stop being alternatives, so a fetch-only dispatch publishes as well. It reads as a condition
    # somebody simplified.
    ("A fetch-only dispatch runs the publishing job too", MIRROR, "workflows",
     "    if: ${{ !inputs.fetch_only }}", "    if: ${{ always() }}"),

    ("The fetch job runs on every dispatch, including one that publishes", MIRROR, "workflows",
     "    if: ${{ inputs.fetch_only }}", "    if: ${{ always() }}"),

    # The role ARN in the job that cannot assume it. Harmless while the grant above stays `none`, which is
    # what makes it the half of the pair that arrives quietly: the ARN sits beside the code waiting for a
    # token grant to reach the same job.
    ("The fetch job is handed the publishing role's ARN", MIRROR, "workflows",
     "          VERSION: ${{ inputs.version }}\n          GPR_TOKEN: ${{ secrets.GPR_TOKEN }}",
     "          VERSION: ${{ inputs.version }}\n"
     "          ROLE_ARN: ${{ vars.AWS_MIRROR_PUBLISH_ROLE_ARN }}\n"
     "          GPR_TOKEN: ${{ secrets.GPR_TOKEN }}"),

    # A step deciding on the input, which reads as defence in depth while the job it sits in grants the
    # token either way.
    ("A step decides publishing on the input instead of the job", MIRROR, "workflows",
     "      - name: Authenticate to AWS\n        uses:",
     "      - name: Authenticate to AWS\n        if: ${{ !inputs.fetch_only }}\n        uses:"),

    # The dispatch input interpolated into the script body. `--version` is text somebody typed into a form,
    # and substitution happens before the shell runs, so a value that closes its own quote runs as a command
    # in the job holding the vendor token and the publishing credential.
    ("The version is interpolated into the publishing script", MIRROR, "workflows",
     '\n          python3 .github/scripts/mirror_card_reader.py --version "$VERSION"\n',
     '\n          python3 .github/scripts/mirror_card_reader.py --version "${{ inputs.version }}"\n'),

    # The live reporter's allowlist. Each of these widens what reaches a channel, and none of them looks
    # alarming in a diff, which is why they are covered rather than trusted.
    ("The failure message is reported whole, allowlist bypassed", LIVE_POSTER, "live",
     '    matched = REPORTABLE.findall(message)', '    matched = [message]'),

    ("The allowlist gains a catch-all, so any word is reportable", LIVE_POSTER, "live",
     r'    r"|(?i:\bno (?:compose hierarchies|detail reported)\b)",',
     '    r"|.+",'),

    ("The identifier pattern loses its case sensitivity, admitting the text beside it", LIVE_POSTER, "live",
     r'    r"|\b(?:code|serviceCode|declineCode|type)=[A-Za-z0-9_.-]{1,40}"',
     r'    r"|(?i:\b(?:code|serviceCode|declineCode|type)=.{1,40})"'),

    ("The status key takes any value again, not only digits", LIVE_POSTER, "live",
     r'    r"\bhttpStatus=\d{3}"',
     r'    r"\bhttpStatus=[A-Za-z0-9_.-]{1,40}"'),

    # A list that emptied is the case the notice is the whole message, and appending it to nothing left a
    # blank line where the failures had been.
    ("The notice is appended to the list rather than joined with it", LIVE_POSTER, "live",
     '        shown = lines + ([f"_{hidden} further failure(s) not listed here; see the run._"] if hidden '
     'else [])\n        body = "\\n".join(shown)',
     '        notice = f"\\n_{hidden} further failure(s) not listed here; see the run._" if hidden else ""\n'
     '        body = "\\n".join(lines) + notice'),

    ("The summary is no longer bounded", LIVE_POSTER, "live",
     '    return " ".join(dict.fromkeys(matched))[:300]', '    return " ".join(matched)'),

    # The thread post's bound. Cutting the finished string is what this replaced, and it is the version that
    # reads as a complete list while being anything but.
    ("The thread post is sliced mid-line again", LIVE_POSTER, "live",
     '    lines = [f"• `{mrkdwn(flow.suite)}` {mrkdwn(flow.name)} — {mrkdwn(flow.detail or \'\')}" '
     'for flow in failed]',
     '    return "\\n".join(f"• {flow.name} — {flow.detail}" for flow in failed)[:SLACK_TEXT_LIMIT]'),

    ("The dropped failures are no longer counted", LIVE_POSTER, "live",
     '        shown = lines + ([f"_{hidden} further failure(s) not listed here; see the run._"] if hidden '
     'else [])',
     '        shown = lines'),

    # W12, where the third-party emulator action is allowed to run. One row per route a secret takes into
    # that job, because the rule is about the job graph and each row is a different edge of it. The last
    # three spellings were each a review finding against the version of W12 that read expressions.
    ("The emulator step is handed a secret through its env", NIGHTLY, "workflows",
     "          emulator-options: -no-snapshot-save -no-window -gpu swiftshader_indirect"
     " -noaudio -no-boot-anim -camera-back none",
     "          emulator-options: -no-snapshot-save -no-window -gpu swiftshader_indirect"
     " -noaudio -no-boot-anim -camera-back none\n"
     "        env:\n          READER_PW: ${{ secrets.PAYABLI_MAVEN_PW_PROD }}"),

    ("The emulator action is handed a secret through its inputs", NIGHTLY, "workflows",
     '        # is waiting on rather than something this one can be talked into.\n        uses: reactivecircus/android-emulator-runner@v2\n        with:\n',
     '        # is waiting on rather than something this one can be talked into.\n        uses: reactivecircus/android-emulator-runner@v2\n        with:\n          repository-password: ${{ secrets.PAYABLI_MAVEN_PW_PROD }}\n'),

    # Job level, which every step inherits while none of them names it.
    ("The emulator job inherits a secret from its own env", NIGHTLY, "workflows",
     '      EMULATOR_ARCH: x86_64\n    steps:\n',
     "      EMULATOR_ARCH: x86_64\n      READER_PW: ${{ secrets.PAYABLI_MAVEN_PW_PROD }}\n    steps:\n"),

    ("The instrumented job is given the card reader credential", CI, "workflows",
     "      API_LEVEL: '34'\n      EMULATOR_TARGET: google_apis",
     "      PAYABLI_MAVEN_USER: ${{ secrets.PAYABLI_MAVEN_US_PROD }}\n"
     "      PAYABLI_MAVEN_PASSWORD: ${{ secrets.PAYABLI_MAVEN_PW_PROD }}\n"
     "      API_LEVEL: '34'\n      EMULATOR_TARGET: google_apis"),

    # Workflow level, inherited by every job and appearing in none of them.
    ("The workflow-level env hands every ci.yml job the card reader credential", CI, "workflows",
     "env:\n  # Never --info or --debug here: verbose Gradle logs can print repository credentials.\n"
     "  GRADLE_OPTS: -Dorg.gradle.console=plain",
     "env:\n  # Never --info or --debug here: verbose Gradle logs can print repository credentials.\n"
     "  GRADLE_OPTS: -Dorg.gradle.console=plain\n"
     "  READER_PW: ${{ secrets.PAYABLI_MAVEN_PW_PROD }}"),

    # An earlier step in the same job exports the value, so the emulator step inherits it without naming it.
    ("A step before the emulator exports a secret to the job's environment file", NIGHTLY, "workflows",
     '      - name: Enable KVM group perms\n',
     '      - name: Export\n        run: echo "READER_PW=${{ secrets.PAYABLI_MAVEN_PW_PROD }}" >> "$GITHUB_ENV"\n'
     + '      - name: Enable KVM group perms\n'),

    # GitHub's `format` escapes a brace by doubling it, so an expression reader that stops at the first
    # `}}` never reaches the secret.
    ("The emulator job's env hides a secret behind an escaped brace", NIGHTLY, "workflows",
     '      EMULATOR_ARCH: x86_64\n    steps:\n',
     "      EMULATOR_ARCH: x86_64\n"
     "      READER_PW: ${{ format('{{Hello {0}!}}', secrets.PAYABLI_MAVEN_PW_PROD) }}\n    steps:\n"),

    ("The emulator job exports the entire secrets context", NIGHTLY, "workflows",
     '      EMULATOR_ARCH: x86_64\n    steps:\n',
     "      EMULATOR_ARCH: x86_64\n      ALL_SECRETS: ${{ toJSON(secrets) }}\n    steps:\n"),

    # The edges between jobs. Outputs and artifacts carry values across without the word appearing.
    ("The emulator job waits on the job that holds the credential", NIGHTLY, "workflows",
     "    name: Unit + instrumented tests\n",
     "    name: Unit + instrumented tests\n    needs: card-present\n"),

    ("The emulator job downloads an artifact", NIGHTLY, "workflows",
     '      - name: Enable KVM group perms\n',
     "      - uses: actions/download-artifact@v8\n        with:\n          name: nightly-results-card-present\n"
     + '      - name: Enable KVM group perms\n'),

    # A job holding a secret, even on one step, lets every earlier action write what that step reads.
    ("An action in the nightly card-present job is left on a moving tag", NIGHTLY, "workflows",
     "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1\n\n"
     "      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6",
     "      - uses: actions/checkout@v7\n\n"
     "      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6"),

    ("An action in the ci.yml card-present job is left on a moving tag", CI, "workflows",
     '      PAYABLI_MAVEN_PASSWORD: ${{ secrets.PAYABLI_MAVEN_PW_PROD }}\n    steps:\n      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1',
     '      PAYABLI_MAVEN_PASSWORD: ${{ secrets.PAYABLI_MAVEN_PW_PROD }}\n    steps:\n      - uses: actions/checkout@v7'),

    # W13. A results directory left out of the archive reaches `verdict` as a suite that wrote nothing.
    ("The results archive leaves out the unit test results", PACK, "workflows",
     "  \\( -path '*/build/test-results' -o -path '*/build/reports'",
     "  \\( -path '*/build/reports'"),

    # W9, and the reason it exists: W12 reads ci.yml, so the harness has to run when ci.yml changes.
    # Dropping it from the filter leaves every W12 assertion about that file unreachable by the change
    # that would break it, while the harness still reports a full pass on everything else.
    # :example runs in card-present and is counted there. Counting it as a unit module lets its results
    # stand in for a unit step that wrote none, which is the guard the two sets exist for.
    ("A silent card-present module is hidden by its sibling", COLLECTOR, "collector",
     'card_missing = card_step == "success" and (card_total == 0 or bool(card_silent))',
     'card_missing = card_step == "success" and card_total == 0'),

    ("A silent unit module is hidden by its sibling", COLLECTOR, "collector",
     'unit_missing = unit_step == "success" and (unit_total == 0 or bool(unit_silent))',
     'unit_missing = unit_step == "success" and unit_total == 0'),

    ("A card-present module is counted under the unit job", COLLECTOR, "workflows",
     'for module in ("taptopay", "example")',
     'for module in ("taptopay",)'),

    ("The harness stops running when only ci.yml changes", SCRIPTS, "workflows",
     "      - '.github/workflows/card-reader-mirror.yml'\n      - '.github/workflows/ci.yml'\n      - '.github/workflows/qa-snapshot.yml'\n  push:",
     "      - '.github/workflows/card-reader-mirror.yml'\n  push:"),

]


def still_parses(path: Path) -> str:
    """Empty when the patched file is still the kind of file the harness can read, else why not.

    A mutation is meant to break a behaviour, not a parse: a file the harness cannot read would fail for the
    wrong reason and be scored as caught. Python gets the compiler, and YAML gets the same parser the checks
    read the document with, so a mutation that produces something they cannot load is reported as invalid
    rather than counted as a break they caught.

    A structural text check stood here while the harness had no parser, and it could only ask whether two
    keys were still present.
    """
    if path.suffix == ".py":
        try:
            py_compile.compile(str(path), doraise=True, cfile=str(WORK / "compile-probe.pyc"))
        except py_compile.PyCompileError as error:
            return f"patched file does not compile: {error}"
        return ""
    if path.suffix == ".sh":
        proc = subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True)
        return f"patched script does not parse: {proc.stderr.strip()}" if proc.returncode else ""

    try:
        document = yaml.safe_load(path.read_text())
    except yaml.YAMLError as error:
        return f"patched workflow does not parse: {error}"
    if not isinstance(document, dict):
        return f"patched workflow is not a mapping: {type(document).__name__}"
    if "jobs" not in document:
        return "patched workflow lost its jobs block"
    if True not in document and "on" not in document:
        return "patched workflow lost its on block"
    return ""


def run_verify(half: str) -> tuple[int, int, str]:
    # Aimed at the copies, so the harness reads what this run mutated rather than what the repository holds.
    env = {**os.environ, "NIGHTLY_ONLY": half,
           "NIGHTLY_COLLECTOR": str(COLLECTOR), "NIGHTLY_POSTER": str(POSTER), "NIGHTLY_PACK": str(PACK),
           "NIGHTLY_WORKFLOWS": str(WORKFLOW_DIR), "NIGHTLY_LIVE_POSTER": str(LIVE_POSTER),
           "NIGHTLY_PUBLISHER": str(PUBLISHER)}
    proc = subprocess.run([sys.executable, str(VERIFY)], capture_output=True, text=True, env=env)
    match = re.search(r"(\d+) passed, (\d+) failed", proc.stdout)
    if not match:
        return -1, -1, proc.stdout[-500:] + proc.stderr[-500:]
    return int(match.group(1)), int(match.group(2)), proc.stdout


def main() -> int:
    # The file in the working tree is the pristine copy: it is read for every anchor and is what each
    # mutated copy is restored from, and it is never written. Proving a fix red before it is green is a
    # separate exercise and belongs to verify.py, which takes NIGHTLY_COLLECTOR and NIGHTLY_POSTER for
    # exactly that; see README.md here for the recipe.
    pristine = dict(SOURCE)
    for target, source in SOURCE.items():
        shutil.copy(source, target)

    print("Baseline, unmodified:")
    for half in ("collector", "poster", "workflows", "live", "publisher"):
        passed, failed, _ = run_verify(half)
        print(f"  {half}: {passed} passed, {failed} failed")
        if failed != 0:
            print("  ABORT: baseline is not green, so no sabotage result would mean anything.")
            return 1

    rows, invalid = [], []
    for description, path, half, anchor, replacement in MUTATIONS:
        source = pristine[path].read_text()
        occurrences = source.count(anchor)
        if occurrences != 1:
            invalid.append((description, f"anchor matched {occurrences} times, expected exactly 1"))
            print(f"  INVALID  {description}: anchor matched {occurrences}x")
            continue

        path.write_text(source.replace(anchor, replacement))
        broken = still_parses(path)
        if broken:
            invalid.append((description, broken))
            print(f"  INVALID  {description}: {broken}")
            shutil.copy(pristine[path], path)
            continue

        passed, failed, output = run_verify(half)
        shutil.copy(pristine[path], path)

        if passed < 0:
            invalid.append((description, "harness produced no verdict"))
            print(f"  INVALID  {description}: no verdict")
            continue
        caught = [line.strip()[5:].strip() for line in output.splitlines() if line.strip().startswith("FAIL")]
        rows.append((description, failed, caught))
        flag = "ok " if failed > 0 else "MISS"
        print(f"  {flag}  {failed:>3} caught | {description}")

    # Restore and prove it.
    for path, backup in pristine.items():
        shutil.copy(backup, path)
    passed_c, failed_c, _ = run_verify("collector")
    passed_p, failed_p, _ = run_verify("poster")
    print(f"\nRestored: collector {passed_c} passed / {failed_c} failed, poster {passed_p} passed / {failed_p} failed")

    print("\n| Deliberate break, then reverted | Tests that caught it |")
    print("|---|---|")
    for description, failed, _ in rows:
        print(f"| {description} | {failed} |")

    missed = [r for r in rows if r[1] == 0]
    print(f"\n{len(rows)} breaks, {len(rows) - len(missed)} caught, {len(missed)} missed, {len(invalid)} invalid")
    for description, reason in invalid:
        print(f"  INVALID: {description} — {reason}")
    for description, _, _ in missed:
        print(f"  MISSED (a finding, not a footnote): {description}")
    return 1 if missed or invalid or failed_c or failed_p else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        shutil.rmtree(WORK, ignore_errors=True)
