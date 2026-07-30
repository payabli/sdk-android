#!/usr/bin/env python3
"""Build the Slack payload for the nightly run.

Reads what Gradle already wrote and emits one Slack message on stdout. Deliberately stdlib only and
deliberately not a GitHub Action: this repo just moved five actions off a deprecated Node runtime, and a
`curl` post has no runtime to deprecate and nothing to keep pinned.

What it reports, and nothing else:
  * unit test coverage, because a green suite says nothing about how much it covers
  * green, or red with the exact failure count
  * only the tests that failed. A list of passing tests is noise that hides the three lines that matter
  * a probable culprit per failure, from git history rather than from guesswork
  * a link to the run, so the full log is one click away

Never prints a token or a webhook URL: the caller holds those and this only writes a payload.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Slack hard-limits a text block at 3000 characters, so the failure list is bounded. When it is cut, the
# message says so: a silently truncated list reads as "that was all of them".
MAX_LISTED_FAILURES = 12
REPO_ROOT = Path(__file__).resolve().parents[2]


class Failure:
    def __init__(self, suite: str, case: str, detail: str, kind: str) -> None:
        self.suite = suite
        self.case = case
        self.detail = detail
        self.kind = kind

    @property
    def simple_class(self) -> str:
        return self.suite.rsplit(".", 1)[-1]


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
                details.append(Failure(path.name, "(unparseable results file)", "truncated or invalid XML", "error"))
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
                        raw = (node.get("message") or node.text or "").strip()
                        # One line: a stack trace in Slack buries every other failure below the fold.
                        first = raw.splitlines()[0] if raw else "(no message)"
                        details.append(
                            Failure(
                                case.get("classname", suite_name),
                                case.get("name", "(unnamed)"),
                                first[:300],
                                kind,
                            )
                        )
    return tests, failures, skipped, details


def line_coverage() -> list[tuple[str, float | None]]:
    """Line coverage per module, from the JaCoCo XML the coverage task writes.

    A module with no classes yet reports None rather than being left out. Omitting it silently would read
    as "core is the only module we measure", when the truth is that the others have nothing to measure.
    """
    out: list[tuple[str, float | None]] = []
    for path in sorted(REPO_ROOT.glob("*/build/reports/coverage/test/debug/report.xml")):
        module = path.relative_to(REPO_ROOT).parts[0]
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        # Only counters that are direct children of <report>. Nested ones are per-package and per-class,
        # and summing those double-counts. An empty module has no such counters at all.
        percent: float | None = None
        for counter in root.findall("counter"):
            if counter.get("type") != "LINE":
                continue
            missed, covered = _int(counter, "missed"), _int(counter, "covered")
            total = missed + covered
            if total:
                percent = 100.0 * covered / total
        out.append((module, percent))
    return out


def git_one_line(*args: str) -> str:
    try:
        result = subprocess.run(
            ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, timeout=20, check=False
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return result.stdout.strip().splitlines()[0] if result.stdout.strip() else ""


def find_source(class_name: str) -> Path | None:
    matches = sorted(REPO_ROOT.glob(f"*/src/*/**/{class_name}.kt"))
    return matches[0].relative_to(REPO_ROOT) if matches else None


def probable_culprit(failure: Failure) -> str:
    """The last commit to touch the failing test, and the last to touch the class it names.

    A heuristic and labelled as one. It is right often enough to start from and cheap enough to be worth
    printing; it is not evidence, and the run log is linked for that.
    """
    notes: list[str] = []
    test_file = find_source(failure.simple_class)
    if test_file:
        line = git_one_line("log", "-1", "--format=%h %an: %s", "--", str(test_file))
        if line:
            notes.append(f"test last touched by `{line}`")

    # PayabliServiceInstrumentedTest -> PayabliService, FooTest -> Foo.
    subject = re.sub(r"(Instrumented)?Tests?$", "", failure.simple_class)
    if subject and subject != failure.simple_class:
        subject_file = find_source(subject)
        if subject_file:
            line = git_one_line("log", "-1", "--format=%h %an: %s", "--", str(subject_file))
            if line:
                notes.append(f"`{subject}` last touched by `{line}`")
    return " · ".join(notes)


def main() -> int:
    unit_patterns = ["*/build/test-results/test*UnitTest/TEST-*.xml"]
    android_patterns = ["*/build/outputs/androidTest-results/connected/**/TEST-*.xml"]

    unit_total, unit_failed, unit_skipped, unit_details = parse_results(unit_patterns)
    inst_total, inst_failed, _, inst_details = parse_results(android_patterns)

    repo = os.environ.get("GITHUB_REPOSITORY", "payabli/sdk-android")
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    sha = os.environ.get("GITHUB_SHA", "")[:7]
    ref = os.environ.get("GITHUB_REF_NAME", "")
    run_url = f"{server}/{repo}/actions/runs/{run_id}" if run_id else f"{server}/{repo}/actions"
    # Set by the workflow from the earlier steps' outcome, so a step that never ran is not read as a pass.
    unit_step = os.environ.get("UNIT_OUTCOME", "unknown")
    inst_step = os.environ.get("INSTRUMENTED_OUTCOME", "unknown")

    steps_bad = {"failure", "cancelled", "timed_out"}
    red = bool(unit_failed or inst_failed) or unit_step in steps_bad or inst_step in steps_bad
    # A green claim requires results to exist. Zero tests found means the run died before writing any,
    # which is a redder signal than a failure, not a green one.
    no_results = unit_total == 0 and inst_total == 0
    if no_results:
        red = True

    lines: list[str] = []
    if red:
        header = ":red_circle: *Nightly failed*"
    else:
        header = ":white_check_mark: *Nightly green*"
    lines.append(f"{header} · `{ref}` @ `{sha}`")

    if no_results:
        lines.append("*No test results were written at all* - the run died before any suite reported.")

    unit_label = f"{unit_failed} failed / {unit_total} tests" if unit_failed else f"all {unit_total} passed"
    if unit_step in steps_bad and not unit_failed:
        unit_label += f" (step {unit_step})"
    lines.append(f"*Unit* {unit_label}" + (f", {unit_skipped} skipped" if unit_skipped else ""))

    inst_label = f"{inst_failed} failed / {inst_total} tests" if inst_failed else f"all {inst_total} passed"
    if inst_step in steps_bad and not inst_failed:
        inst_label += f" (step {inst_step})"
    elif inst_step == "skipped":
        inst_label = "skipped"
    lines.append(f"*Instrumented* {inst_label}")

    coverage = line_coverage()
    if coverage:
        rendered = [f"{m} {p:.1f}%" if p is not None else f"{m} no classes yet" for m, p in coverage]
        lines.append("*Coverage (line)* " + " · ".join(rendered))
    else:
        lines.append("*Coverage (line)* no report found")

    blocks: list[dict] = [{"type": "section", "text": {"type": "mrkdwn", "text": "\n".join(lines)}}]

    all_failures = unit_details + inst_details
    if all_failures:
        shown = all_failures[:MAX_LISTED_FAILURES]
        detail_lines = [f"*Failures* ({len(all_failures)})"]
        for failure in shown:
            detail_lines.append(f"\n• `{failure.simple_class} > {failure.case}`\n  {failure.detail}")
            culprit = probable_culprit(failure)
            if culprit:
                detail_lines.append(f"  probable cause: {culprit}")
        omitted = len(all_failures) - len(shown)
        if omitted:
            detail_lines.append(f"\n_{omitted} further failure(s) not listed here; see the run._")
        text = "\n".join(detail_lines)
        blocks.append({"type": "section", "text": {"type": "mrkdwn", "text": text[:2900]}})

    blocks.append(
        {
            "type": "context",
            "elements": [{"type": "mrkdwn", "text": f"<{run_url}|Open the run> for logs and report artifacts"}],
        }
    )

    fallback = f"{'Nightly failed' if red else 'Nightly green'}: {unit_label} unit, {inst_label} instrumented"
    json.dump({"text": fallback, "blocks": blocks}, sys.stdout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
