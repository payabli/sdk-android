#!/usr/bin/env python3
"""Collect what the nightly run produced, and decide the verdict.

This half holds no credential and posts nothing. It runs in the test job, where the build outputs and the
full git history are, and it writes three things:

  * a facts file, JSON, uploaded as an artifact for `nightly_slack.py` to render and post
  * the full stack traces, to `$GITHUB_STEP_SUMMARY`, so the report has something durable to link at
  * `verdict=green|red` to `$GITHUB_OUTPUT`, which the suite gate in the same job honours

The split from posting is not tidiness. The gate reads the verdict, so the verdict has to be computed in
the job the gate lives in, or the run and the notification could disagree again. And keeping the token out
of this job means no third-party action ever runs beside it. See nightly.yml.

What it reports, and nothing else:
  * unit test coverage, because a green suite says nothing about how much it covers
  * green, or red with the exact failure count
  * only the tests that failed. A list of passing tests is noise that hides the three lines that matter
  * a probable culprit per failure, from git history rather than from guesswork, with that commit's author
  * a link to the run, so the full log is one click away

Deliberately stdlib only and deliberately not a GitHub Action: this repo just moved five actions off a
deprecated Node runtime, and a script has no runtime to deprecate and nothing to keep pinned.
"""

from __future__ import annotations

import html
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# The first line of a failure is what reaches Slack, so it is bounded there. The trace only reaches the job
# summary, and the per-trace bound exists to stop one runaway trace from crowding out the other failures.
# Both announce themselves when they bite, and the unabridged trace is in the nightly-reports artifact
# either way, so neither bound loses evidence.
MAX_DETAIL_CHARS = 300
MAX_TRACE_CHARS = 4000
# GitHub caps a job summary at 1 MiB, and that is a limit on **bytes**, so the budget below is spent in
# bytes rather than in characters. Counting `len()` measures code points: 900,000 characters of multi-byte
# UTF-8 is up to 3.6 MB, which GitHub rejects, and a rejected summary takes the destination of every trace
# link in the Slack report with it. Not hypothetical for this repo, whose storage tests deliberately carry
# non-BMP and malformed text, and whose assertion output would therefore quote it back.
MAX_SUMMARY_BYTES = 900_000

# The modules that have a coverage task, named rather than discovered. Globbing for whatever report happens
# to be on disk drops a module out of the message entirely when its task did not run, and the module with
# the only real coverage is the first to go, because its report is written by the very task a failing test
# just failed. Measured on a red probe run: `core 79.7%` vanished and the line read `payin no classes yet ·
# taptopay no classes yet · telemetry no classes yet`, which invites the reader to conclude coverage
# collapsed rather than that it was never written.
#
# :example is absent deliberately, matching ci.yml. It has no coverage task, so it has nothing to omit.
COVERAGE_MODULES = ("core", "payin", "taptopay", "telemetry")


class Failure:
    def __init__(self, suite: str, case: str, detail: str, trace: str, kind: str) -> None:
        self.suite = suite
        self.case = case
        self.detail = detail
        self.trace = trace
        self.kind = kind
        # Filled in by attribute(), because the git lookups need a repository and this class does not.
        self.culprits: list[dict[str, str]] = []

    @property
    def simple_class(self) -> str:
        return self.suite.rsplit(".", 1)[-1]

    @property
    def label(self) -> str:
        return f"{self.simple_class} > {self.case}"

    def as_facts(self) -> dict:
        # The trace is deliberately absent. It goes to the job summary and stays in GitHub; the report links
        # it rather than carrying it. That keeps test output out of Slack storage, keeps the thread reply
        # inside Slack's 3000-character block limit, and means the poster never handles trace text at all.
        return {
            "suite": self.suite,
            "case": self.case,
            "label": self.label,
            "detail": self.detail,
            "kind": self.kind,
            "culprits": self.culprits,
        }


def _int(element: ET.Element, name: str) -> int:
    try:
        return int(element.get(name, "0"))
    except ValueError:
        return 0


