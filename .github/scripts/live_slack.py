#!/usr/bin/env python3
"""Report one live-flows run to Slack: which environment, which flows, and no blame.

Deliberately not `nightly_slack.py`. That reporter answers "what changed to break this", with a culprit per
failure from git history and a commit range since the last green run. A live failure is usually the service's
answer rather than a commit, so the same rendering would name whoever last touched a file for a connector
outage, which is worse than saying nothing. This one names the environment and the flows and stops.

It also posts on green, where the nightly stays silent behind a scheduled liveness alarm. Two runs a day at one
line each is little enough to read, and it makes silence mean the workflow stopped rather than meaning
everything passed, which is what the nightly needs its alarm to establish.

**It is not that alarm.** Nothing here arms anything, so a schedule that stops is noticed by somebody missing
two daily messages and not by anything raising its hand. This repository is public, so scheduled workflows are
disabled after 60 days without repository activity with no announcement, and queued scheduled jobs can be
dropped under load; either of those goes unreported until a person reads the channel and finds the last message
is old. Arming a switch per environment, as `nightly_slack.py` does for the nightly, is what would close that,
and posting on green is a weaker thing that costs nothing.

What reaches the channel from a failure is the classification, the HTTP status and the service's own code.
`PayInLiveFlowsInstrumentedTest.orFail` builds that string and deliberately leaves out `reason` and `detail`,
because the service echoes submitted values into some of them.
"""

from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from nightly_slack import mrkdwn, slack_post, warn

# The two suites this workflow runs, mapped to what a reader calls them. A class not listed still reports,
# under its own name, because a new suite must not go missing from the message that announces failures.
SUITES = {
    "PayInLiveFlowsInstrumentedTest": "SDK surface",
    "QaWalkthroughTest": "sample app",
}

# Under Slack's 3000-character block limit, leaving room for the notice that reports what was dropped.
SLACK_TEXT_LIMIT = 2900


class Flow:
    def __init__(self, suite: str, name: str, detail: str | None) -> None:
        self.suite = SUITES.get(suite, suite)
        self.name = name
        self.detail = detail

    @property
    def failed(self) -> bool:
        return self.detail is not None


# What a failure is allowed to say in the channel. An allowlist rather than a redaction pass, because these
# flows submit a card and a bank account and the assertion text around them is not written with a chat
# channel in mind: a redaction list has to anticipate every shape that could carry a submitted value, and
# gets it wrong silently, whereas this can only ever emit what it matched.
#
# The classifications and codes are published API surface. `PayInLiveFlowsInstrumentedTest.orFail` already
# builds its message from exactly these, and this keeps that true of every other suite as well.
#
# The prose alternative matches either case, because the message it is for begins a sentence: the runner
# writes "No compose hierarchies found in the app". The key=value and exception alternatives stay
# case-sensitive, since those are identifiers rather than sentences.
REPORTABLE = re.compile(
    # An HTTP status is an `Int` at its source, so digits is what it can be. Narrowed anyway, because this
    # pattern has to hold for messages no suite here has written yet, and a key that can only ever be a number
    # is one fewer place a general value shape has to be trusted.
    r"\bhttpStatus=\d{3}"
    r"|\b(?:code|serviceCode|declineCode|type)=[A-Za-z0-9_.-]{1,40}"
    r"|\b(?:AssertionError|IllegalStateException|IllegalArgumentException|ComparisonFailure)\b"
    r"|(?i:\bno (?:compose hierarchies|detail reported)\b)",
)


def summarize(message: str) -> str:
    """The reportable parts of a failure message, in order, or a pointer to the artifact when there are none.

    A live failure is read from the JUnit XML in the uploaded artifact. That stays complete; this is the
    line that goes into a channel, and the two do not have to carry the same thing.
    """
    matched = REPORTABLE.findall(message)
    if not matched:
        return "no reportable detail; read the results artifact"
    return " ".join(dict.fromkeys(matched))[:300]


