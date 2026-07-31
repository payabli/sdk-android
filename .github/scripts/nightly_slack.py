#!/usr/bin/env python3
"""Render the nightly facts into Slack: a summary in the channel, the failure detail in its thread.

Runs in a job of its own that `needs` the test job, so the bot token never exists in a job that runs a
third-party action. It reads the facts file `nightly_report.py` wrote and posts twice.

Why a bot token rather than the incoming webhook this replaced. Threading needs the parent message's `ts`
as `thread_ts`, and a webhook's response body is the literal string `ok` with no `ts` and no channel, so a
webhook cannot reply to its own message. Slack documents a workaround, carrying a `thread_ts` obtained from
`conversations.history` or the Events API, but each of those needs a token too, so there is no token-free
route to a thread. `chat.postMessage` returns `{"ok": true, "channel": ..., "ts": ...}`, which is the whole
reason for the change.

Two calls, not one, and that is not a compromise. No Slack method posts a parent and a reply together:
`thread_ts` has to name a message that already exists. Posting the summary first is also the failure mode
worth having. If the thread post fails the summary is already in the channel with the verdict, the counts
and the coverage, so the channel keeps the actionable part and loses only the detail. An atomic call would
be all or nothing, which on a red night means no message at all.

Nothing here can fail the run. A Slack outage must not turn a green nightly red, and the suite gate in the
test job owns the run result, so every path below warns and exits zero.

Never prints the token, and never handles a stack trace: traces stay in the job summary and this links them.
"""

from __future__ import annotations

import http.client
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# Everything a call to Slack is allowed to do other than answer. `http.client.HTTPException` earns its place
# by not being an OSError: `IncompleteRead` and `BadStatusLine` are raised on a truncated or malformed
# response and urllib does not wrap them, so a handler built from URLError and OSError alone lets them
# through and the poster dies with a traceback. Measured, not assumed: issubclass(HTTPException, OSError)
# is False.
UNREACHABLE = (
    urllib.error.URLError,
    http.client.HTTPException,
    TimeoutError,
    json.JSONDecodeError,
    OSError,
)

SLACK_API = "https://slack.com/api"
# Slack hard-limits a text block at 3000 characters. Two separate bounds therefore apply to the failure
# list, a count and a length, and both must announce themselves: a silently truncated list reads as "that
# was all of them". The length bound sits under 3000 to leave room for the notice that reports it.
MAX_LISTED_FAILURES = 12
SLACK_BLOCK_LIMIT = 2900
SUPPORTED_SCHEMA = 2


def warn(message: str) -> None:
    print(f"::warning::{message}")


def mrkdwn(text: str) -> str:
    """Escape text that came from a test result before it reaches a Slack block.

    Two reasons, and the second is the serious one. Slack mrkdwn treats `&`, `<` and `>` specially, and a
    JUnit ComparisonFailure is written as `expected:<a> but was:<b>`, so ordinary assertion output would
    misrender. And Slack control sequences are written the same way, so an assertion message or a test name
    containing `<!channel>` would broadcast to everyone in the channel. Test data is untrusted input here,
    even when we wrote the test.
    """
    escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    # Backticks are not escapable in mrkdwn, and several of these values are rendered inside a code span, so
    # one would end the span early. With the angle brackets already neutralised that is cosmetic rather than
    # a broadcast route, but the substitution costs nothing and closes the class. An apostrophe rather than a
    # deletion, so a test name that legitimately contains one still reads.
    return escaped.replace("`", "'")