def parse_results(patterns: list[str]) -> tuple[int, int, int, list[Failure]]:
    """Returns (tests, failures, skipped, failure details) across every matching JUnit XML file."""
    tests = failures = skipped = 0
    details: list[Failure] = []
    seen_files: set[Path] = set()

    for pattern in patterns:
        for path in sorted(REPO_ROOT.glob(pattern)):
            if path in seen_files:
                continue
            seen_files.add(path)
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                # A truncated results file means the runner died mid-write. Say so rather than
                # reporting a smaller suite as though it were the whole one.
                details.append(
                    Failure(path.name, "(unparseable results file)", "truncated or invalid XML", "", "error")
                )
                failures += 1
                continue

            suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
            for suite in suites:
                tests += _int(suite, "tests")
                failures += _int(suite, "failures") + _int(suite, "errors")
                skipped += _int(suite, "skipped")
                suite_name = suite.get("name", path.stem)
                for case in suite.iter("testcase"):
                    for kind in ("failure", "error"):
                        node = case.find(kind)
                        if node is None:
                            continue
                        message = (node.get("message") or "").strip()
                        # The element text is the stack trace; the attribute is the assertion message. Both
                        # are read now: the first line goes to Slack, the trace to the job summary. Before
                        # this the trace was parsed and thrown away, which is why there was nothing to link.
                        trace = (node.text or "").strip()
                        raw = message or trace
                        first = raw.splitlines()[0] if raw else "(no message)"
                        details.append(
                            Failure(
                                case.get("classname", suite_name),
                                case.get("name", "(unnamed)"),
                                first[:MAX_DETAIL_CHARS],
                                trace or message,
                                kind,
                            )
                        )
    return tests, failures, skipped, details


def coverage(counter_type: str) -> list[tuple[str, float | None, str]]:
    """Coverage of one JaCoCo counter type per module, from the XML the coverage task writes.

    `counter_type` is a JaCoCo counter name: BRANCH and LINE are the two reported here. Branch coverage is
    the stricter of the two, since a fully executed line with an untaken branch counts as covered by line
    and uncovered by branch.

    Returns one row per module in COVERAGE_MODULES, always, with a state that distinguishes three answers
    that must not be conflated. `measured` carries a percentage. `empty` means the report exists with no
    counters, which is a module that has no classes yet, and is different from 0%. `missing` means no
    readable report was written at all, which is what a failed or skipped coverage task leaves behind.

    Every one of them is named. Reporting only what is on disk lets a module disappear on the nights the
    report matters most, and a silent omission reads as "this module is not measured" rather than "this
    module was not measured tonight".
    """
    out: list[tuple[str, float | None, str]] = []
    for module in COVERAGE_MODULES:
        path = REPO_ROOT / module / "build/reports/coverage/test/debug/report.xml"
        if not path.is_file():
            out.append((module, None, "missing"))
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            # A half-written report is as unmeasured as an absent one, and says so the same way.
            out.append((module, None, "missing"))
            continue
        # Only counters that are direct children of <report>. Nested ones are per-package and per-class,
        # and summing those double-counts. An empty module has no such counters at all.
        percent: float | None = None
        for counter in root.findall("counter"):
            if counter.get("type") != counter_type:
                continue
            missed, covered = _int(counter, "missed"), _int(counter, "covered")
            total = missed + covered
            if total:
                percent = 100.0 * covered / total
        out.append((module, percent, "measured" if percent is not None else "empty"))
    return out