def thread_body(failed: list[Flow]) -> str:
    """One line per refused flow, dropped whole where they do not all fit, and counted where they are dropped.

    `nightly_slack.thread_blocks` bounds its list the same way and for the same reason: a cut through the
    middle of a line reads as the whole list, so the count is what keeps the message honest.
    """
    lines = [f"• `{mrkdwn(flow.suite)}` {mrkdwn(flow.name)} — {mrkdwn(flow.detail or '')}" for flow in failed]
    while True:
        hidden = len(failed) - len(lines)
        # The notice is a line among the others rather than a suffix, so a list that emptied leaves the notice
        # alone rather than a blank line above it. One failure longer than the limit is what empties it, from a
        # parameterized test name; the loop drops to nothing on purpose, because stopping at one would put back
        # the mid-line cut this exists to prevent.
        shown = lines + ([f"_{hidden} further failure(s) not listed here; see the run._"] if hidden else [])
        body = "\n".join(shown)
        if len(body) <= SLACK_TEXT_LIMIT or not lines:
            break
        lines.pop()
    return body[:SLACK_TEXT_LIMIT]


def flows(results: Path) -> list[Flow]:
    found: list[Flow] = []
    for path in sorted(results.glob("**/TEST-*.xml")):
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            found.append(Flow(path.name, "unreadable results", f"{type(error).__name__}"))
            continue
        for case in root.iter("testcase"):
            failure = case.find("failure")
            if failure is None:
                failure = case.find("error")
            detail = None
            if failure is not None:
                detail = summarize(failure.get("message") or failure.text or "")
            found.append(Flow((case.get("classname") or "").split(".")[-1], case.get("name") or "?", detail))
    return found


def run_url() -> str:
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    return f"{server}/{repository}/actions/runs/{run_id}" if repository and run_id else ""


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} <results-directory>", file=sys.stderr)
        return 2

    token = os.environ.get("SLACK_BOT_TOKEN", "").strip()
    channel = os.environ.get("SLACK_CHANNEL_ID", "").strip()
    # Absent credentials warn and skip, as the nightly does. A delivery problem is not a test result, and a
    # live run that really did pass must not be reported as failed because Slack was unreachable.
    if not token or not channel:
        warn("SLACK_BOT_TOKEN or SLACK_CHANNEL_ID is not set, so no live report was posted.")
        return 0

    environment = os.environ.get("LIVE_ENVIRONMENT", "unknown").strip() or "unknown"
    job_result = os.environ.get("LIVE_JOB_RESULT", "unknown").strip() or "unknown"
    platform = os.environ.get("PLATFORM", "Android").strip() or "Android"

    found = flows(Path(sys.argv[1]))
    failed = [flow for flow in found if flow.failed]

    # Three ways to be red, and the third is the one a count cannot see: a step that succeeded having run
    # nothing writes no XML, and a suite total of zero would otherwise render as "0 of 0 approved".
    silent = not found
    red = bool(failed) or silent or job_result != "success"

    link = run_url()
    where = f"{platform} · live flows · {environment}"
    if silent:
        headline = f"{where} · no results written"
    elif failed:
        headline = f"{where} · {len(failed)} of {len(found)} refused"
    elif job_result != "success":
        headline = f"{where} · the job reported {job_result}"
    else:
        headline = f"{where} · {len(found)} of {len(found)} approved"

    # Escaped like the block, not because anything untrusted reaches the headline today: it is built from the
    # environment name, the platform and two counts. The fallback is the same string rendered a second way,
    # and one of the two escaping is how the pair drifts.
    text = f"{'🔴' if red else '🟢'} {mrkdwn(headline)}"
    blocks = [{"type": "section", "text": {"type": "mrkdwn", "text": f"*{mrkdwn(headline)}*"}}]
    if link:
        blocks.append({
            "type": "context",
            "elements": [{"type": "mrkdwn", "text": f"<{link}|run>"}],
        })

    parent = slack_post("chat.postMessage", token, {"channel": channel, "text": text, "blocks": blocks})
    if parent is None or not parent.get("ok"):
        # The run's own verdict is the workflow's to decide. Losing the report must not change it.
        return 0

    if failed:
        slack_post("chat.postMessage", token, {
            "channel": channel,
            "thread_ts": parent.get("ts"),
            "text": thread_body(failed),
        })

    return 0


if __name__ == "__main__":
    sys.exit(main())