def slack_post(method: str, token: str, payload: dict) -> dict | None:
    """Call one Slack Web API method. Returns the parsed body, or None if it could not be reached.

    Slack reports application errors as HTTP 200 with `{"ok": false, "error": "..."}` rather than as a
    status code, so `ok` is what the callers check. Only the error code is ever logged: the response body
    echoes the message back and the request carries the token, and neither belongs in a public log.
    """
    request = urllib.request.Request(
        f"{SLACK_API}/{method}",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Authorization": f"Bearer {token}",
        },
        method="POST",
    )
    try:
        # Bounded on purpose. An unbounded call against a stalled endpoint would hold this job until its
        # timeout, and a Slack outage must not cost anything but the report.
        with urllib.request.urlopen(request, timeout=20) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        # 429 and 5xx arrive here. Not retried: the report is worth one attempt, and a second one on a red
        # night delays nothing that matters while adding a path that is never exercised.
        warn(f"Slack {method} returned HTTP {error.code}. The suite result is unaffected.")
        return None
    except UNREACHABLE as error:
        warn(f"Slack {method} could not be reached ({type(error).__name__}). The suite result is unaffected.")
        return None

    if not body.get("ok"):
        warn(f"Slack {method} refused the call: {body.get('error', 'unknown error')}.")
    return body