def git_one_line(*args: str) -> str:
    try:
        result = subprocess.run(
            ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, timeout=20, check=False
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return result.stdout.strip().splitlines()[0] if result.stdout.strip() else ""


def last_commit(path: Path) -> dict[str, str] | None:
    """The last commit to touch a path, as fields rather than a rendered line.

    Unit-separated rather than space-separated, because a commit subject and an author name can both contain
    anything and the report needs the author's email on its own to look up a Slack account. Splitting a
    pretty-printed line on spaces would take the first word of a subject as an email often enough to matter.
    """
    line = git_one_line("log", "-1", "--format=%h%x1f%an%x1f%ae%x1f%s", "--", str(path))
    if not line:
        return None
    parts = line.split("\x1f")
    if len(parts) != 4:
        return None
    sha, author, email, subject = parts
    return {"sha": sha, "author": author, "email": email, "subject": subject}


def find_source(class_name: str, package: str = "") -> Path | None:
    """The file for a class, disambiguated by package, or None when the answer would be a guess.

    Taking the first filename match was wrong and quietly so: `ExampleUnitTest.kt` exists five times in
    this repo, so every non-core failure was attributed to core's history. A package-qualified path is
    checked first, and an ambiguous bare filename yields nothing rather than a plausible wrong answer.
    """
    if package:
        suffix = f"{package.replace('.', '/')}/{class_name}.kt"
        qualified = sorted(p for p in REPO_ROOT.glob(f"*/src/*/**/{class_name}.kt") if str(p).endswith(suffix))
        if len(qualified) == 1:
            return qualified[0].relative_to(REPO_ROOT)
        if qualified:
            return None

    matches = sorted(REPO_ROOT.glob(f"*/src/*/**/{class_name}.kt"))
    # Exactly one, or nothing. Several means the name alone cannot identify the file.
    return matches[0].relative_to(REPO_ROOT) if len(matches) == 1 else None


def attribute(failure: Failure) -> None:
    """Attach the last commit to touch the failing test, and the last to touch the class it names.

    A heuristic and labelled as one wherever it is rendered. It is right often enough to start from and
    cheap enough to be worth printing; it is not evidence, and the run log is linked for that. The author
    travels with it because a name beside a commit is the cheapest route to the person who knows.
    """
    package = failure.suite.rsplit(".", 1)[0] if "." in failure.suite else ""

    test_file = find_source(failure.simple_class, package)
    if test_file:
        commit = last_commit(test_file)
        if commit:
            failure.culprits.append({**commit, "what": "test"})

    # PayabliServiceInstrumentedTest -> PayabliService, FooTest -> Foo. The class under test usually sits in
    # the same package as its test, so the package qualifies this lookup too.
    subject = re.sub(r"(Instrumented)?Tests?$", "", failure.simple_class)
    if subject and subject != failure.simple_class:
        subject_file = find_source(subject, package)
        if subject_file:
            commit = last_commit(subject_file)
            if commit:
                failure.culprits.append({**commit, "what": subject})


def suite_label(failed: int, skipped: int, total: int, outcome: str, missing: bool) -> str:
    """A suite line that never overstates what passed.

    JUnit's `tests` attribute counts skipped cases: measured, an ignored test and a failing one produce
    `tests="2" skipped="1" failures="1"`. So passed has to be derived, or a suite with one pass and one
    skip reads as "all 2 passed" and then contradicts itself by appending the skip count.
    """
    if outcome != "success":
        # Names the step state rather than a count, because a count from a step that did not finish
        # describes whatever it managed before dying.
        return f"step {outcome}" + (f", {failed} failed so far" if failed else "")
    if missing:
        return "no results written"
    passed = max(total - failed - skipped, 0)
    parts = []
    if failed:
        parts.append(f"{failed} failed")
    parts.append(f"{passed} passed" if failed or skipped else f"all {passed} passed")
    if skipped:
        parts.append(f"{skipped} skipped")
    return ", ".join(parts) + (f" / {total} tests" if failed or skipped else "")


def _utf8_len(text: str) -> int:
    """Byte length, because every limit this file spends against is a byte limit."""
    return len(text.encode("utf-8"))


def clip_trace(trace: str) -> str:
    """Bound a trace, keeping both ends rather than the first N characters.

    Taking the head was the obvious thing and the wrong one. A JVM trace puts the `Caused by:` chain at the
    end, and on a wrapped exception that tail is the whole diagnosis, so head-only truncation reliably
    discards the part a reader opened the summary for. Keep two thirds from the top, which carries the
    exception type, the message and the frames nearest the assertion, and the remainder from the bottom.

    The notice says where the unabridged trace is rather than only that trimming happened, since the JUnit
    XML in the nightly-reports artifact always holds it.
    """
    if len(trace) <= MAX_TRACE_CHARS:
        return trace
    head = MAX_TRACE_CHARS * 2 // 3
    tail = MAX_TRACE_CHARS - head
    omitted = len(trace) - MAX_TRACE_CHARS
    return (
        trace[:head]
        + f"\n\n... {omitted} characters trimmed from the middle. The complete trace is in the "
        "nightly-reports artifact ...\n\n"
        + trace[-tail:]
    )


def write_step_summary(failures: list[Failure]) -> None:
    """Render the traces into the job summary, which is what the Slack report links at.

    Chosen over the three alternatives for a reason each. The results artifact holds the same traces but
    costs the reader a download and an unzip. A Slack file upload reads best but needs an upload scope and
    puts test output into Slack storage, which is worth avoiding for a payments SDK even though a trace
    should carry nothing sensitive. And a per-log-line anchor (`/job/<id>#step:<n>:<line>`) rots silently,
    because line numbers move with any change to the log.

    Every trace is HTML-escaped inside a <pre>, so nothing in test output can close the element and inject
    markup into the summary. A fenced code block would not do: a trace containing a fence would escape it.
    """
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if not target or not failures:
        return

    header = (
        f"## Nightly failures ({len(failures)})\n\n"
        "Stack traces, trimmed in the middle where they are long. The complete JUnit XML is in the "
        "`nightly-reports` artifact on this run.\n\n"
    )
    chunks = [header]
    # Spent in bytes, and the header and the worst-case omission notice are charged up front so the notice
    # cannot itself be what pushes the summary over.
    notice_reserve = _utf8_len(f"_{len(failures)} further trace(s) omitted: the job summary limit._\n")
    budget = MAX_SUMMARY_BYTES - _utf8_len(header) - notice_reserve
    written = 0
    for failure in failures:
        trace = clip_trace(failure.trace or failure.detail or "(no trace recorded)")
        # `open` on the first few only. A red night is usually one or two failures and expanding each one by
        # hand is friction; twenty open traces is a wall.
        opened = " open" if written < 3 else ""
        block = (
            f"<details{opened}><summary><code>{html.escape(failure.label)}</code></summary>\n\n"
            f"<pre>{html.escape(trace)}</pre>\n\n</details>\n\n"
        )
        cost = _utf8_len(block)
        if cost > budget:
            chunks.append(f"_{len(failures) - written} further trace(s) omitted: the job summary limit._\n")
            break
        budget -= cost
        written += 1
        chunks.append(block)

    try:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write("".join(chunks))
    except OSError as error:
        # A summary that cannot be written must not cost the run its report. The Slack link will point at a
        # run page without a traces section, which is a degraded report rather than a missing one.
        print(f"::warning::Could not write the job summary: {error}", file=sys.stderr)


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} <facts-output-path>", file=sys.stderr)
        return 2
    facts_path = Path(sys.argv[1])

    # Card-present is excluded from the unit patterns because it is a separate step with a separate outcome.
    # Sharing one glob let :taptopay results make the unit total non-zero when the unit step had written
    # nothing, which defeated the missing-results guard, and left no way to notice a card-present step that
    # succeeded while writing nothing.
    unit_patterns = [
        f"{module}/build/test-results/test*UnitTest/TEST-*.xml"
        for module in ("core", "payin", "telemetry", "example", "payabli-android")
    ]
    card_patterns = ["taptopay/build/test-results/test*UnitTest/TEST-*.xml"]
    android_patterns = ["*/build/outputs/androidTest-results/connected/**/TEST-*.xml"]

    unit_total, unit_failed, unit_skipped, unit_details = parse_results(unit_patterns)
    card_total, card_failed, card_skipped, card_details = parse_results(card_patterns)
    inst_total, inst_failed, inst_skipped, inst_details = parse_results(android_patterns)

    repo = os.environ.get("GITHUB_REPOSITORY", "payabli/sdk-android")
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    sha = os.environ.get("GITHUB_SHA", "")[:7]
    ref = os.environ.get("GITHUB_REF_NAME", "")
    run_url = f"{server}/{repo}/actions/runs/{run_id}" if run_id else f"{server}/{repo}/actions"
    # Set by the workflow from the earlier steps' outcomes, so a step that never ran is not read as a pass.
    unit_step = os.environ.get("UNIT_OUTCOME", "unknown")
    inst_step = os.environ.get("INSTRUMENTED_OUTCOME", "unknown")
    card_step = os.environ.get("CARD_PRESENT_OUTCOME", "skipped")

    # Only `success` is green for a required suite. Everything else, `skipped` included, is red.
    #
    # `skipped` mattered most and was the subtle one: neither required step has an intentional skip
    # condition, so the only way either is skipped is that something before it failed. Treating it as
    # benign meant a broken KVM or AVD step produced "Instrumented skipped" under a green headline while
    # the run itself was red.
    #
    # The card-present step is the exception, because its skip is intentional: no credential, no run. So it
    # is red on failure and green on a deliberate skip.
    required_bad = unit_step != "success" or inst_step != "success"
    card_bad = card_step not in {"success", "skipped"}

    # A green claim also requires results to exist, per suite rather than in total. Checking the sum let a
    # suite that wrote nothing hide behind another that did, and report "all 0 passed" as a pass, which is
    # the exact regression this nightly exists to catch.
    unit_missing = unit_step == "success" and unit_total == 0
    inst_missing = inst_step == "success" and inst_total == 0
    # A card-present step that ran and wrote nothing is as suspect as either of the required suites.
    card_missing = card_step == "success" and card_total == 0

    red = (
        bool(unit_failed or inst_failed or card_failed)
        or required_bad
        or card_bad
        or unit_missing
        or inst_missing
        or card_missing
    )

    # Named by the workflow rather than inferred from the repo. Both platform SDKs can report into the same
    # channel, and a copy of this script that guesses would eventually guess wrong.
    platform = os.environ.get("PLATFORM", "").strip() or repo.rsplit("/", 1)[-1]

    unit_label = suite_label(unit_failed, unit_skipped, unit_total, unit_step, unit_missing)
    inst_label = suite_label(inst_failed, inst_skipped, inst_total, inst_step, inst_missing)
    suites = [("Unit", unit_label), ("Instrumented", inst_label)]
    # Only worth a line when it is not the ordinary intentional skip.
    if card_step != "skipped":
        suites.append(("Card-present unit", suite_label(card_failed, card_skipped, card_total, card_step, card_missing)))

    # Branch first, then line. Branch is the stricter number and the one that moves when a test stops
    # exercising a path, so it leads; line sits under it for the easier comparison against history.
    coverages = [(label, coverage(counter)) for label, counter in (("branch", "BRANCH"), ("line", "LINE"))]

    all_failures = unit_details + card_details + inst_details
    for failure in all_failures:
        attribute(failure)

    write_step_summary(all_failures)

    facts = {
        # Bumped whenever a consumer would misread an older file. The poster refuses an unknown version
        # rather than rendering half a message from fields it does not recognise.
        "schema": 2,
        "verdict": "red" if red else "green",
        "platform": platform,
        "suites": [{"name": name, "label": label} for name, label in suites],
        "coverage": [
            {"label": label, "modules": [{"module": m, "percent": p, "state": s} for m, p, s in measured]}
            for label, measured in coverages
        ],
        "failures": [failure.as_facts() for failure in all_failures],
        "run": {
            "url": run_url,
            "commit_url": f"{server}/{repo}/commit/{sha}" if sha else "",
            "sha": sha,
            "ref": ref,
        },
    }
    facts_path.parent.mkdir(parents=True, exist_ok=True)
    facts_path.write_text(json.dumps(facts, indent=2), encoding="utf-8")

    # Publish the verdict so the workflow gate can honour it. Step outcomes alone cannot see a task that
    # succeeded while discovering no tests, so without this the run could stay green while the report said
    # red. The run and the notification must not be able to disagree.
    step_output = os.environ.get("GITHUB_OUTPUT")
    if step_output:
        with open(step_output, "a", encoding="utf-8") as handle:
            handle.write(f"verdict={'red' if red else 'green'}\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
