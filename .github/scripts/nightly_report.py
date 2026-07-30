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

# Slack hard-limits a text block at 3000 characters. Two separate bounds therefore apply to the failure
# list, a count and a length, and both must announce themselves: a silently truncated list reads as "that
# was all of them". The length bound sits under 3000 to leave room for the notice that reports it.
MAX_LISTED_FAILURES = 12
SLACK_BLOCK_LIMIT = 2900
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


def coverage(counter_type: str) -> list[tuple[str, float | None]]:
    """Coverage of one JaCoCo counter type per module, from the XML the coverage task writes.

    `counter_type` is a JaCoCo counter name: BRANCH and LINE are the two reported here. Branch coverage is
    the stricter of the two, since a fully executed line with an untaken branch counts as covered by line
    and uncovered by branch.

    A module with no classes yet reports None rather than being left out. Omitting it silently would read as
    "core is the only module we measure", when the truth is the others have nothing to measure.
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
            if counter.get("type") != counter_type:
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


def probable_culprit(failure: Failure) -> str:
    """The last commit to touch the failing test, and the last to touch the class it names.

    A heuristic and labelled as one. It is right often enough to start from and cheap enough to be worth
    printing; it is not evidence, and the run log is linked for that.
    """
    notes: list[str] = []
    package = failure.suite.rsplit(".", 1)[0] if "." in failure.suite else ""

    test_file = find_source(failure.simple_class, package)
    if test_file:
        line = git_one_line("log", "-1", "--format=%h %an: %s", "--", str(test_file))
        if line:
            notes.append(f"test last touched by `{line}`")

    # PayabliServiceInstrumentedTest -> PayabliService, FooTest -> Foo. The class under test usually sits in
    # the same package as its test, so the package qualifies this lookup too.
    subject = re.sub(r"(Instrumented)?Tests?$", "", failure.simple_class)
    if subject and subject != failure.simple_class:
        subject_file = find_source(subject, package)
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

    red = bool(unit_failed or inst_failed) or required_bad or card_bad or unit_missing or inst_missing

    # Named by the workflow rather than inferred from the repo. Both platform SDKs can report into the same
    # channel, and a copy of this script that guesses would eventually guess wrong.
    platform = os.environ.get("PLATFORM", "").strip() or repo.rsplit("/", 1)[-1]

    lines: list[str] = []
    verdict = "Nightly failed" if red else "Nightly green"
    icon = ":red_circle:" if red else ":white_check_mark:"
    # The ref and sha live in the context line at the bottom, not here. A branch name can be 60 characters
    # of ticket slug, which pushes the thing you actually need to read off the first line.
    lines.append(f"{icon} *{platform} · {verdict}*")

    def suite_label(failed: int, total: int, outcome: str, missing: bool) -> str:
        if outcome != "success":
            # Names the step state rather than a count, because a count from a step that did not finish
            # describes whatever it managed before dying.
            return f"step {outcome}" + (f", {failed} failed so far" if failed else "")
        if missing:
            return "no results written"
        if failed:
            return f"{failed} failed / {total} tests"
        return f"all {total} passed"

    unit_label = suite_label(unit_failed, unit_total, unit_step, unit_missing)
    lines.append(f"*Unit* {unit_label}" + (f", {unit_skipped} skipped" if unit_skipped else ""))

    inst_label = suite_label(inst_failed, inst_total, inst_step, inst_missing)
    lines.append(f"*Instrumented* {inst_label}")

    # Only worth a line when it is not the ordinary intentional skip.
    if card_step != "skipped":
        lines.append(f"*Card-present unit* step {card_step}")

    # Branch first, then line. Branch is the stricter number and the one that moves when a test stops
    # exercising a path, so it leads; line sits under it for the easier comparison against history.
    for label, counter_type in (("branch", "BRANCH"), ("line", "LINE")):
        measured = coverage(counter_type)
        if measured:
            rendered = [f"{m} {p:.1f}%" if p is not None else f"{m} no classes yet" for m, p in measured]
            lines.append(f"*Coverage ({label})* " + " · ".join(rendered))
        else:
            lines.append(f"*Coverage ({label})* no report found")

    blocks: list[dict] = [{"type": "section", "text": {"type": "mrkdwn", "text": "\n".join(lines)}}]

    all_failures = unit_details + inst_details
    if all_failures:
        # One rendered entry per failure, so trimming can drop whole failures rather than cut through one.
        entries: list[str] = []
        for failure in all_failures[:MAX_LISTED_FAILURES]:
            entry = f"\n• `{failure.simple_class} > {failure.case}`\n  {failure.detail}"
            culprit = probable_culprit(failure)
            if culprit:
                entry += f"\n  probable cause: {culprit}"
            entries.append(entry)

        # Two independent limits, and the character one used to be applied silently by slicing the finished
        # string. Twelve failures at 300 characters each exceed the block limit before any culprit text, so
        # the list could be cut mid-failure while the omitted count was zero and the message therefore
        # claimed to be complete. Drop whole entries until the text and its notice fit, and always say how
        # many are missing, counting both limits together.
        while True:
            hidden = len(all_failures) - len(entries)
            notice = f"\n_{hidden} further failure(s) not listed here; see the run._" if hidden else ""
            text = f"*Failures* ({len(all_failures)})" + "".join(entries) + notice
            if len(text) <= SLACK_BLOCK_LIMIT or len(entries) <= 1:
                break
            entries.pop()

        blocks.append({"type": "section", "text": {"type": "mrkdwn", "text": text[:SLACK_BLOCK_LIMIT]}})

    # Traceability, kept small and out of the headline. The sha is a link so it stays one short token.
    trail = f"<{run_url}|Open the run>"
    if sha:
        trail += f" · <{server}/{repo}/commit/{sha}|`{sha}`>"
    if ref:
        trail += f" on `{ref}`"
    blocks.append({"type": "context", "elements": [{"type": "mrkdwn", "text": trail}]})

    fallback = f"{platform} {verdict.lower()}: {unit_label} unit, {inst_label} instrumented"
    json.dump({"text": fallback, "blocks": blocks}, sys.stdout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