def slack_user_for_email(email: str, token: str) -> str | None:
    """The Slack user ID for a git author email, or None when there is not one.

    A git email need not match a Slack account at all, so failure here is ordinary rather than exceptional
    and the caller falls back to the plain name. Needs the `users:read.email` scope.
    """
    if not email:
        return None
    request = urllib.request.Request(
        f"{SLACK_API}/users.lookupByEmail?{urllib.parse.urlencode({'email': email})}",
        headers={"Authorization": f"Bearer {token}"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            body = json.loads(response.read().decode("utf-8"))
    except UNREACHABLE:
        # HTTPError is a URLError subclass, so it is covered. Not warned for the same reason a refusal is
        # not: a lookup that fails falls back to the plain name, which is the default rendering anyway.
        return None
    if not body.get("ok"):
        # Not warned. `users_not_found` is the expected answer for anyone who commits from an address that
        # is not their Slack one, and a warning per failure per night would train people to ignore warnings.
        return None
    return (body.get("user") or {}).get("id")


def render_author(commit: dict, token: str, mention: bool, cache: dict[str, str | None]) -> str:
    """The commit author, as a Slack mention only when mentions are switched on.

    Off by default, and that is a team-norm decision rather than a technical one. The culprit is a labelled
    heuristic and it has been wrong before, so pinging its author at 3am is not something to enable without
    the team agreeing to it first. Set SLACK_MENTION_CULPRITS=true once that conversation has happened.
    """
    name = mrkdwn(commit.get("author", "")) or "unknown author"
    if not mention:
        return name
    email = commit.get("email", "")
    if email not in cache:
        cache[email] = slack_user_for_email(email, token)
    user_id = cache[email]
    return f"<@{user_id}>" if user_id else name


def merge_by_commit(culprits: list[dict]) -> list[tuple[dict, list[str]]]:
    """Group attributions that name the same commit, preserving the order they were found in.

    The collector looks up two things, the failing test and the class it names, and a commit that changed a
    class usually changed its test in the same breath. Left ungrouped that renders the same sha, subject and
    author twice per failure, which on a multi-failure night is most of the message.
    """
    merged: dict[str, tuple[dict, list[str]]] = {}
    for commit in culprits:
        sha = commit.get("sha", "")
        if sha in merged:
            merged[sha][1].append(commit["what"])
        else:
            merged[sha] = (commit, [commit["what"]])
    return list(merged.values())


def summary_blocks(facts: dict) -> tuple[list[dict], str]:
    """The channel message: verdict, counts, coverage, and where to look. No failure detail.

    Deliberately silent about the thread. Slack renders its own reply count on a threaded parent, so a line
    claiming detail is in the thread would be redundant when the thread post succeeds and a lie when it
    fails. Letting Slack's own affordance be the pointer keeps the parent true either way.
    """
    red = facts["verdict"] == "red"
    verdict = "Nightly failed" if red else "Nightly green"
    icon = ":red_circle:" if red else ":white_check_mark:"
    # The ref and sha live in the context line at the bottom, not here. A branch name can be 60 characters
    # of ticket slug, which pushes the thing you actually need to read off the first line.
    lines = [f"{icon} *{mrkdwn(facts['platform'])} · {verdict}*"]
    for suite in facts["suites"]:
        lines.append(f"*{mrkdwn(suite['name'])}* {mrkdwn(suite['label'])}")

    # Three states, three phrasings, because conflating them misreports. "no classes yet" is a module with
    # nothing to measure; "no report written" is a module whose coverage task did not produce one, which on a
    # red night is the normal fate of the module whose tests just failed. Rendering the second as the first,
    # or omitting it, would say coverage is absent when it is merely unmeasured tonight.
    for group in facts["coverage"]:
        rendered = []
        for module in group["modules"]:
            name = mrkdwn(module["module"])
            if module["state"] == "measured":
                rendered.append(f"{name} {module['percent']:.1f}%")
            elif module["state"] == "empty":
                rendered.append(f"{name} no classes yet")
            else:
                rendered.append(f"{name} no report written")
        measured = " · ".join(rendered) if rendered else "no modules configured"
        lines.append(f"*Coverage ({group['label']})* {measured}")

    failures = facts["failures"]
    if failures:
        lines.append(f"*Failures* {len(failures)}")

    blocks: list[dict] = [{"type": "section", "text": {"type": "mrkdwn", "text": "\n".join(lines)}}]

    # Traceability, kept small and out of the headline. The sha is a link so it stays one short token.
    #
    # The ref is escaped like everything else dynamic. Git allows backticks and angle brackets in a refname,
    # and a manual dispatch chooses the ref, so an unescaped one could close this code span and inject a
    # Slack control sequence.
    run = facts["run"]
    trail = f"<{run['url']}|Open the run>"
    if run["sha"] and run["commit_url"]:
        trail += f" · <{run['commit_url']}|`{run['sha']}`>"
    if run["ref"]:
        trail += f" on `{mrkdwn(run['ref'])}`"
    blocks.append({"type": "context", "elements": [{"type": "mrkdwn", "text": trail}]})

    suite_text = ", ".join(f"{s['label']} {s['name'].lower()}" for s in facts["suites"])
    return blocks, f"{facts['platform']} {verdict.lower()}: {suite_text}"


def thread_blocks(facts: dict, token: str, mention: bool) -> list[dict]:
    """The thread reply: one entry per failed test, with its trace linked and its commit attributed.

    The trace is a link rather than text. It lives in the job summary, which renders without a download and
    outlives log retention, and a full trace would blow the 3000-character block limit on the first failure.
    """
    failures = facts["failures"]
    run_url = facts["run"]["url"]
    cache: dict[str, str | None] = {}

    # One rendered entry per failure, so trimming can drop whole failures rather than cut through one.
    entries: list[str] = []
    for failure in failures[:MAX_LISTED_FAILURES]:
        # Every field here originates in a test result or in git output, which carries commit subjects and
        # author names, so all of it is escaped.
        entry = f"\n• `{mrkdwn(failure['label'])}` · <{run_url}|full trace>\n  {mrkdwn(failure['detail'])}"
        for commit, whats in merge_by_commit(failure["culprits"]):
            # One line per commit, not per lookup. The two lookups usually land on the same commit, because a
            # change to a class and to its test normally ships together, and printing that commit twice with
            # only the leading noun different was the least readable thing in the message.
            subjects = " and ".join("test" if what == "test" else f"`{mrkdwn(what)}`" for what in whats)
            author = render_author(commit, token, mention, cache)
            entry += (
                f"\n  {subjects} last touched by `{mrkdwn(commit['sha'])}"
                f" {mrkdwn(commit['subject'])}` — {author}"
            )
        entries.append(entry)

    # Two independent limits, and the character one used to be applied silently by slicing the finished
    # string. Twelve failures at 300 characters each exceed the block limit before any culprit text, so
    # the list could be cut mid-failure while the omitted count was zero and the message therefore
    # claimed to be complete. Drop whole entries until the text and its notice fit, and always say how
    # many are missing, counting both limits together.
    # Drops to zero entries if it has to. Stopping at one left the contract broken in the case it was
    # meant to cover: a single entry longer than the limit, from a parameterized test name or a long
    # commit subject, was sliced mid-entry with no notice. Header plus notice is always short enough.
    while True:
        hidden = len(failures) - len(entries)
        notice = f"\n_{hidden} further failure(s) not listed here; see the run._" if hidden else ""
        header = "*Probable cause is a heuristic, not evidence.*"
        text = f"{header}" + "".join(entries) + notice
        if len(text) <= SLACK_BLOCK_LIMIT or not entries:
            break
        entries.pop()

    return [{"type": "section", "text": {"type": "mrkdwn", "text": text[:SLACK_BLOCK_LIMIT]}}]


def unreported_blocks(job_result: str) -> tuple[list[dict], str]:
    """What to say when the test job produced no facts file at all.

    This is the case the old arrangement could not report. Posting lived inside the test job behind
    `if: always()`, and a job timeout or a cancellation kills the job and takes that step with it, so the
    channel simply went quiet on the nights that most needed a message. Reporting from a separate job means
    a dead test job still gets announced.
    """
    text = (
        f":red_circle:*Nightly · no report*\nThe test job ended `{mrkdwn(job_result)}` without writing a "
        "report. A cancellation, a job timeout, or a failure before the tests ran."
    )
    return [{"type": "section", "text": {"type": "mrkdwn", "text": text}}], "Nightly produced no report"


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} <facts-path>", file=sys.stderr)
        return 2

    token = os.environ.get("SLACK_BOT_TOKEN", "").strip()
    channel = os.environ.get("SLACK_CHANNEL_ID", "").strip()
    # Absent credentials warn and skip, and never cost the suite result. The nightly's whole point is the
    # test outcome; the report is how it is delivered, and a delivery problem is not a test result.
    if not token or not channel:
        warn("SLACK_BOT_TOKEN or SLACK_CHANNEL_ID is not set, so no nightly report was posted.")
        return 0

    mention = os.environ.get("SLACK_MENTION_CULPRITS", "").strip().lower() == "true"

    facts_path = Path(sys.argv[1])
    facts: dict | None = None
    if facts_path.is_file():
        try:
            facts = json.loads(facts_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError) as error:
            warn(f"The nightly facts file could not be read ({type(error).__name__}).")
    if facts is not None and facts.get("schema") != SUPPORTED_SCHEMA:
        warn(f"Unsupported nightly facts schema {facts.get('schema')!r}; expected {SUPPORTED_SCHEMA}.")
        facts = None

    if facts is None:
        blocks, fallback = unreported_blocks(os.environ.get("NIGHTLY_JOB_RESULT", "unknown"))
    else:
        blocks, fallback = summary_blocks(facts)

    parent = slack_post("chat.postMessage", token, {"channel": channel, "text": fallback, "blocks": blocks})
    if parent is None or not parent.get("ok"):
        return 0

    # Green nights end here, with one line in the channel and no thread. That is the readability this whole
    # arrangement is for.
    if facts is None or not facts["failures"]:
        return 0

    thread_ts = parent.get("ts")
    if not thread_ts:
        # Documented to be present on a successful post, so this is defensive rather than expected.
        warn("Slack accepted the summary but returned no ts, so the failure detail was not threaded.")
        return 0

    slack_post(
        "chat.postMessage",
        token,
        {
            "channel": channel,
            "thread_ts": thread_ts,
            "text": f"{len(facts['failures'])} failure(s)",
            "blocks": thread_blocks(facts, token, mention),
        },
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
