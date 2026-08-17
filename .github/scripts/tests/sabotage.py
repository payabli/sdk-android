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
# The live workflows are mutated too, because what keeps a client credential out of the emulator step and out
# of a fork's reach is how those files are written. A copy each, in the same scratch directory, so the same
# guarantee holds: nothing here writes into the repository.
WORKFLOW_DIR = WORK / "workflows"
WORKFLOW_DIR.mkdir(exist_ok=True)
LIVE_FLOWS = WORKFLOW_DIR / "live-flows.yml"
LIVE_QA = WORKFLOW_DIR / "live-qa.yml"
LIVE_SANDBOX = WORKFLOW_DIR / "live-sandbox.yml"
# The live reporter, whose allowlist is what keeps a submitted value out of the channel.
LIVE_POSTER = WORK / "live_slack.py"
SOURCE = {
    COLLECTOR: SDK / ".github/scripts/nightly_report.py",
    POSTER: SDK / ".github/scripts/nightly_slack.py",
    LIVE_POSTER: SDK / ".github/scripts/live_slack.py",
    LIVE_FLOWS: SDK / ".github/workflows/live-flows.yml",
    LIVE_QA: SDK / ".github/workflows/live-qa.yml",
    LIVE_SANDBOX: SDK / ".github/workflows/live-sandbox.yml",
}

# (description, target file, half to run, anchor, replacement)
MUTATIONS = [
    ("Thread reply posted without thread_ts", POSTER, "poster",
     '"thread_ts": thread_ts,', '"_thread_ts_removed": thread_ts,'),

    ("Green fallback runs the red-only commit lookup again", POSTER, "poster",
     "None if green else commits_since_last_green()", "commits_since_last_green()"),

    ("A failed sweep counted as a successful reset again", POSTER, "poster",
     "    if not cancel_stale_switches(token, channel, keep=armed[0], keep_post_at=armed[1]):",
     "    if cancel_stale_switches(token, channel, keep=armed[0], keep_post_at=armed[1]) and False:"),

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
     "    unit_missing = unit_step == \"success\" and unit_total == 0",
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
     "            ./gradlew :example:connectedAndroidTest \\",
     "            ./gradlew :example:connectedAndroidTest \\\n"
     "              -Ppayabli.liveTest.entryPoint=\"$PAYABLI_LIVETEST_ENTRY_POINT\" \\"),

    # The live reporter's allowlist. Each of these widens what reaches a channel, and none of them looks
    # alarming in a diff, which is why they are covered rather than trusted.
    ("The failure message is reported whole, allowlist bypassed", LIVE_POSTER, "live",
     '    matched = REPORTABLE.findall(message)', '    matched = [message]'),

    ("The allowlist gains a catch-all, so any word is reportable", LIVE_POSTER, "live",
     r'    r"|(?i:\bno (?:compose hierarchies|detail reported)\b)",',
     '    r"|.+",'),

    ("The identifier pattern loses its case sensitivity, admitting the text beside it", LIVE_POSTER, "live",
     r'    r"\b(?:code|httpStatus|serviceCode|declineCode|type)=[A-Za-z0-9_.-]{1,40}"',
     r'    r"(?i:\b(?:code|httpStatus|serviceCode|declineCode|type)=.{1,40})"'),

    ("The summary is no longer bounded", LIVE_POSTER, "live",
     '    return " ".join(dict.fromkeys(matched))[:300]', '    return " ".join(matched)'),

    # The thread post's bound. Cutting the finished string is what this replaced, and it is the version that
    # reads as a complete list while being anything but.
    ("The thread post is sliced mid-line again", LIVE_POSTER, "live",
     '    lines = [f"• `{mrkdwn(flow.suite)}` {mrkdwn(flow.name)} — {mrkdwn(flow.detail or \'\')}" '
     'for flow in failed]',
     '    return "\\n".join(f"• {flow.name} — {flow.detail}" for flow in failed)[:SLACK_TEXT_LIMIT]'),

    ("The dropped failures are no longer counted", LIVE_POSTER, "live",
     '        notice = f"\\n_{hidden} further failure(s) not listed here; see the run._" if hidden else ""',
     '        notice = ""'),
]


def still_parses(path: Path) -> str:
    """Empty when the patched file is still the kind of file the harness can read, else why not.

    A mutation is meant to break a behaviour, not a parse: a file the harness cannot read would fail for the
    wrong reason and be scored as caught. Python gets the compiler. YAML gets a structural check rather than a
    parser, because this harness is standard library only and a hand-rolled parser would be a second thing to
    trust; what matters is that the document still has the shape the checks read.
    """
    if path.suffix == ".py":
        try:
            py_compile.compile(str(path), doraise=True, cfile=str(WORK / "compile-probe.pyc"))
        except py_compile.PyCompileError as error:
            return f"patched file does not compile: {error}"
        return ""

    text = path.read_text()
    for key in ("on:", "jobs:"):
        if f"\n{key}" not in f"\n{text}":
            return f"patched workflow lost its {key.rstrip(':')} block"
    return ""


def run_verify(half: str) -> tuple[int, int, str]:
    # Aimed at the copies, so the harness reads what this run mutated rather than what the repository holds.
    env = {**os.environ, "NIGHTLY_ONLY": half,
           "NIGHTLY_COLLECTOR": str(COLLECTOR), "NIGHTLY_POSTER": str(POSTER),
           "NIGHTLY_WORKFLOWS": str(WORKFLOW_DIR), "NIGHTLY_LIVE_POSTER": str(LIVE_POSTER)}
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
    for half in ("collector", "poster", "workflows", "live"):
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
