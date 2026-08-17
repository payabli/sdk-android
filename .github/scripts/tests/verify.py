#!/usr/bin/env python3
"""Adversarial verification for the nightly reporter. Sabotages each guarantee separately.

Collector runs as a subprocess inside a synthetic git repo, so REPO_ROOT resolution, globbing and git log
are all real. Poster runs in-process against a fake Slack on loopback, so the threading contract is really
exercised rather than asserted about.

Run it with no arguments and no setup: `python3 .github/scripts/tests/verify.py`. See README.md here for
the four disciplines these checks are written under, and for how to prove a check red before it is green.
"""

from __future__ import annotations

import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Pointing COLLECTOR or POSTER at an older revision is how a check is proved red before it is proved green,
# so these overrides are load-bearing rather than incidental. The default resolves from this file so a fresh
# clone needs nothing set: tests -> scripts -> .github -> repository root.
# Not `os.environ.get("NIGHTLY_SDK", HERE.parents[2])`: a default argument is evaluated eagerly, so the
# fallback would raise IndexError for a copy of this file sitting fewer than three directories deep even
# when the override is set, which is the one situation the override exists for.
_sdk = os.environ.get("NIGHTLY_SDK")
SDK = Path(_sdk) if _sdk else HERE.parents[2]
COLLECTOR = Path(os.environ.get("NIGHTLY_COLLECTOR", SDK / ".github/scripts/nightly_report.py"))
POSTER = Path(os.environ.get("NIGHTLY_POSTER", SDK / ".github/scripts/nightly_slack.py"))
ONLY = os.environ.get("NIGHTLY_ONLY", "both")

# One scratch root for the whole run, removed on the way out. Every synthetic repository and every facts
# directory is created inside it. Per-case mkdtemp with no cleanup left 122 directories behind per run, and
# a sabotage pass invokes this suite 41 times, so the two together accumulated 27,754 directories and 835MB
# on one machine before anything noticed.
SCRATCH = Path(tempfile.mkdtemp(prefix="nightly-verify-"))

PASS, FAIL = [], []


def check(name: str, condition: bool, detail: str = "") -> None:
    (PASS if condition else FAIL).append(name)
    print(f"  {'ok  ' if condition else 'FAIL'} {name}" + (f"\n        {detail}" if detail and not condition else ""))


# --------------------------------------------------------------------------------------------------
# Collector
# --------------------------------------------------------------------------------------------------

JUNIT = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="{suite}" tests="{tests}" skipped="{skipped}" failures="{failures}" errors="0">
{cases}
</testsuite>
"""


def junit(suite, cases, tests=None, failures=None, skipped=0):
    body, nfail = [], 0
    for name, failure in cases:
        if failure is None:
            body.append(f'  <testcase classname="{suite}" name="{name}"/>')
        else:
            message, trace = failure
            nfail += 1
            body.append(
                f'  <testcase classname="{suite}" name="{name}">'
                f'<failure message="{message}" type="java.lang.AssertionError">{trace}</failure>'
                f"</testcase>"
            )
    return JUNIT.format(
        suite=suite,
        tests=len(cases) if tests is None else tests,
        failures=nfail if failures is None else failures,
        skipped=skipped,
        cases="\n".join(body),
    )


COVERAGE = '<?xml version="1.0"?><report name="core"><counter type="BRANCH" missed="{bm}" covered="{bc}"/><counter type="LINE" missed="{lm}" covered="{lc}"/></report>'


def make_repo(files: dict[str, str], *, commit_files=True):
    root = Path(tempfile.mkdtemp(dir=SCRATCH))
    (root / ".github/scripts").mkdir(parents=True)
    shutil.copy(COLLECTOR, root / ".github/scripts/nightly_report.py")
    for rel, content in files.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
    env = {**os.environ, "GIT_AUTHOR_NAME": "Dana Rivera", "GIT_AUTHOR_EMAIL": "dana@payabli.com",
           "GIT_COMMITTER_NAME": "Dana Rivera", "GIT_COMMITTER_EMAIL": "dana@payabli.com"}
    run = lambda *a: subprocess.run(["git", *a], cwd=root, capture_output=True, env=env, check=True)
    run("init", "-q")
    if commit_files:
        run("add", "-A")
        run("commit", "-qm", "Add the transport retry budget")
    return root


def run_collector(root: Path, **env_extra):
    facts = root / "out/nightly-facts.json"
    gh_out, gh_sum = root / "gh_output", root / "gh_summary"
    gh_out.touch()
    gh_sum.touch()
    env = {
        "PATH": os.environ["PATH"], "PLATFORM": "Android",
        "GITHUB_OUTPUT": str(gh_out), "GITHUB_STEP_SUMMARY": str(gh_sum),
        "GITHUB_REPOSITORY": "payabli/sdk-android", "GITHUB_SERVER_URL": "https://github.com",
        "GITHUB_RUN_ID": "30535718033", "GITHUB_SHA": "b9a8e2712345", "GITHUB_REF_NAME": "main",
        "UNIT_OUTCOME": "success", "INSTRUMENTED_OUTCOME": "success", "CARD_PRESENT_OUTCOME": "skipped",
        **env_extra,
    }
    proc = subprocess.run([sys.executable, str(root / ".github/scripts/nightly_report.py"), str(facts)],
                          capture_output=True, text=True, env=env)
    return {
        "proc": proc,
        "facts": json.loads(facts.read_text()) if facts.is_file() else None,
        "output": gh_out.read_text(),
        "summary": gh_sum.read_text(),
    }


UNIT_XML = "core/build/test-results/testDebugUnitTest/TEST-com.payabli.sdk.core.RetryTest.xml"
INST_XML = "core/build/outputs/androidTest-results/connected/debug/TEST-emulator.xml"
PAYIN_INST_XML = "payin/build/outputs/androidTest-results/connected/debug/TEST-emulator.xml"
COV_XML = "core/build/reports/coverage/test/debug/report.xml"
SRC = "core/src/test/java/com/payabli/sdk/core/RetryTest.kt"
SUBJ = "core/src/main/java/com/payabli/sdk/core/Retry.kt"


def test_collector():
    print("\nCollector")

    # C1 green
    r = run_collector(make_repo({
        UNIT_XML: junit("com.payabli.sdk.core.RetryTest", [("backs off", None), ("gives up", None)]),
        INST_XML: junit("com.payabli.sdk.core.ServiceInstrumentedTest", [("gzip", None)]),
        COV_XML: COVERAGE.format(bm=10, bc=90, lm=5, lc=95),
    }))
    check("C1 green verdict", "verdict=green" in r["output"], r["output"])
    check("C1 no failures in facts", r["facts"]["failures"] == [])
    check("C1 no job summary written", r["summary"] == "", repr(r["summary"][:200]))
    check("C1 coverage rendered", r["facts"]["coverage"][0]["modules"][0]["percent"] == 90.0 and r["facts"]["coverage"][0]["modules"][0]["state"] == "measured",
          json.dumps(r["facts"]["coverage"]))
    check("C1 card-present skip omitted", [s["name"] for s in r["facts"]["suites"]] == ["Unit", "Instrumented"])

    # C2 red, with attribution
    root = make_repo({
        UNIT_XML: junit("com.payabli.sdk.core.RetryTest", [
            ("backs off", ("expected:&lt;3&gt; but was:&lt;4&gt;",
                           "java.lang.AssertionError: expected:&lt;3&gt; but was:&lt;4&gt;\n\tat com.payabli.sdk.core.RetryTest.backsOff(RetryTest.kt:42)\n\tat java.base/x.y(z.java:1)")),
        ]),
        INST_XML: junit("com.payabli.sdk.core.ServiceInstrumentedTest", [("gzip", None)]),
        COV_XML: COVERAGE.format(bm=10, bc=90, lm=5, lc=95),
        SRC: "class RetryTest",
        SUBJ: "class Retry",
    })
    r = run_collector(root)
    check("C2 red verdict", "verdict=red" in r["output"], r["output"])
    f = r["facts"]["failures"][0]
    check("C2 one failure", len(r["facts"]["failures"]) == 1)
    check("C2 label", f["label"] == "RetryTest > backs off", f["label"])
    check("C2 detail is first line only", f["detail"] == "expected:<3> but was:<4>", repr(f["detail"]))
    check("C2 trace absent from facts", "trace" not in f, str(f.keys()))
    check("C2 two culprits attributed", len(f["culprits"]) == 2, json.dumps(f["culprits"]))
    check("C2 author name", f["culprits"][0]["author"] == "Dana Rivera", json.dumps(f["culprits"][0]))
    check("C2 author email", f["culprits"][0]["email"] == "dana@payabli.com")
    check("C2 culprit subject", f["culprits"][0]["subject"] == "Add the transport retry budget")
    check("C2 culprit kinds", [c["what"] for c in f["culprits"]] == ["test", "Retry"])
    check("C2 trace in job summary", "RetryTest.backsOff(RetryTest.kt:42)" in r["summary"], r["summary"][:300])
    check("C2 summary has details block", "<details open><summary><code>RetryTest &gt; backs off" in r["summary"],
          r["summary"][:300])
    check("C2 failure count in summary heading", "## Nightly failures (1)" in r["summary"])

    # C3 unparseable XML
    r = run_collector(make_repo({UNIT_XML: "<testsuite tests='1'", INST_XML: junit("I", [("a", None)]),
                                 COV_XML: COVERAGE.format(bm=0, bc=1, lm=0, lc=1)}))
    check("C3 truncated XML is a failure", "verdict=red" in r["output"] and
          any("unparseable" in x["case"] for x in r["facts"]["failures"]), json.dumps(r["facts"]["failures"]))

    # C4 step succeeded but wrote nothing
    r = run_collector(make_repo({INST_XML: junit("I", [("a", None)]), COV_XML: COVERAGE.format(bm=0, bc=1, lm=0, lc=1)}))
    check("C4 no results written is red", "verdict=red" in r["output"])
    check("C4 says no results written", r["facts"]["suites"][0]["label"] == "no results written",
          json.dumps(r["facts"]["suites"]))

    # C4b one instrumented module wrote nothing while its sibling wrote plenty. The suite total is not zero,
    # so the check above cannot see it: only the per-module list can, and without it the run stays green
    # having silently lost a whole module's tier.
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [("a", None)]),
        INST_XML: junit("I", [("a", None)]),
        COV_XML: COVERAGE.format(bm=0, bc=1, lm=0, lc=1),
    }), INSTRUMENTED_MODULES="core,payin")
    check("C4b a silent instrumented module is red", "verdict=red" in r["output"], r["output"])
    check("C4b names the silent module", r["facts"]["suites"][1]["label"] == "no results written by payin",
          json.dumps(r["facts"]["suites"]))

    # C4c both modules wrote results, so the same list must not redden a healthy run.
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [("a", None)]),
        INST_XML: junit("I", [("a", None)]),
        PAYIN_INST_XML: junit("P", [("b", None)]),
        COV_XML: COVERAGE.format(bm=0, bc=1, lm=0, lc=1),
    }), INSTRUMENTED_MODULES="core,payin")
    check("C4c both modules present is green", "verdict=green" in r["output"], r["output"])

    # C5 step outcome not success
    r = run_collector(make_repo({UNIT_XML: junit("S", [("a", None)]), INST_XML: junit("I", [("a", None)])}),
                      INSTRUMENTED_OUTCOME="failure")
    check("C5 bad step outcome is red", "verdict=red" in r["output"])
    check("C5 names the step state", r["facts"]["suites"][1]["label"] == "step failure",
          json.dumps(r["facts"]["suites"]))

    # C6 skipped arithmetic
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [("a", None), ("b", ("boom", "trace"))], tests=3, skipped=1),
        INST_XML: junit("I", [("a", None)]),
    }))
    check("C6 skip never overstates passes", r["facts"]["suites"][0]["label"] == "1 failed, 1 passed, 1 skipped / 3 tests",
          json.dumps(r["facts"]["suites"][0]))

    # C7 HTML injection through a stack trace
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [("evil", ("m", "&lt;/pre&gt;&lt;/details&gt;&lt;script&gt;alert(1)&lt;/script&gt;"))]),
        INST_XML: junit("I", [("a", None)]),
    }))
    check("C7 trace cannot close the pre", "</pre></details><script>" not in r["summary"], r["summary"][:400])
    check("C7 trace is html-escaped", "&lt;script&gt;alert(1)" in r["summary"], r["summary"][:400])

    # C8 trace truncation announces itself
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [("huge", ("m", "x" * 9000))]),
        INST_XML: junit("I", [("a", None)]),
    }))
    check("C8 long trace trimmed and says so", "trimmed from the middle" in r["summary"], r["summary"][-300:])
    check("C8 trimmed trace stays near its bound",
          len(r["summary"]) < 4000 + 600, str(len(r["summary"])))

    # C9 card-present failure surfaces
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [("a", None)]),
        INST_XML: junit("I", [("a", None)]),
        "taptopay/build/test-results/testDebugUnitTest/TEST-x.xml": junit("T", [("cp", ("nope", "t"))]),
    }), CARD_PRESENT_OUTCOME="success")
    check("C9 card-present failure is red", "verdict=red" in r["output"])
    check("C9 card-present suite listed", [s["name"] for s in r["facts"]["suites"]][2] == "Card-present unit")

    # C10 ambiguous class name must not be attributed
    root = make_repo({
        UNIT_XML: junit("com.payabli.sdk.core.ExampleUnitTest", [("a", ("m", "t"))]),
        INST_XML: junit("I", [("b", None)]),
        "core/src/test/java/com/other/ExampleUnitTest.kt": "x",
        "payin/src/test/java/com/another/ExampleUnitTest.kt": "y",
    })
    r = run_collector(root)
    check("C10 ambiguous name yields no culprit", r["facts"]["failures"][0]["culprits"] == [],
          json.dumps(r["facts"]["failures"][0]["culprits"]))

    # C11 shallow-clone style repo with no commits: attribution empty, never crashes
    r = run_collector(make_repo({UNIT_XML: junit("S", [("a", ("m", "t"))]), INST_XML: junit("I", [("b", None)]),
                                 SRC: "x"}, commit_files=False))
    check("C11 no history does not crash", r["proc"].returncode == 0, r["proc"].stderr[-400:])
    check("C11 no history means no culprit", r["facts"]["failures"][0]["culprits"] == [])

    # C12 schema and exit code
    check("C12 schema stamped", r["facts"]["schema"] == 4)
    check("C12 facts carry no run block to be tampered with", "run" not in r["facts"], str(sorted(r["facts"])))
    check("C12 clean exit", r["proc"].returncode == 0, r["proc"].stderr[-400:])

    # C13 an <error> element, not just <failure>
    root = make_repo({
        UNIT_XML: '<?xml version="1.0"?><testsuite name="S" tests="1" failures="0" errors="1" skipped="0">'
                  '<testcase classname="com.payabli.sdk.core.BootTest" name="boots">'
                  '<error message="ClassNotFoundException: AndroidJUnitRunner" type="java.lang.Error">'
                  'java.lang.ClassNotFoundException\n\tat java.base/loader(X.java:9)</error></testcase></testsuite>',
        INST_XML: junit("I", [("a", None)]),
    })
    r = run_collector(root)
    check("C13 <error> is collected", len(r["facts"]["failures"]) == 1 and r["facts"]["failures"][0]["kind"] == "error",
          json.dumps(r["facts"]["failures"]))
    check("C13 <error> is red", "verdict=red" in r["output"])
    check("C13 <error> trace reaches the summary", "ClassNotFoundException" in r["summary"])

    # C14 message attribute but no element text: the summary falls back to the message
    r = run_collector(make_repo({
        UNIT_XML: '<?xml version="1.0"?><testsuite name="S" tests="1" failures="1" errors="0" skipped="0">'
                  '<testcase classname="com.payabli.sdk.core.T" name="a">'
                  '<failure message="bare assertion, no trace" type="E"/></testcase></testsuite>',
        INST_XML: junit("I", [("a", None)]),
    }))
    check("C14 traceless failure still reports a detail",
          r["facts"]["failures"][0]["detail"] == "bare assertion, no trace", json.dumps(r["facts"]["failures"][0]))
    check("C14 traceless failure still gets a summary block", "bare assertion, no trace" in r["summary"],
          r["summary"][:300])

    # C15b the summary budget is spent in BYTES. GitHub caps a job summary at 1 MiB of bytes; a character
    # budget lets multi-byte output pass while the encoded file is several times over, and a rejected summary
    # takes the destination of every trace link with it. Each trace here is ~4000 chars but ~12000 bytes.
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [(f"case{i}", ("m", "\u4e16" * 5000)) for i in range(120)]),
        INST_XML: junit("I", [("a", None)]),
    }))
    summary_bytes = len(r["summary"].encode("utf-8"))
    check("C15b summary is under 1 MiB of BYTES, not characters", summary_bytes < 1_048_576,
          f"{summary_bytes} bytes, {len(r['summary'])} chars")
    check("C15b byte-bounded summary still announces what it dropped",
          "further trace(s) omitted" in r["summary"], r["summary"][-200:])

    # C15c a long trace keeps its tail. A JVM trace puts `Caused by:` last, so head-only truncation drops
    # the diagnosis. Both ends must survive and the notice must say where the unabridged trace is.
    tail = "Caused by: java.net.SocketTimeoutException: the actual root cause"
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [("longtrace", ("m", "HEAD_MARKER_START" + ("filler line\n" * 900) + tail))]),
        INST_XML: junit("I", [("a", None)]),
    }))
    check("C15c head of the trace survives", "HEAD_MARKER_START" in r["summary"], r["summary"][:300])
    check("C15c tail with Caused by survives", "Caused by: java.net.SocketTimeoutException" in r["summary"],
          r["summary"][-500:])
    check("C15c trimming says where the whole trace is", "nightly-reports artifact" in r["summary"],
          r["summary"][-500:])

    # C15 the job-summary budget announces what it dropped
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [(f"case{i}", ("m", "z" * 4000)) for i in range(400)]),
        INST_XML: junit("I", [("a", None)]),
    }))
    check("C15 summary budget announces omission", "further trace(s) omitted" in r["summary"],
          r["summary"][-300:])
    check("C15 summary stays under the job limit", len(r["summary"]) < 1_048_576, str(len(r["summary"])))

    # C18 a module with classes but no branches must not be reported as having no classes. Derived from the
    # selected counter, `empty` and `inapplicable` collapsed together and the message contradicted itself:
    # `core no classes yet` on the branch row sat directly above `core 90.0%` on the line row.
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [("a", None)]), INST_XML: junit("I", [("b", None)]),
        # Lines, but zero branches: a class with no conditionals.
        COV_XML: '<?xml version="1.0"?><report name="core">'
                 '<counter type="LINE" missed="2" covered="18"/>'
                 '<counter type="BRANCH" missed="0" covered="0"/></report>',
        # A report with no counters at all is the genuine "no classes yet".
        "payin/build/reports/coverage/test/debug/report.xml": '<?xml version="1.0"?><report name="payin"/>',
    }))
    by_label = {g["label"]: {m["module"]: m for m in g["modules"]} for g in r["facts"]["coverage"]}
    check("C18 branch-less module is inapplicable, not empty",
          by_label["branch"]["core"]["state"] == "inapplicable", json.dumps(by_label["branch"]["core"]))
    check("C18 the same module still measures lines",
          by_label["line"]["core"]["state"] == "measured" and by_label["line"]["core"]["percent"] == 90.0,
          json.dumps(by_label["line"]["core"]))
    check("C18 a counterless report is still empty",
          by_label["branch"]["payin"]["state"] == "empty", json.dumps(by_label["branch"]["payin"]))
    check("C18 no module claims no-classes while measuring another counter",
          not any(by_label["branch"][m]["state"] == "empty" and by_label["line"][m]["state"] == "measured"
                  for m in by_label["branch"]),
          json.dumps(by_label))

    # C20 attribution is bounded to what the report can list. Unbounded, a broad suite break paid two
    # recursive globs and up to two `git log` calls per failure, and `git log` carries a 20-second timeout,
    # so a pathological repo state turns that into a step timeout that loses the whole facts file. The counts
    # and the traces must still cover every failure.
    root = make_repo({
        UNIT_XML: junit("com.payabli.sdk.core.RetryTest",
                        [(f"case{i}", ("boom", f"trace {i}")) for i in range(40)]),
        INST_XML: junit("I", [("a", None)]),
        SRC: "class RetryTest",
        SUBJ: "class Retry",
    })
    r = run_collector(root, UNIT_OUTCOME="failure")
    facts = r["facts"]
    attributed = [f for f in facts["failures"] if f["culprits"]]
    check("C20 every failure is still collected", len(facts["failures"]) == 40, str(len(facts["failures"])))
    check("C20 attribution stops at 12", len(attributed) == 12, f"{len(attributed)} attributed")
    check("C20 the attributed ones are the first ones",
          all(f["culprits"] for f in facts["failures"][:12]) and
          not any(f["culprits"] for f in facts["failures"][12:]),
          json.dumps([bool(f["culprits"]) for f in facts["failures"]]))
    check("C20 traces still cover more than the attributed subset",
          r["summary"].count("<details") > 12, str(r["summary"].count("<details")))
    check("C20 the verdict still counts them all", "verdict=red" in r["output"], r["output"])

    # C16 THE DEFECT THE RED PROBE FOUND: a module whose coverage task did not run must not vanish.
    # Before the fix this globbed whatever was on disk, so `core` silently dropped out of a red report.
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [("a", ("boom", "trace"))]),
        INST_XML: junit("I", [("b", None)]),
        # No core coverage report at all: exactly what a failed :core coverage task leaves behind.
        "payin/build/reports/coverage/test/debug/report.xml": COVERAGE.format(bm=0, bc=0, lm=0, lc=0),
    }), UNIT_OUTCOME="failure")
    branch = {m["module"]: m for m in r["facts"]["coverage"][0]["modules"]}
    check("C16 every configured module is present",
          sorted(branch) == ["core", "example", "payin", "taptopay", "telemetry"],
          str(sorted(branch)))
    check("C16 absent report is 'missing', not omitted", branch["core"]["state"] == "missing",
          json.dumps(branch["core"]))
    check("C16 counterless report is 'empty', not missing", branch["payin"]["state"] == "empty",
          json.dumps(branch["payin"]))
    r2 = run_collector(make_repo({
        UNIT_XML: junit("S", [("a", None)]), INST_XML: junit("I", [("b", None)]),
        COV_XML: COVERAGE.format(bm=10, bc=90, lm=5, lc=95),
    }))
    b2 = {m["module"]: m for m in r2["facts"]["coverage"][0]["modules"]}
    check("C16 a real report is still 'measured'",
          b2["core"]["state"] == "measured" and b2["core"]["percent"] == 90.0, json.dumps(b2["core"]))

    # C17 an unparseable coverage report reads as unmeasured rather than being dropped
    r = run_collector(make_repo({
        UNIT_XML: junit("S", [("a", None)]), INST_XML: junit("I", [("b", None)]),
        COV_XML: "<report><counter type=",
    }))
    b3 = {m["module"]: m for m in r["facts"]["coverage"][0]["modules"]}
    check("C17 corrupt coverage report is 'missing'", b3["core"]["state"] == "missing", json.dumps(b3["core"]))


# --------------------------------------------------------------------------------------------------
# Poster, against a fake Slack
# --------------------------------------------------------------------------------------------------

class FakeSlack(BaseHTTPRequestHandler):
    calls: list = []
    behaviour: dict = {}

    def _reply(self, code, body):
        raw = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        if body.get("_truncate"):
            # Promise more than is delivered, then close: the client raises http.client.IncompleteRead.
            self.send_header("Content-Length", str(len(raw) + 500))
            self.end_headers()
            self.wfile.write(raw)
            self.close_connection = True
            return
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    # Defaults per method, so a test only programs what it cares about.
    DEFAULTS = {
        "users.lookupByEmail": {"ok": True, "user": {"id": "U0LOOKUP"}},
        "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": []},
        "chat.scheduleMessage": {"ok": True, "scheduled_message_id": "Q0ARMED", "channel": "C0BLLFM863V"},
        "chat.deleteScheduledMessage": {"ok": True},
    }

    def do_GET(self):
        method = self.path.split("?")[0].rsplit("/", 1)[-1]
        # Counted before appending, so the first call sees index 0. A list of rules lets a test serve successive
        # pages, which is the only way to exercise cursor following; a bare dict answers every call the same.
        n = sum(1 for c in FakeSlack.calls if c["method"] == method)
        FakeSlack.calls.append({"method": method, "path": self.path, "auth": self.headers.get("Authorization")})
        rules = FakeSlack.behaviour.get(method, FakeSlack.DEFAULTS.get(method, {"ok": True}))
        rule = rules[min(n, len(rules) - 1)] if isinstance(rules, list) else rules
        self._reply(rule.get("_status", 200), {k: v for k, v in rule.items() if k != "_status"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        payload = json.loads(self.rfile.read(length) or b"{}")
        method = self.path.rsplit("/", 1)[-1]
        n = sum(1 for c in FakeSlack.calls if c["method"] == method)
        FakeSlack.calls.append({"method": method, "payload": payload, "auth": self.headers.get("Authorization")})
        rules = FakeSlack.behaviour.get(
            method,
            FakeSlack.DEFAULTS.get(method, [{"ok": True, "ts": "1785408441.829119", "channel": "C0BLLFM863V"}]),
        )
        rule = rules[min(n, len(rules) - 1)] if isinstance(rules, list) else rules
        self._reply(rule.get("_status", 200), {k: v for k, v in rule.items() if k != "_status"})

    def log_message(self, *a):
        pass


def load_poster(base_url):
    spec = importlib.util.spec_from_file_location("nightly_slack", POSTER)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    mod.SLACK_API = base_url
    return mod


FACTS_RED = {
    "schema": 4, "verdict": "red", "platform": "Android",
    "suites": [{"name": "Unit", "label": "1 failed, 273 passed / 274 tests"},
               {"name": "Instrumented", "label": "all 4 passed"}],
    "coverage": [{"label": "branch", "modules": [{"module": "core", "percent": 87.2, "state": "measured"},
                                                 {"module": "payin", "percent": None, "state": "empty"},
                                                 {"module": "taptopay", "percent": None, "state": "missing"},
                                                 {"module": "telemetry", "percent": None, "state": "inapplicable"}]},
                 {"label": "line", "modules": []}],
    "failures": [{
        "suite": "com.payabli.sdk.core.RetryTest", "case": "backs off",
        "label": "RetryTest > backs off", "detail": "expected:<3> but was:<4>", "kind": "failure",
        "culprits": [{"sha": "b9a8e27", "author": "Dana Rivera", "email": "dana@payabli.com",
                      "subject": "Add the transport retry budget", "what": "test"},
                     {"sha": "5ded50a", "author": "Sam Okafor", "email": "sam@payabli.com",
                      "subject": "Rework the backoff", "what": "Retry"}],
    }],
    # Deliberately poisoned and deliberately ignored: schema 3 drops the run block, and the poster rebuilds
    # these from its own trusted environment. A tampered artifact must not be able to reach Slack link syntax.
    "run": {"url": "https://evil.test/x>  <!channel> <https://evil.test/y",
            "commit_url": "https://evil.test/c> <!here>", "sha": "aaa> <!channel>", "ref": "r> <!channel>"},
}


def run_poster(mod, facts, **env_extra):
    FakeSlack.calls = []
    tmp = Path(tempfile.mkdtemp(dir=SCRATCH))
    path = tmp / "nightly-facts.json"
    if facts is not None:
        path.write_text(json.dumps(facts) if not isinstance(facts, str) else facts)
    env = {"SLACK_BOT_TOKEN": "xoxb-not-a-real-token", "SLACK_CHANNEL_ID": "C0BLLFM863V",
           # The no-facts path has no facts to take a run link from and builds it from these.
           "GITHUB_SERVER_URL": "https://github.com", "GITHUB_REPOSITORY": "payabli/sdk-android",
           "GITHUB_RUN_ID": "30609394288", "PLATFORM": "Android",
           # The switch is owned by the scheduled run only; most tests are about that run.
           "LIVENESS_OWNER": "true", **env_extra}
    saved = {k: os.environ.get(k) for k in ("SLACK_BOT_TOKEN", "SLACK_CHANNEL_ID", "SLACK_MENTION_CULPRITS",
                                            "NIGHTLY_JOB_RESULT", "GITHUB_SERVER_URL", "GITHUB_REPOSITORY",
                                            "GITHUB_RUN_ID", "PLATFORM", "GITHUB_TOKEN", "GITHUB_SHA",
                                            "LIVENESS_OWNER", "GITHUB_API_URL", "GITHUB_REF_NAME")}
    for k in saved:
        os.environ.pop(k, None)
    os.environ.update({k: v for k, v in env.items() if v is not None})
    argv = sys.argv
    sys.argv = ["nightly_slack.py", str(path)]
    buf = io.StringIO()
    try:
        with redirect_stdout(buf):
            code = mod.main()
    finally:
        sys.argv = argv
        for k, v in saved.items():
            os.environ.pop(k, None)
            if v is not None:
                os.environ[k] = v
    return code, buf.getvalue(), list(FakeSlack.calls)


MAX_LOOKUPS = 24  # MAX_LISTED_FAILURES x two distinct commit authors


class _PinnedClock:
    """The poster's `time` module with `time()` frozen, and everything else passed straight through.

    Only `post_at` arithmetic needs pinning. `time.monotonic`, which bounds the mention lookups, must stay
    real: freezing it would turn a budget that is supposed to expire into one that never does.
    """

    def __init__(self, real, at):
        self._real, self._at = real, at

    def time(self):
        return self._at

    def __getattr__(self, name):
        return getattr(self._real, name)


class _Missing(dict):
    """Stands in for an absent call so an assertion fails cleanly instead of raising.

    Every lookup returns another _Missing, so `of(calls, "x")[0]["payload"]["text"]` yields something falsy
    rather than IndexError or KeyError. A harness that raises prints no verdict, and a run with no FAIL lines
    is indistinguishable from a run that passed, which is worst in exactly the cases this harness exists for:
    comparing against older code, and sabotage runs that deliberately remove the behaviour being indexed.
    """

    def __getitem__(self, key):
        return _Missing()

    def get(self, key, default=None):
        return _Missing()

    def __contains__(self, key):
        return False

    def __bool__(self):
        return False

    def __len__(self):
        return 0

    def __iter__(self):
        return iter(())

    def splitlines(self):
        return []

    def __str__(self):
        return "<absent>"


class _Calls(list):
    def __getitem__(self, index):
        try:
            return list.__getitem__(self, index)
        except IndexError:
            return _Missing()


def of(calls, method):
    return _Calls(c for c in calls if c["method"] == method)


def before(calls, first, second):
    """True when `first` is called before `second`. Never raises: a missing call is a clean False, because
    `.index()` on an absent element kills the harness before it prints a verdict and a run with no FAIL lines
    reads as a run with no failures."""
    order = [c["method"] for c in calls]
    if first not in order or second not in order:
        return False
    return order.index(first) < order.index(second)


def posts(calls):
    return _Calls(c for c in calls if c["method"] == "chat.postMessage")


def test_poster(mod):
    print("\nPoster")
    ok_parent = [{"ok": True, "ts": "1785408441.829119", "channel": "C0BLLFM863V"}]

    # P1 a green night says nothing at all. Six of seven messages used to say "Nightly green",
    # which is what trains people to stop reading a channel. Silence is only safe because the liveness switch
    # below makes prolonged silence itself an alarm.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    green = {**FACTS_RED, "verdict": "green", "failures": []}
    code, out, calls = run_poster(mod, green)
    check("P1 green exits 0", code == 0)
    check("P1 green posts nothing to the channel", len(posts(calls)) == 0, str(len(posts(calls))))
    check("P1 green still re-arms the switch", len(of(calls, "chat.scheduleMessage")) == 1,
          str([c["method"] for c in calls]))
    check("P1 green says so in the log", "green" in out.lower() and "nothing was posted" in out, out)
    # The green rendering itself still has to be right, for the day it is needed on a mismatch. Asserted at
    # the renderer now that main() no longer posts it.
    text = mod.summary_blocks(green, "success")[0][0]["text"]["text"]
    check("P1 the green rendering is still correct",
          ":white_check_mark:" in text and "Nightly green" in text, text)

    # P2 red threads correctly
    code, out, calls = run_poster(mod, FACTS_RED)
    p = posts(calls)
    check("P2 red posts twice", len(p) == 2, str(len(p)))
    check("P2 parent has no thread_ts", "thread_ts" not in p[0]["payload"])
    check("P2 thread_ts is the parent ts", p[1]["payload"].get("thread_ts") == "1785408441.829119",
          json.dumps(p[1]["payload"].get("thread_ts")))
    parent_text = p[0]["payload"]["blocks"][0]["text"]["text"]
    thread_text = p[1]["payload"]["blocks"][0]["text"]["text"]
    check("P2 parent carries the verdict", ":red_circle:" in parent_text and "Nightly failed" in parent_text)
    check("P2 parent carries counts and coverage", "1 failed, 273 passed" in parent_text and "core 87.2%" in parent_text)
    check("P2 parent has NO failure detail", "RetryTest" not in parent_text, parent_text)
    check("P2 parent does not claim a thread", "thread" not in parent_text.lower(), parent_text)
    check("P2 parent shows failure count", "*Failures* 1" in parent_text, parent_text)
    check("P2 thread carries the test name", "RetryTest &gt; backs off" in thread_text, thread_text)
    # The run id comes from the environment now, not from the facts, so this asserts the trusted one.
    check("P2 thread links the trace",
          "<https://github.com/payabli/sdk-android/actions/runs/30609394288|stack trace>" in thread_text,
          thread_text)
    # The summary trims long traces in the middle, so the link must not promise the full one.
    check("P2 link does not promise a full trace", "full trace" not in thread_text, thread_text)
    check("P2 thread carries the culprit", "b9a8e27 Add the transport retry budget" in thread_text, thread_text)
    check("P2 thread carries the author", "Dana Rivera" in thread_text and "Sam Okafor" in thread_text, thread_text)
    check("P2 thread labels the heuristic", "heuristic" in thread_text, thread_text)
    check("P2 run link in parent context block",
          "Open the run" in p[0]["payload"]["blocks"][1]["elements"][0]["text"])
    check("P2 run link uses the trusted run id",
          "30609394288" in p[0]["payload"]["blocks"][1]["elements"][0]["text"],
          p[0]["payload"]["blocks"][1]["elements"][0]["text"])
    check("P2 fallback text set on both", bool(p[0]["payload"]["text"]) and bool(p[1]["payload"]["text"]))

    # P3 thread post refused: parent stands, run unaffected
    FakeSlack.behaviour = {"chat.postMessage": [ok_parent[0], {"ok": False, "error": "invalid_blocks"}]}
    code, out, calls = run_poster(mod, FACTS_RED)
    check("P3 thread failure still exits 0", code == 0)
    check("P3 parent was posted first", len(posts(calls)) == 2)
    check("P3 warns with the slack error code", "invalid_blocks" in out and "::warning::" in out, out)

    # P4 parent refused: no thread attempted
    FakeSlack.behaviour = {"chat.postMessage": [{"ok": False, "error": "channel_not_found"}]}
    code, out, calls = run_poster(mod, FACTS_RED)
    check("P4 parent failure exits 0", code == 0)
    check("P4 no thread attempted after parent failure", len(posts(calls)) == 1, str(len(posts(calls))))
    check("P4 warns channel_not_found", "channel_not_found" in out, out)
    # The guard that returns early on a refused parent is masked by the later missing-ts check as far as
    # call count goes, so count alone does not test it. What differs is the claim: falling through emits
    # "accepted the summary but returned no ts" for a post Slack rejected outright, which is false.
    check("P4 refused parent is not reported as accepted", "returned no ts" not in out, out)
    check("P4 refused parent does not claim a thread was posted", "not threaded" not in out, out)

    # P5 absent credentials
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    code, out, calls = run_poster(mod, FACTS_RED, SLACK_BOT_TOKEN="")
    check("P5 absent token exits 0", code == 0)
    check("P5 absent token posts nothing", len(calls) == 0, str(calls))
    check("P5 absent token warns", "::warning::" in out and "SLACK_BOT_TOKEN" in out, out)
    code, out, calls = run_poster(mod, FACTS_RED, SLACK_CHANNEL_ID="")
    check("P5 absent channel skips too", code == 0 and len(calls) == 0)
    check("P5 token never printed", "xoxb-not-a-real-token" not in out, out)

    # P6 missing facts, test job dead: red, and names the job's own end state
    code, out, calls = run_poster(mod, None, NIGHTLY_JOB_RESULT="cancelled")
    text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
    check("P6 missing facts still posts", len(posts(calls)) == 1)
    check("P6 missing facts says so", "no report" in text and "cancelled" in text, text)
    check("P6 dead test job is red", ":red_circle:" in text, text)
    check("P6 missing facts posts no thread", "thread_ts" not in posts(calls)[0]["payload"])
    # Indexed defensively on purpose. Reaching into blocks[-1]["elements"] turns a missing context block
    # into a KeyError, which kills the harness before it prints a verdict, and a run with no FAIL lines
    # reads as a run with no failures. A check that cannot fail cleanly is not a check.
    contexts = [b for b in posts(calls)[0]["payload"]["blocks"] if b.get("type") == "context"]
    check("P6 no-report message carries a run link",
          bool(contexts) and "Open the run" in contexts[0]["elements"][0]["text"],
          json.dumps(posts(calls)[0]["payload"]["blocks"]))

    # P6b missing facts after a PASSING test job. The suite was green and the gate passed, so the facts were
    # lost in transfer: both the upload and the download are non-blocking. A red circle here is a false
    # alarm, and "ended success without writing a report" is self-contradictory.
    code, out, calls = run_poster(mod, None, NIGHTLY_JOB_RESULT="success")
    text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
    check("P6b green test job is not reported red", ":red_circle:" not in text, text)
    check("P6b green test job warns instead", ":warning:" in text, text)
    check("P6b does not claim the job wrote nothing",
          "without writing" not in text and "produced no usable report" not in text, text)
    check("P6b names the transfer as the cause", "did not reach this job" in text, text)
    check("P6b says the suite was green", "green" in text, text)
    check("P6b fallback does not read as a failure",
          "did not arrive" in posts(calls)[0]["payload"]["text"], posts(calls)[0]["payload"]["text"])

    # P6c no run id in the environment: the link is dropped, not half-built, and nothing raises
    code, out, calls = run_poster(mod, None, NIGHTLY_JOB_RESULT="success", GITHUB_RUN_ID="")
    blocks = posts(calls)[0]["payload"]["blocks"]
    check("P6c absent run id still posts", len(posts(calls)) == 1 and code == 0)
    check("P6c absent run id adds no context block", len(blocks) == 1, json.dumps(blocks))

    # P16 the no-report message must name the platform. Both SDKs post into one channel, and this is the
    # single path that cannot read the platform out of the facts file.
    for result in ("success", "cancelled"):
        code, out, calls = run_poster(mod, None, NIGHTLY_JOB_RESULT=result)
        text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
        check(f"P16 no-report header names the platform ({result})", "Android" in text, text)
        check(f"P16 no-report fallback names the platform ({result})",
              "Android" in posts(calls)[0]["payload"]["text"], posts(calls)[0]["payload"]["text"])
    # Absent PLATFORM falls back to the repo name rather than to nothing.
    code, out, calls = run_poster(mod, None, NIGHTLY_JOB_RESULT="success", PLATFORM="")
    text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
    check("P16 absent PLATFORM falls back to the repo name", "sdk-android" in text, text)

    # P17 the mention lookup phase is bounded, so it cannot eat the step's own timeout before the thread is
    # posted. Twelve failures x two distinct authors is 24 lookups; a stalling Slack must not run them all.
    slow = {**FACTS_RED, "failures": [
        {"suite": f"com.payabli.sdk.core.T{i}", "case": "c", "label": f"T{i} > c", "detail": "d",
         "kind": "failure",
         "culprits": [{"sha": f"aaa{i}", "author": f"Author {i}", "email": f"a{i}@x", "subject": "s",
                       "what": "test"},
                      {"sha": f"bbb{i}", "author": f"Other {i}", "email": f"b{i}@x", "subject": "s",
                       "what": "Thing"}]}
        for i in range(12)]}
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    # Probed rather than assumed, so a poster without a budget reports a clean failure instead of raising
    # AttributeError and killing the run before it prints a verdict.
    if not hasattr(mod, "LOOKUP_BUDGET_SECONDS"):
        check("P17 the lookup phase has a global budget", False, "LOOKUP_BUDGET_SECONDS is not defined")
        check("P17 a spent budget stops looking up", False, "no budget to spend")
        return
    saved_budget, saved_timeout = mod.LOOKUP_BUDGET_SECONDS, mod.LOOKUP_TIMEOUT_SECONDS
    mod.LOOKUP_BUDGET_SECONDS = 0  # budget already spent: not one lookup should be attempted
    try:
        code, out, calls = run_poster(mod, slow, SLACK_MENTION_CULPRITS="true")
        lookups = [c for c in calls if c["method"] == "users.lookupByEmail"]
        thread_text = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
        check("P17 a spent budget stops looking up", len(lookups) == 0, f"{len(lookups)} lookups")
        check("P17 a spent budget still posts the thread", len(posts(calls)) == 2, str(len(posts(calls))))
        check("P17 a spent budget falls back to plain names",
              "Author 0" in thread_text and "<@" not in thread_text, thread_text)
    finally:
        mod.LOOKUP_BUDGET_SECONDS, mod.LOOKUP_TIMEOUT_SECONDS = saved_budget, saved_timeout
    check("P17 per-lookup timeout leaves room inside the 300s step bound",
          20 + MAX_LOOKUPS * mod.LOOKUP_TIMEOUT_SECONDS + 20 < 300,
          f"20 + {MAX_LOOKUPS} x {mod.LOOKUP_TIMEOUT_SECONDS} + 20")
    check("P17 the global budget bounds it regardless of failure count",
          20 + mod.LOOKUP_BUDGET_SECONDS + mod.LOOKUP_TIMEOUT_SECONDS + 20 < 300,
          f"20 + {mod.LOOKUP_BUDGET_SECONDS} + {mod.LOOKUP_TIMEOUT_SECONDS} + 20")

    # P7 unreadable / wrong schema
    code, out, calls = run_poster(mod, "{not json", NIGHTLY_JOB_RESULT="failure")
    check("P7 unreadable facts degrade to no-report", len(posts(calls)) == 1 and "no report" in
          posts(calls)[0]["payload"]["blocks"][0]["text"]["text"])
    code, out, calls = run_poster(mod, {**FACTS_RED, "schema": 99})
    check("P7 future schema refused, not half-rendered", "Unsupported nightly facts schema" in out, out)
    check("P7 future schema still announces", len(posts(calls)) == 1)

    # P8 escaping and control-sequence injection
    evil = {**FACTS_RED, "failures": [{**FACTS_RED["failures"][0],
                                       "label": "Evil<!channel>Test > a`b",
                                       "detail": "expected:<a> but was:<b> & <!here>",
                                       "culprits": [{"sha": "abc", "author": "<!everyone>", "email": "e@x",
                                                     "subject": "fix <script> & `stuff`", "what": "test"}]}],
            "run": {**FACTS_RED["run"], "ref": "feat/<!channel>`x`"}}
    code, out, calls = run_poster(mod, evil)
    p = posts(calls)
    thread_text = p[1]["payload"]["blocks"][0]["text"]["text"]
    parent_ctx = p[0]["payload"]["blocks"][1]["elements"][0]["text"]
    check("P8 no raw <! in thread", "<!" not in thread_text, thread_text)
    check("P8 no raw <! in parent context", "<!" not in parent_ctx, parent_ctx)
    check("P8 angle brackets escaped", "&lt;!channel&gt;" in thread_text, thread_text)
    check("P8 backticks neutralised in dynamic text", "a'b" in thread_text, thread_text)
    check("P8 ampersand escaped", "&amp;" in thread_text, thread_text)
    check("P8 links still intact", "|stack trace>" in thread_text, thread_text)

    # P9 truncation announces itself
    many = {**FACTS_RED, "failures": [
        {"suite": f"com.payabli.sdk.core.T{i}", "case": "c" * 120, "label": f"T{i} > " + "c" * 120,
         "detail": "d" * 300, "kind": "failure",
         "culprits": [{"sha": "abc1234", "author": "Dana Rivera", "email": "d@x",
                       "subject": "s" * 90, "what": "test"}]}
        for i in range(20)]}
    code, out, calls = run_poster(mod, many)
    thread_text = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P9 thread within the block limit", len(thread_text) <= 2900, str(len(thread_text)))
    check("P9 truncation announces the hidden count", "further failure(s) not listed here" in thread_text,
          thread_text[-300:])
    import re as _re
    m = _re.search(r"_(\d+) further failure", thread_text)
    listed = thread_text.count("• `")
    check("P9 hidden count is honest", m and int(m.group(1)) == 20 - listed,
          f"claims {m.group(1) if m else '?'} hidden, listed {listed} of 20")

    # P9b one single oversized entry: drops to zero entries rather than slicing
    huge = {**FACTS_RED, "failures": [{**FACTS_RED["failures"][0], "label": "X" * 400,
                                       "detail": "y" * 3000, "culprits": []}]}
    code, out, calls = run_poster(mod, huge)
    thread_text = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P9b oversized single entry stays within limit", len(thread_text) <= 2900, str(len(thread_text)))
    check("P9b oversized single entry announces omission", "1 further failure(s)" in thread_text, thread_text)

    # P10 mentions
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    code, out, calls = run_poster(mod, FACTS_RED)
    check("P10 mentions off by default: no lookup", not any(c["method"] == "users.lookupByEmail" for c in calls))
    thread_text = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P10 mentions off: plain name", "Dana Rivera" in thread_text and "<@" not in thread_text, thread_text)

    code, out, calls = run_poster(mod, FACTS_RED, SLACK_MENTION_CULPRITS="true")
    lookups = [c for c in calls if c["method"] == "users.lookupByEmail"]
    thread_text = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P10 mentions on: lookup called", len(lookups) == 2, str(len(lookups)))
    check("P10 mentions on: mention rendered", "<@U0LOOKUP>" in thread_text, thread_text)
    check("P10 lookup is authenticated", lookups[0]["auth"] == "Bearer xoxb-not-a-real-token")
    check("P10 email is url-encoded in the query", "email=dana%40payabli.com" in lookups[0]["path"],
          lookups[0]["path"])

    FakeSlack.behaviour = {"chat.postMessage": ok_parent, "users.lookupByEmail": {"ok": False, "error": "users_not_found"}}
    code, out, calls = run_poster(mod, FACTS_RED, SLACK_MENTION_CULPRITS="true")
    thread_text = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P10 lookup failure falls back to the name", "Dana Rivera" in thread_text and "<@" not in thread_text,
          thread_text)
    check("P10 lookup failure is not warned about", "users_not_found" not in out, out)

    # P10c mentions on, same email twice: cached, looked up once
    twice = {**FACTS_RED, "failures": [FACTS_RED["failures"][0],
                                       {**FACTS_RED["failures"][0], "label": "OtherTest > b"}]}
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    code, out, calls = run_poster(mod, twice, SLACK_MENTION_CULPRITS="true")
    lookups = [c for c in calls if c["method"] == "users.lookupByEmail"]
    check("P10c lookups are cached per email", len(lookups) == 2, f"{len(lookups)} lookups for 2 distinct emails x2")

    # P10d only a case-insensitive "true" enables mentions; nothing else does
    for value, expected_on in (("true", True), ("True", True), ("TRUE", True), (" true ", True),
                               ("1", False), ("yes", False), ("false", False), ("", False), ("off", False)):
        code, out, calls = run_poster(mod, FACTS_RED, SLACK_MENTION_CULPRITS=value)
        on = any(c["method"] == "users.lookupByEmail" for c in calls)
        check(f"P10d SLACK_MENTION_CULPRITS={value!r} -> mentions {'on' if expected_on else 'off'}",
              on == expected_on, f"lookup called: {on}")

    # P11 accepted but no ts
    FakeSlack.behaviour = {"chat.postMessage": [{"ok": True, "channel": "C0BLLFM863V"}]}
    code, out, calls = run_poster(mod, FACTS_RED)
    check("P11 missing ts exits 0", code == 0)
    check("P11 missing ts posts no thread", len(posts(calls)) == 1)
    check("P11 missing ts warns", "returned no ts" in out, out)

    # P12 HTTP error and unreachable host
    FakeSlack.behaviour = {"chat.postMessage": [{"_status": 500, "ok": False, "error": "internal_error"}]}
    code, out, calls = run_poster(mod, FACTS_RED)
    check("P12 http 500 exits 0", code == 0)
    check("P12 http 500 warns", "HTTP 500" in out, out)

    dead = load_poster("http://127.0.0.1:1/api")
    code, out, calls = run_poster(dead, FACTS_RED)
    check("P12 unreachable slack exits 0", code == 0)
    check("P12 unreachable slack warns", "could not be reached" in out, out)


    # P14 the three coverage states each get their own phrasing
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    code, out, calls = run_poster(mod, FACTS_RED)
    parent_text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
    check("P14 measured renders a percentage", "core 87.2%" in parent_text, parent_text)
    check("P14 empty renders 'no classes yet'", "payin no classes yet" in parent_text, parent_text)
    check("P14 missing renders 'no report written'", "taptopay no report written" in parent_text, parent_text)
    check("P14 missing is not silently dropped", "taptopay" in parent_text, parent_text)
    check("P14 inapplicable renders as no branches, not no classes yet",
          "telemetry no branches" in parent_text, parent_text)
    check("P14 inapplicable is not conflated with empty",
          "telemetry no classes yet" not in parent_text, parent_text)
    check("P14 empty module list does not render a bare label",
          "*Coverage (line)* no modules configured" in parent_text, parent_text)

    # P15 a truncated Slack response must warn, not crash. http.client.IncompleteRead is not an OSError,
    # so a handler built from URLError and OSError alone let it escape and killed the poster.
    FakeSlack.behaviour = {"chat.postMessage": [{"ok": True, "ts": "1785408441.829119", "_truncate": True}]}
    try:
        code, out, calls = run_poster(mod, FACTS_RED)
        crashed = False
    except Exception as error:
        code, out, crashed = None, f"{type(error).__name__}: {error}", True
    check("P15 truncated response does not crash the poster", not crashed, out)
    check("P15 truncated response exits 0", code == 0, str(code))
    check("P15 truncated response warns", "could not be reached" in (out or ""), out or "")
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}

    # P18 URLs come from this job's trusted environment, never from the artifact. The facts above carry a
    # poisoned run block; a tampered value inside `<url|label>` closes the link and whatever follows is
    # parsed as mrkdwn, so `<!channel>` would broadcast from the very job that was split out to prevent it.
    # The trusted sha and ref are supplied here rather than left unset, because the absence checks below are
    # only worth something if the context block would otherwise carry the values they are checking for. With
    # no GITHUB_SHA the poisoned sha has nothing to displace, and "the poison is absent" passes on a block
    # that renders no sha at all.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    code, out, calls = run_poster(mod, FACTS_RED, GITHUB_SHA="045eebf9c1d2", GITHUB_REF_NAME="main")
    parent = posts(calls)[0]["payload"]
    ctx = parent["blocks"][1]["elements"][0]["text"]
    thread_text = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P18 poisoned run url never reaches the context block", "evil.test" not in ctx, ctx)
    check("P18 poisoned run url never reaches the thread", "evil.test" not in thread_text, thread_text)
    check("P18 no raw control sequence anywhere in the parent", "<!" not in json.dumps(parent), json.dumps(parent))
    check("P18 no raw control sequence anywhere in the thread",
          "<!" not in json.dumps(posts(calls)[1]["payload"]), json.dumps(posts(calls)[1]["payload"]))
    check("P18 the trusted run id is used instead", "30609394288" in ctx, ctx)
    # The pair: the environment's sha and ref are rendered, and the artifact's are not. Either one alone
    # passes without testing the substitution.
    check("P18 the trusted sha is rendered", "045eebf" in ctx, ctx)
    check("P18 the trusted ref is rendered", "on `main`" in ctx, ctx)
    check("P18 the poisoned sha is not rendered", "aaa" not in ctx, ctx)

    # P19 the notification fallback is escaped too. It is rendered as mrkdwn, so a control sequence there
    # broadcasts even when every visible block is clean.
    evil_fallback = {**FACTS_RED, "platform": "Android<!channel>",
                     "suites": [{"name": "Unit<!here>", "label": "1 failed <!everyone>"}]}
    code, out, calls = run_poster(mod, evil_fallback)
    fallback = posts(calls)[0]["payload"]["text"]
    check("P19 fallback escapes the platform", "<!" not in fallback, fallback)
    check("P19 fallback escapes suite names and labels", "&lt;!here&gt;" in fallback, fallback)
    code, out, calls = run_poster(mod, None, NIGHTLY_JOB_RESULT="success", PLATFORM="X<!channel>")
    check("P19 no-report fallback escapes the platform too",
          "<!" not in posts(calls)[0]["payload"]["text"], posts(calls)[0]["payload"]["text"])

    # P20 the coverage label crossed the artifact too, and reached both the heading and the inapplicable
    # phrase raw. A tampered label restores the broadcast path the job split exists to close.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    evil_label = {**FACTS_RED, "coverage": [
        {"label": "branch<!channel>", "modules": [{"module": "core", "percent": 87.2, "state": "measured"},
                                                  {"module": "payin", "percent": None,
                                                   "state": "inapplicable"}]}]}
    code, out, calls = run_poster(mod, evil_label)
    parent_text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
    check("P20 tampered coverage label does not reach the heading raw", "<!" not in parent_text, parent_text)
    check("P20 tampered label is escaped in the heading", "branch&lt;!channel&gt;" in parent_text, parent_text)
    check("P20 unknown label still renders a phrase for inapplicable",
          "payin no branch" in parent_text or "data" in parent_text, parent_text)
    check("P20 nothing raw anywhere in the payload",
          "<!" not in json.dumps(posts(calls)[0]["payload"]), json.dumps(posts(calls)[0]["payload"]))

    # P21 a measured row whose percent is not a number must not kill the poster. `:.1f` against a string
    # raises, and everything in the facts crossed a boundary the third-party action can write to.
    bad_percent = {**FACTS_RED, "coverage": [
        {"label": "branch", "modules": [{"module": "core", "percent": "not-a-number", "state": "measured"}]}]}
    try:
        code, out, calls = run_poster(mod, bad_percent)
        crashed = False
    except Exception as error:
        code, crashed, out = None, True, f"{type(error).__name__}: {error}"
    check("P21 non-numeric percent does not crash the poster", not crashed, out)
    check("P21 non-numeric percent still posts", not crashed and len(posts(calls)) >= 1)

    # P22 the verdict is reconciled against the job result. The facts are uploaded two steps before the gate,
    # so a test job that dies afterwards leaves a green artifact behind on a red run; posting that green is
    # the exact run-versus-notification disagreement this workflow exists to prevent. A tampered collector
    # arrives at the same place: it can write `green`, but it cannot make a failed step report success.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    green_facts = {**FACTS_RED, "verdict": "green", "failures": [],
                   "suites": [{"name": "Unit", "label": "all 335 passed"}]}
    code, out, calls = run_poster(mod, green_facts, NIGHTLY_JOB_RESULT="success")
    check("P22 a genuinely green run posts nothing", len(posts(calls)) == 0, str(len(posts(calls))))
    text = mod.summary_blocks(green_facts, "success")[0][0]["text"]["text"]
    check("P22 a genuinely green run still reads green", ":white_check_mark:" in text, text)
    check("P22 green run adds no mismatch note", "did not finish" not in text, text)

    for bad in ("cancelled", "failure", "timed_out"):
        code, out, calls = run_poster(mod, green_facts, NIGHTLY_JOB_RESULT=bad)
        text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
        fb = posts(calls)[0]["payload"]["text"]
        check(f"P22 green facts + job {bad} is not reported green", ":white_check_mark:" not in text, text)
        check(f"P22 green facts + job {bad} is reported red", ":red_circle:" in text, text)
        check(f"P22 the mismatch is named ({bad})", "did not finish" in text and bad in text, text)
        check(f"P22 the notification does not say green ({bad})", "green" not in fb, fb)
    # Counts are still printed, because the message is corrected rather than discarded.
    code, out, calls = run_poster(mod, green_facts, NIGHTLY_JOB_RESULT="cancelled")
    text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
    check("P22 the suite counts survive the correction", "all 335 passed" in text, text)

    # P23 a malformed artifact must still produce a message. Matching the schema number is not the same as
    # being renderable: `{"schema": 4}` passed the version check and then raised KeyError, and a list root
    # raised AttributeError before any check ran, so the poster died before posting even the fallback. A
    # silent channel on a night when something is already wrong is the outcome this reporter exists to stop.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    malformed = [
        ("a list root", "[]"),
        ("a bare number", "7"),
        ("a bare string", '"nope"'),
        ("schema only", '{"schema": 4}'),
        ("suites not a list", '{"schema":4,"verdict":"red","platform":"A","suites":{},"coverage":[],"failures":[]}'),
        ("verdict not a string", '{"schema":4,"verdict":1,"platform":"A","suites":[],"coverage":[],"failures":[]}'),
        ("nested suite garbage",
         '{"schema":4,"verdict":"red","platform":"A","suites":[{}],"coverage":[],"failures":[]}'),
        ("nested coverage garbage",
         '{"schema":4,"verdict":"red","platform":"A","suites":[],"coverage":[{"label":"b"}],"failures":[]}'),
    ]
    for name, payload in malformed:
        try:
            code, out, calls = run_poster(mod, payload, NIGHTLY_JOB_RESULT="failure")
            crashed = False
        except Exception as error:
            code, out, calls, crashed = None, f"{type(error).__name__}: {error}", [], True
        check(f"P23 {name} does not crash the poster", not crashed, out)
        check(f"P23 {name} still posts something", not crashed and len(posts(calls)) == 1,
              str(len(posts(calls))) if not crashed else out)
        if not crashed:
            check(f"P23 {name} warns about it", "::warning::" in out, out)
            check(f"P23 {name} falls back to the no-report message",
                  "no report" in posts(calls)[0]["payload"]["blocks"][0]["text"]["text"],
                  posts(calls)[0]["payload"]["blocks"][0]["text"]["text"])

    # P23b a good facts file with a broken failure entry keeps the summary and loses only the detail.
    broken_detail = {**FACTS_RED, "failures": [{"label": "T > c"}]}
    try:
        code, out, calls = run_poster(mod, broken_detail)
        crashed = False
    except Exception as error:
        code, out, calls, crashed = None, f"{type(error).__name__}", [], True
    check("P23b a broken failure entry does not crash the poster", not crashed, out)
    check("P23b the summary still lands", not crashed and len(posts(calls)) >= 1)
    check("P23b only the detail is lost",
          not crashed and len(posts(calls)) == 1 and "could not be rendered" in out, out)

    # P24 modules sharing a phrase are named together, so the line stops repeating itself. Most
    # nights three of four entries are the same words; spelling each out ran to 104 characters and wrapped on
    # a phone. Every module must still be named, and COVERAGE_MODULES order must survive.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}

    def coverage_line(facts_in, label="branch"):
        code, out, calls = run_poster(mod, facts_in)
        text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
        return next(l for l in text.splitlines() if f"Coverage ({label})" in l)

    def cov(*rows):
        return {**FACTS_RED, "failures": [],
                "coverage": [{"label": "branch",
                              "modules": [{"module": m, "percent": p, "state": s} for m, p, s in rows]}]}

    today = cov(("core", 87.2, "measured"), ("payin", None, "empty"),
                ("taptopay", None, "empty"), ("telemetry", None, "empty"))
    line = coverage_line(today)
    check("P24 the repeated phrase appears once", line.count("no classes yet") == 1, line)
    check("P24 the sharing modules are named together", "payin, taptopay, telemetry no classes yet" in line, line)
    check("P24 the measured module keeps its own percentage", "core 87.2%" in line, line)
    check("P24 every module is still named",
          all(m in line for m in ("core", "payin", "taptopay", "telemetry")), line)
    check("P24 the line is shorter than spelling each out", len(line) < 100, f"{len(line)} chars: {line}")

    # Order preserved and states never merged across a different one in between.
    mixed = cov(("core", 87.2, "measured"), ("payin", None, "empty"),
                ("taptopay", None, "missing"), ("telemetry", None, "empty"))
    line = coverage_line(mixed)
    check("P24 a different state in the middle is not merged over",
          "payin no classes yet" in line and "taptopay no report written" in line
          and "telemetry no classes yet" in line, line)
    check("P24 declared module order survives",
          line.index("core") < line.index("payin") < line.index("taptopay") < line.index("telemetry"), line)
    check("P24 states are never conflated", "taptopay no classes yet" not in line, line)

    # Two modules on the same percentage must not share, because a percentage is one module's fact.
    same = cov(("core", 80.0, "measured"), ("payin", 80.0, "measured"),
               ("taptopay", None, "empty"), ("telemetry", None, "empty"))
    line = coverage_line(same)
    check("P24 equal percentages are not shared", "core 80.0% · payin 80.0%" in line, line)

    # inapplicable still gets its own phrasing and can share with its neighbours.
    inapp = cov(("core", None, "inapplicable"), ("payin", None, "empty"),
                ("taptopay", None, "empty"), ("telemetry", None, "empty"))
    line = coverage_line(inapp)
    check("P24 inapplicable keeps its phrase", "core no branches" in line, line)
    check("P24 inapplicable does not absorb the empties",
          "payin, taptopay, telemetry no classes yet" in line, line)

    # And the degenerate case still says something rather than rendering a bare label.
    empty_group = {**FACTS_RED, "failures": [], "coverage": [{"label": "branch", "modules": []}]}
    line = coverage_line(empty_group)
    check("P24 no modules still renders a phrase", "no modules configured" in line, line)

    # P25 the dead-man's switch. Green goes silent, so silence can no longer be read as health,
    # and this repo can stop silently: it is public, so scheduled workflows are auto-disabled after 60 days of
    # inactivity with no notification, and GitHub drops queued scheduled jobs under load. The switch makes
    # prolonged silence itself the alarm, on Slack's clock rather than GitHub's.
    import time as _time
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}

    for label, facts_in, expect_posts in (("green", {**FACTS_RED, "verdict": "green", "failures": []}, 0),
                                          ("red", FACTS_RED, 2)):
        code, out, calls = run_poster(mod, facts_in)
        armed = of(calls, "chat.scheduleMessage")
        check(f"P25 the switch is armed on a {label} night", len(armed) == 1, str(len(armed)))
        check(f"P25 the {label} night still posts what it should", len(posts(calls)) == expect_posts,
              f"{len(posts(calls))} posts, expected {expect_posts}")
        check(f"P25 the switch is armed before anything is cancelled ({label})",
              before(calls, "chat.scheduleMessage", "chat.scheduledMessages.list"),
              str([c["method"] for c in calls]))

    # The window: about 26 hours out, because measured scheduled runs fire 42-53 minutes late and a tighter
    # window would cry wolf nightly.
    code, out, calls = run_poster(mod, FACTS_RED)
    armed_calls = of(calls, "chat.scheduleMessage")
    post_at = armed_calls[0]["payload"].get("post_at", 0) if armed_calls else 0
    delta_h = (post_at - _time.time()) / 3600 if post_at else -1
    check("P25 the window is about 26 hours", 25.5 < delta_h < 26.5, f"{delta_h:.2f}h")
    check("P25 the window leaves room for the measured 53 minute delay", delta_h > 24 + 1,
          f"{delta_h:.2f}h")

    armed_payload = armed_calls[0]["payload"] if armed_calls else {}
    check("P25 the alarm names the platform", "Android" in json.dumps(armed_payload), json.dumps(armed_payload))
    alarm_text = (armed_payload.get("blocks") or [{}])[0].get("text", {}).get("text", "")
    # Matched case-insensitively on the substance, so a rewording does not fail the test for the wrong reason.
    lowered = alarm_text.lower()
    check("P25 the alarm says the run did not happen, not merely that it failed",
          "did not run" in lowered and "not just a red suite" in lowered, alarm_text)
    # "actions minutes" used to be the second half of this. It was removed rather than reworded: Actions is free
    # on standard runners in public repositories, so it named a cause that cannot occur here. P34 owns that now.
    check("P25 the alarm names where to look",
          "workflow is still enabled" in lowered and "githubstatus.com" in lowered, alarm_text)
    # Read at a glance, measured as scannability rather than as a character budget. A raw length bound was the
    # wrong proxy: turning the checks into a numbered list added characters while making it easier to read, so
    # the bound would have argued against the improvement. What matters is that no line is a wall and there
    # are few enough lines to take in at once.
    # `max()` on an empty sequence raises, and alarm_lines is empty whenever nothing was armed, which is
    # exactly the state a sabotage run creates. A check that cannot fail cleanly kills the harness before it
    # prints a verdict, and a run with no FAIL lines reads as a run with no failures.
    alarm_lines = [ln for ln in alarm_text.splitlines() if ln.strip()]
    check("P25 an alarm was armed at all to inspect", bool(alarm_lines), repr(alarm_text[:80]))
    check("P25 no line of the alarm is a wall of text",
          bool(alarm_lines) and max(len(ln) for ln in alarm_lines) <= 110,
          str([(len(ln), ln[:40]) for ln in alarm_lines]))
    check("P25 the alarm is few enough lines to take in at once", 0 < len(alarm_lines) <= 7,
          f"{len(alarm_lines)} lines")
    check("P25 the checks are presented as ordered steps",
          "Check, in order:" in alarm_text and "\n1." in alarm_text and "\n3." in alarm_text, alarm_text)
    # Slack documents that a scheduled message using the metadata parameter will not post, which would
    # silently disarm the switch. Asserting its absence, since that failure would be invisible.
    check("P25 no metadata parameter, which would stop it posting", "metadata" not in armed_payload,
          json.dumps(armed_payload))

    # P26 arm, then cancel. Cancelling first opens a window with no alarm pending, and a run that dies inside
    # it disarms the dead-man's switch with nothing to report that it happened. Arming first means the worst
    # case is a duplicate alarm, which is noisy and visible, rather than a missing one, which is silent.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [
            {"id": "Q0OLD1", "channel": "C0BLLFM863V", "post_at": 1, "text": "[nightly-liveness:Android] old"},
            {"id": "Q0OLD2", "post_at": 2, "text": "[nightly-liveness:Android] older"}]},
    }
    code, out, calls = run_poster(mod, FACTS_RED)
    deleted = of(calls, "chat.deleteScheduledMessage")
    check("P26 every pending alarm is cancelled", len(deleted) == 2, str(len(deleted)))
    check("P26 the cancelled ids are the listed ones",
          {d["payload"]["scheduled_message_id"] for d in deleted} == {"Q0OLD1", "Q0OLD2"},
          json.dumps([d["payload"] for d in deleted]))
    check("P26 arms before cancelling, so an alarm is always pending",
          before(calls, "chat.scheduleMessage", "chat.deleteScheduledMessage"),
          str([c["method"] for c in calls]))
    check("P26 a fresh alarm is armed after cancelling", len(of(calls, "chat.scheduleMessage")) == 1)

    # P26b the sweep pages. An alarm the sweep never sees is never cancelled and fires as a false alarm, so
    # reading one page deep is a correctness bug rather than a scale nicety. Page 2 holds the only stale alarm.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "chat.scheduledMessages.list": [
            {"ok": True, "scheduled_messages": [
                {"id": "Q0FILLER", "post_at": 1, "text": "unmarked, so not ours"}],
             "response_metadata": {"next_cursor": "page2"}},
            {"ok": True, "scheduled_messages": [
                {"id": "Q0PAGE2", "post_at": 2, "text": "[nightly-liveness:Android] only on page two"}],
             "response_metadata": {"next_cursor": ""}},
        ],
    }
    code, out, calls = run_poster(mod, FACTS_RED)
    listed = of(calls, "chat.scheduledMessages.list")
    deleted = of(calls, "chat.deleteScheduledMessage")
    check("P26b follows next_cursor to a second page", len(listed) == 2, str(len(listed)))
    check("P26b the cursor is sent on the second request",
          len(listed) > 1 and "cursor=page2" in listed[1].get("path", ""),
          json.dumps([c.get("path") for c in listed]))
    check("P26b the first request sends no cursor",
          bool(listed) and "cursor=" not in listed[0].get("path", ""), str(listed[:1]))
    check("P26b an alarm only on page two is still cancelled",
          [d["payload"]["scheduled_message_id"] for d in deleted] == ["Q0PAGE2"],
          json.dumps([d["payload"] for d in deleted]))
    check("P26b an empty next_cursor stops the paging", len(listed) == 2, str(len(listed)))
    check("P26b paging does not warn on a clean sweep", "::warning::" not in out, out)

    # P26c a cursor that never empties must terminate, and must say the sweep was incomplete rather than
    # truncating in silence. A repeating cursor is the shape a malformed or buggy response takes.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [],
                                        "response_metadata": {"next_cursor": "forever"}},
    }
    code, out, calls = run_poster(mod, FACTS_RED)
    listed = of(calls, "chat.scheduledMessages.list")
    # Probed, not read. A poster with no page cap is exactly the version this check exists to fail, and
    # `mod.MAX_SWITCH_PAGES` on it raises AttributeError, which kills the run before it prints any verdict.
    cap = getattr(mod, "MAX_SWITCH_PAGES", None)
    check("P26c a page cap exists", isinstance(cap, int) and cap > 0, repr(cap))
    check("P26c a repeating cursor terminates",
          bool(cap) and 0 < len(listed) <= cap + 1, f"cap={cap!r} listed={len(listed)}")
    check("P26c the page cap is honoured exactly", len(listed) == cap, f"cap={cap!r} listed={len(listed)}")
    check("P26c an exhausted sweep still exits 0", code == 0, str(code))
    check("P26c an exhausted sweep says so rather than truncating silently",
          "::warning::" in out and "pages of scheduled messages" in out, out)
    check("P26c the report still posted despite the exhausted sweep",
          len(of(calls, "chat.postMessage")) >= 1, str([c["method"] for c in calls]))

    # P27 a Slack failure must leave the switch armed rather than silently disarmed, and must not crash.
    for label, behaviour in (
        ("list refused", {"chat.scheduledMessages.list": {"ok": False, "error": "channel_not_found"}}),
        ("list 500", {"chat.scheduledMessages.list": {"_status": 500, "ok": False, "error": "oops"}}),
        ("arming refused", {"chat.scheduleMessage": [{"ok": False, "error": "restricted_too_many"}]}),
        # Marked, or the cancel step correctly skips it and there is no refusal to warn about.
        # Marked and provably older, or the cancel correctly retains it and there is no refusal to warn about.
        ("delete refused", {"chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [
                                {"id": "Q1", "post_at": 1, "text": "[nightly-liveness:Android] earlier"}]},
                            "chat.deleteScheduledMessage": [{"ok": False, "error": "bad_id"}]}),
    ):
        FakeSlack.behaviour = {"chat.postMessage": ok_parent, **behaviour}
        try:
            code, out, calls = run_poster(mod, FACTS_RED)
            crashed = False
        except Exception as error:
            code, out, crashed = None, f"{type(error).__name__}: {error}", True
        check(f"P27 {label} does not crash the poster", not crashed, out)
        check(f"P27 {label} still exits 0", code == 0, str(code))
        check(f"P27 {label} warns", "::warning::" in out, out)

    # P28 a green night with no credentials must not try to arm anything.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    code, out, calls = run_poster(mod, {**FACTS_RED, "verdict": "green", "failures": []}, SLACK_BOT_TOKEN="")
    check("P28 absent credentials arm nothing", len(calls) == 0, str([c["method"] for c in calls]))

    # P29 the commit range since the last green nightly. Rendered through the renderer, because the lookup
    # itself needs the Actions API and a token, which the harness deliberately does not provide.
    #
    # Capability-probed first. A poster without the third parameter raises TypeError, and a poster without the
    # lookup raises AttributeError, either of which would kill the run before it printed a verdict.
    import inspect as _inspect
    if len(_inspect.signature(mod.summary_blocks).parameters) < 3 or not hasattr(mod, "commits_since_last_green"):
        check("P29 the summary can render a commit range", False,
              "summary_blocks takes no since_green argument, or commits_since_last_green is absent")
        check("P30 the lookup declines to guess without a token", False, "commits_since_last_green is absent")
        return
    rng = {"base": "b9a8e27", "head": "045eebf", "count": 12,
           "url": "https://github.com/payabli/sdk-android/compare/b9a8e27...045eebf", "when": "x"}
    text = mod.summary_blocks(FACTS_RED, "success", rng)[0][0]["text"]["text"]
    check("P29 the range names the commit count", "12 commits" in text, text)
    check("P29 the range links a compare", "|b9a8e27...045eebf>" in text, text)
    check("P29 the per-file heuristic is kept alongside it",
          "Since the last green nightly" in text, text)
    one = mod.summary_blocks(FACTS_RED, "success", {**rng, "count": 1})[0][0]["text"]["text"]
    check("P29 one commit is not pluralised", "1 commit ·" in one, one)
    unknown = mod.summary_blocks(FACTS_RED, "success", {**rng, "count": None})[0][0]["text"]["text"]
    check("P29 an unknown count still links the compare", "the commits" in unknown and "compare" in unknown,
          unknown)
    absent = mod.summary_blocks(FACTS_RED, "success", None)[0][0]["text"]["text"]
    check("P29 no baseline means no line rather than a wrong one",
          "Since the last green nightly" not in absent, absent)
    check("P29 the range never reaches Slack unescaped",
          "<!" not in json.dumps(mod.summary_blocks(
              FACTS_RED, "success", {**rng, "base": "a<!channel>"})[0]),
          json.dumps(mod.summary_blocks(FACTS_RED, "success", {**rng, "base": "a<!channel>"})[0]))

    # P30 the lookup declines to guess when it has no token, so a green night costs no API calls either way.
    saved = {k: os.environ.pop(k, None) for k in ("GITHUB_TOKEN",)}
    try:
        check("P30 no token means no range rather than a guess", mod.commits_since_last_green() is None)
    finally:
        for k, v in saved.items():
            if v is not None:
                os.environ[k] = v
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}

    # P33 (PR #16 review) a failed sweep is a failed reset. Arming worked, so the old code called it success and
    # stayed silent on green; the alarm the sweep should have removed is due before the next nightly, so it fires
    # and reports a stopped nightly that did not stop. Too many pending alarms is a failure, not only too few.
    for label, behaviour in (
        ("a refused delete", {
            "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [
                {"id": "Q0STALE", "post_at": 1, "text": "[nightly-liveness:Android] yesterday"}]},
            "chat.deleteScheduledMessage": [{"ok": False, "error": "invalid_scheduled_message_id"}]}),
        ("an unreadable list", {"chat.scheduledMessages.list": {"ok": False, "error": "ratelimited"}}),
        ("an exhausted page walk", {
            "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [],
                                            "response_metadata": {"next_cursor": "forever"}}}),
    ):
        FakeSlack.behaviour = {"chat.postMessage": ok_parent, **behaviour}
        code, out, calls = run_poster(mod, {**FACTS_RED, "verdict": "green", "failures": []})
        posted = of(calls, "chat.postMessage")
        check(f"P33 {label} does not count as a reset, so green posts", len(posted) == 1,
              str([c["method"] for c in calls]))
        check(f"P33 {label} warns rather than claiming the switch is armed",
              "::warning::" in out and "::notice::Liveness switch armed" not in out, out)
        check(f"P33 {label} still exits 0", code == 0, str(code))
        check(f"P33 {label} still armed a fresh alarm", len(of(calls, "chat.scheduleMessage")) == 1,
              str([c["method"] for c in calls]))

    # ...and the clean case must stay silent, or the above has just disabled silent-green altogether.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [
            {"id": "Q0STALE", "post_at": 1, "text": "[nightly-liveness:Android] yesterday"}]},
    }
    code, out, calls = run_poster(mod, {**FACTS_RED, "verdict": "green", "failures": []})
    check("P33 a clean sweep still means a silent green night", not of(calls, "chat.postMessage"),
          str([c["method"] for c in calls]))
    check("P33 a clean sweep says the switch is armed", "::notice::Liveness switch armed" in out, out)
    check("P33 a clean sweep did cancel the stale alarm", len(of(calls, "chat.deleteScheduledMessage")) == 1,
          str([c["method"] for c in calls]))

    # P34 (PR #16 review) the alarm must not send the reader after an inapplicable cause. GitHub documents
    # Actions as free on standard runners in public repositories, and this workflow is ubuntu-latest in a public
    # repo, so exhausted minutes cannot be the reason and checking them wastes the one read this message gets.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    _ = run_poster(mod, {**FACTS_RED, "verdict": "green", "failures": []})
    armed_now = of(_[2], "chat.scheduleMessage")
    alarm = json.dumps(armed_now[0]["payload"]) if armed_now else ""
    check("P34 the alarm does not blame Actions minutes", "minutes" not in alarm, alarm)
    check("P34 the alarm points at the Actions service status", "githubstatus.com" in alarm, alarm)
    check("P34 it still lists three things to check",
          all(f"{n}." in alarm for n in (1, 2, 3)), alarm)

    # P32 (PR #16 review) the green fallback must not run the red-only commit lookup. Green plus a refused arm
    # posts the summary as a safety net, and the lookup there spends the three Actions API calls the early green
    # decision exists to avoid, then prints a suspect range under a headline saying the nightly passed. That
    # sends someone hunting a cause that does not exist, which is worse than the wasted calls.
    #
    # The Actions API is pointed at the same fake, and it is programmed to answer plausibly, so the range would
    # render if the lookup ran. A fake that refused would make this check pass for the wrong reason.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "chat.scheduleMessage": {"ok": False, "error": "restricted_too_many"},
        "30609394288": {"workflow_id": 77},
        "runs": {"workflow_runs": [{"id": 999, "head_sha": "b9a8e27ffff"}]},
        "b9a8e27ffff...045eebf": {"status": "ahead", "total_commits": 12},
    }
    green_facts = {**FACTS_RED, "verdict": "green", "failures": []}
    code, out, calls = run_poster(mod, green_facts, GITHUB_TOKEN="ghs-fake-not-a-real-token",
                                  GITHUB_SHA="045eebf", GITHUB_REF_NAME="main",
                                  GITHUB_API_URL=mod.SLACK_API)
    posted = of(calls, "chat.postMessage")
    api_calls = [c for c in calls if "/repos/" in str(c.get("path", ""))]
    check("P32 a refused arm still posts the green summary", len(posted) == 1, str([c["method"] for c in calls]))
    check("P32 and warns that it fell back", "::warning::" in out and "could not be armed" in out, out)
    check("P32 the green fallback makes no Actions API call", not api_calls,
          json.dumps([c.get("path") for c in api_calls]))
    check("P32 no suspect range under a green headline",
          bool(posted) and "Since the last green nightly" not in json.dumps(posted[0]["payload"]),
          json.dumps(posted[0]["payload"]) if posted else "nothing posted")
    check("P32 the green fallback still exits 0", code == 0, str(code))

    # ...and the same lookup must still run on a red night, or the guard above has simply removed the feature.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "30609394288": {"workflow_id": 77},
        "runs": {"workflow_runs": [{"id": 999, "head_sha": "b9a8e27ffff"}]},
        "b9a8e27ffff...045eebf": {"status": "ahead", "total_commits": 12},
    }
    code, out, calls = run_poster(mod, FACTS_RED, GITHUB_TOKEN="ghs-fake-not-a-real-token",
                                  GITHUB_SHA="045eebf", GITHUB_REF_NAME="main",
                                  GITHUB_API_URL=mod.SLACK_API)
    posted = of(calls, "chat.postMessage")
    check("P32 a red night still looks the range up",
          [c for c in calls if "/repos/" in str(c.get("path", ""))],
          str([c.get("path") for c in calls]))
    check("P32 a red night still renders the range",
          bool(posted) and "Since the last green nightly" in json.dumps(posted[0]["payload"]),
          json.dumps(posted[0]["payload"]) if posted else "nothing posted")

    # P31 (PR #16 review) the cancel must only touch this bot's liveness alarms. SWITCH_MARKER was defined and
    # never used, so the delete cancelled every message the token had scheduled in the channel, and a comment
    # claimed otherwise. Both were defects: the wrong blast radius, and a false claim about it.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [
            {"id": "Q0MINE", "post_at": 1, "text": "[nightly-liveness:Android] an earlier alarm"},
            {"id": "Q0THEIRS", "post_at": 2, "text": "a release reminder from some other automation"},
            {"id": "Q0ALSOTHEIRS", "post_at": 3, "text": ""}]},
    }
    code, out, calls = run_poster(mod, FACTS_RED)
    deleted = {d["payload"]["scheduled_message_id"] for d in of(calls, "chat.deleteScheduledMessage")}
    check("P31 an earlier liveness alarm is cancelled", "Q0MINE" in deleted, str(deleted))
    check("P31 another automation's scheduled message is left alone",
          "Q0THEIRS" not in deleted and "Q0ALSOTHEIRS" not in deleted, str(deleted))
    armed_text = of(calls, "chat.scheduleMessage")[0]["payload"]["text"]
    check("P31 the marker is actually in the scheduled text, not just a constant",
          "nightly-liveness:Android" in armed_text, str(armed_text))
    check("P31 the freshly armed alarm is not cancelled by its own run",
          "Q0ARMED" not in deleted, str(deleted))

    # P32 never zero alarms pending. Cancel-then-arm was justified as avoiding a duplicate, which had the
    # asymmetry backwards: a duplicate is one false alarm, while cancelling then failing to arm leaves the
    # silence unmonitored, which is the one state this feature exists to prevent.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent,
                           "chat.scheduleMessage": [{"ok": False, "error": "restricted_too_many"}],
                           "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [
                               {"id": "Q0MINE", "post_at": 1, "text": "[nightly-liveness:Android] earlier"}]}}
    code, out, calls = run_poster(mod, FACTS_RED)
    check("P32 a refused arm cancels nothing, so the old alarm survives",
          len(of(calls, "chat.deleteScheduledMessage")) == 0,
          str([c["method"] for c in calls]))
    check("P32 a refused arm is warned about", "could not be armed" in out, out)

    # P33 a green night only stays silent once an alarm is confirmed. Otherwise silence plus no alarm is the
    # unmonitored state, so it falls back to posting the green summary.
    green_f = {**FACTS_RED, "verdict": "green", "failures": []}
    FakeSlack.behaviour = {"chat.postMessage": ok_parent,
                           "chat.scheduleMessage": [{"ok": False, "error": "restricted_too_many"}]}
    code, out, calls = run_poster(mod, green_f)
    check("P33 green posts the summary when the switch cannot be armed", len(posts(calls)) == 1,
          str(len(posts(calls))))
    check("P33 and says why", "could not be armed" in out, out)
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    code, out, calls = run_poster(mod, green_f)
    check("P33 green stays silent once the switch is confirmed", len(posts(calls)) == 0,
          str(len(posts(calls))))

    # P34 a failed report must not push the alarm out. The switch asserts the channel heard from the nightly,
    # and if the post was rejected it did not.
    FakeSlack.behaviour = {"chat.postMessage": [{"ok": False, "error": "channel_not_found"}]}
    code, out, calls = run_poster(mod, FACTS_RED)
    check("P34 a rejected report does not re-arm the switch",
          len(of(calls, "chat.scheduleMessage")) == 0, str([c["method"] for c in calls]))
    check("P34 a rejected report still exits 0", code == 0, str(code))
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    code, out, calls = run_poster(mod, FACTS_RED)
    check("P34 an accepted report does re-arm the switch",
          len(of(calls, "chat.scheduleMessage")) == 1, str([c["method"] for c in calls]))

    # P35 (PR #16 review) two overlapping runs must not delete each other's alarm. cancel-in-progress only
    # requests cancellation, so both can arm; each then lists both and, keeping only its own id, deletes the
    # other, leaving nothing pending while the later run reports success. Deleting only strictly-older alarms
    # is what closes it, because a run's own alarm always has the latest post_at.
    import time as _t
    now = int(_t.time())
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        # The other overlapping run's alarm, armed a moment later, so its post_at is *newer* than ours.
        "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [
            {"id": "Q0OLDER", "post_at": now + 60, "text": "[nightly-liveness:Android] armed earlier"},
            {"id": "Q0NEWER", "post_at": now + 999999, "text": "[nightly-liveness:Android] the other run's"}]},
    }
    code, out, calls = run_poster(mod, FACTS_RED)
    deleted = {d["payload"]["scheduled_message_id"] for d in of(calls, "chat.deleteScheduledMessage")}
    check("P35 a strictly older alarm is cancelled", "Q0OLDER" in deleted, str(deleted))
    check("P35 a concurrent run's newer alarm is NOT cancelled", "Q0NEWER" not in deleted, str(deleted))

    # A tie must be retained: a duplicate is one false alarm, deleting on a tie could leave zero.
    #
    # The poster's clock is pinned for this one case, because a tie is the only assertion here that turns on
    # exact equality. Deriving the fixture from a wall clock read earlier in this function left an 8.8ms
    # window: crossing a second boundary inside it makes the poster arm one second later, the "tie" becomes
    # strictly older, production correctly deletes it and the check fails for a reason that is not a defect.
    # Measured at roughly a 1-in-110 chance per run, which a sabotage pass takes 33 times.
    pinned = int(_t.time())
    real_clock = mod.time
    mod.time = _PinnedClock(real_clock, pinned)
    try:
        FakeSlack.behaviour = {"chat.postMessage": ok_parent,
                               "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [
                                   {"id": "Q0TIE", "post_at": pinned + mod.SWITCH_HOURS * 3600,
                                    "text": "[nightly-liveness:Android] tie"}]}}
        code, out, calls = run_poster(mod, FACTS_RED)
    finally:
        mod.time = real_clock
    tie_deleted = {d["payload"]["scheduled_message_id"] for d in of(calls, "chat.deleteScheduledMessage")}
    check("P35 a tie on post_at is retained, not deleted", "Q0TIE" not in tie_deleted, str(tie_deleted))

    # An unreadable timestamp is retained for the same reason.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent,
                           "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [
                               {"id": "Q0NOPOST", "text": "[nightly-liveness:Android] no post_at"},
                               {"id": "Q0BADPOST", "post_at": "soon", "text": "[nightly-liveness:Android] bad"}]}}
    code, out, calls = run_poster(mod, FACTS_RED)
    bad_deleted = {d["payload"]["scheduled_message_id"] for d in of(calls, "chat.deleteScheduledMessage")}
    check("P35 an unreadable post_at is retained", not bad_deleted, str(bad_deleted))

    # P36 no commit range unless the compare API answered. A force-push can leave the previous success
    # incomparable, and the link would 404 too, so rendering a range would be a guess with a dead reference.
    check("P36 a range needs a real commit count",
          mod.summary_blocks(FACTS_RED, "success", None)[0][0]["text"]["text"].count(
              "Since the last green nightly") == 0)

    # P37 (PR #16 review) a green night must make no GitHub API call at all. The lookup used to be evaluated
    # while building the blocks, before the green decision, so a green night paid for up to three Actions calls
    # and discarded them, and an Actions timeout would delay re-arming the switch, which is the one thing on a
    # green night that must not be delayed. A comment claimed the opposite.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    calls_seen = []
    real_github_get = mod.github_get
    mod.github_get = lambda url, token: calls_seen.append(url) or None
    try:
        # GITHUB_SHA is required by the lookup and run_poster does not set it, so pass both through or the
        # function returns early and the red-night half of this test proves nothing.
        api_env = {"GITHUB_TOKEN": "ghs-not-real", "GITHUB_SHA": "a" * 40}
        code, out, calls = run_poster(mod, {**FACTS_RED, "verdict": "green", "failures": []}, **api_env)
        check("P37 a green night makes no GitHub API call", not calls_seen, str(calls_seen))
        check("P37 a green night still arms the switch", len(of(calls, "chat.scheduleMessage")) == 1)
        calls_seen.clear()
        code, out, calls = run_poster(mod, FACTS_RED, **api_env)
        check("P37 a red night does look up the range", bool(calls_seen), str(calls_seen))
    finally:
        mod.github_get = real_github_get
        os.environ.pop("GITHUB_TOKEN", None)

    # P38 the range is only rendered when the comparison proves ancestry. Measured against the real API:
    # a reversed pair returns status `behind` with total_commits 0, and identical returns `identical` with 0,
    # so an integer count proves nothing. Re-running an older failure after a newer success is the reversed
    # case, and rewritten history returns `diverged`.
    captured = {}
    def fake_get(url, token):
        if "/actions/runs/" in url:
            return {"workflow_id": 1}
        if "/actions/workflows/" in url:
            return {"workflow_runs": [{"id": 999, "head_sha": "b" * 40, "created_at": "x"}]}
        if "/compare/" in url:
            return captured["compare"]
        return None
    mod.github_get = fake_get
    try:
        os.environ.update({"GITHUB_TOKEN": "ghs-not-real", "GITHUB_RUN_ID": "1",
                           "GITHUB_REPOSITORY": "payabli/sdk-android", "GITHUB_SHA": "a" * 40})
        # The claim is about the rendered range, not about the return being None: `behind` and `identical` now
        # answer a comparison that came out empty, which the summary still refuses to render and the thread
        # reply reads as "no new suspects". Only `diverged` and an unanswered compare stay unknown.
        for status, total, kind in (("ahead", 12, "range"), ("behind", 0, "empty"),
                                   ("identical", 0, "empty"), ("diverged", 7, "unknown")):
            captured["compare"] = {"status": status, "total_commits": total}
            got = mod.commits_since_last_green()
            text = mod.summary_blocks(FACTS_RED, "success", got)[0][0]["text"]["text"]
            check(f"P38 status {status} {'renders a range' if kind == 'range' else 'renders none'}",
                  ("Since the last green nightly" in text) == (kind == "range"), f"{status} -> {text}")
            if kind == "unknown":
                check(f"P38 status {status} leaves ancestry unknown", got is None, f"{status} -> {got}")
            elif kind == "empty":
                check(f"P38 status {status} proves the range empty",
                      got is not None and got.get("shas") == [] and bool(got.get("empty")),
                      f"{status} -> {got}")
            else:
                check(f"P38 status {status} is a real range",
                      got is not None and not got.get("empty"), f"{status} -> {got}")
    finally:
        mod.github_get = real_github_get
        os.environ.pop("GITHUB_TOKEN", None)

    # P39 only the scheduled run may reset the switch. Resetting on every run measured "somebody ran the
    # nightly at some point", so a dead schedule could be masked indefinitely by manual dispatches or probe
    # branches, which is the exact failure the switch exists to catch. That risk grows with the number of
    # people who might dispatch it.
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    green_f = {**FACTS_RED, "verdict": "green", "failures": []}

    code, out, calls = run_poster(mod, green_f, LIVENESS_OWNER="false")
    check("P39 a dispatch does not arm the switch", len(of(calls, "chat.scheduleMessage")) == 0,
          str([c["method"] for c in calls]))
    check("P39 a dispatch does not cancel the schedule's alarm",
          len(of(calls, "chat.deleteScheduledMessage")) == 0, str([c["method"] for c in calls]))
    check("P39 a green dispatch is still silent", len(posts(calls)) == 0, str(len(posts(calls))))
    check("P39 and says why it left the switch alone", "does not own the liveness switch" in out, out)

    code, out, calls = run_poster(mod, FACTS_RED, LIVENESS_OWNER="false")
    check("P39 a red dispatch still reports", len(posts(calls)) >= 1, str(len(posts(calls))))
    check("P39 a red dispatch still leaves the switch alone",
          len(of(calls, "chat.scheduleMessage")) == 0, str([c["method"] for c in calls]))

    code, out, calls = run_poster(mod, green_f, LIVENESS_OWNER="true")
    check("P39 the scheduled run does arm it", len(of(calls, "chat.scheduleMessage")) == 1)

    # Anything but a literal true is a non-owner, so a mis-set variable fails safe by leaving the alarm armed.
    for value in ("", "1", "yes", "TRUE"):
        code, out, calls = run_poster(mod, green_f, LIVENESS_OWNER=value)
        armed = len(of(calls, "chat.scheduleMessage")) == 1
        check(f"P39 LIVENESS_OWNER={value!r} owns the switch only if it means true",
              armed == (value.lower() == "true"), f"armed={armed}")

    # P40 the marker is scoped per platform, because nightly.yml states both platform SDKs can report into one
    # channel. An unscoped marker would let each platform's nightly cancel the other's alarm, so each would
    # mask the other's death, which is worse than no switch because it looks like one is present.
    code, out, calls = run_poster(mod, green_f, PLATFORM="Android")
    android_text = of(calls, "chat.scheduleMessage")[0]["payload"]["text"]
    check("P40 the marker names the platform", "nightly-liveness:Android" in android_text, str(android_text))

    FakeSlack.behaviour = {"chat.postMessage": ok_parent,
                           "chat.scheduledMessages.list": {"ok": True, "scheduled_messages": [
                               {"id": "Q0IOS", "post_at": 1, "text": "[nightly-liveness:iOS] the sibling"},
                               {"id": "Q0MINE", "post_at": 2, "text": "[nightly-liveness:Android] ours"}]}}
    code, out, calls = run_poster(mod, green_f, PLATFORM="Android")
    deleted = {d["payload"]["scheduled_message_id"] for d in of(calls, "chat.deleteScheduledMessage")}
    check("P40 the sibling platform's alarm is never cancelled", "Q0IOS" not in deleted, str(deleted))
    check("P40 our own older alarm still is", "Q0MINE" in deleted, str(deleted))

    # P13 two attributions on the same commit collapse to one line
    FakeSlack.behaviour = {"chat.postMessage": ok_parent}
    same = {**FACTS_RED, "failures": [{**FACTS_RED["failures"][0], "culprits": [
        {"sha": "5eee0f2", "author": "Alex Arguello", "email": "a@x", "subject": "Add the bearer decoration", "what": "test"},
        {"sha": "5eee0f2", "author": "Alex Arguello", "email": "a@x", "subject": "Add the bearer decoration", "what": "Retry"}]}]}
    code, out, calls = run_poster(mod, same)
    thread_text = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P13 same commit printed once", thread_text.count("5eee0f2") == 1, thread_text)
    check("P13 both subjects named on the one line", "test and `Retry` last touched by" in thread_text, thread_text)
    code, out, calls = run_poster(mod, FACTS_RED)
    thread_text = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P13 distinct commits stay on separate lines",
          "test last touched by" in thread_text and "`Retry` last touched by" in thread_text, thread_text)
    code, out, calls = run_poster(mod, same, SLACK_MENTION_CULPRITS="true")
    lookups = [c for c in calls if c["method"] == "users.lookupByEmail"]
    check("P13 merging also halves the lookups", len(lookups) == 1, str(len(lookups)))

    # P41 a culprit that was already green is reported as unchanged rather than blamed. `git log -1 -- <file>`
    # names whoever touched a file last, which for an untouched file is a commit that has passed every nightly
    # since: measured on 2026-08-12, a timing-sensitive transport test was attributed to a commit six nightlies
    # old, beside its author's name. The suspect range the summary already reports is what separates the two.
    import inspect as _inspect2
    if len(_inspect2.signature(mod.thread_blocks).parameters) < 4 or not hasattr(mod, "landed_before_last_green"):
        check("P41 a culprit outside the range is not blamed", False,
              "thread_blocks takes no since_green argument, or landed_before_last_green is absent")
        return

    inside = {"base": "b9a8e27", "head": "045eebf", "count": 2, "url": "https://x/compare", "when": "x",
              "shas": ["b9a8e27ffffffffffffffffffffffffffffffffff", "5ded50affffffffffffffffffffffffffffffff00"]}
    outside = {**inside, "shas": ["1111111ffffffffffffffffffffffffffffffffff"]}

    text_inside = mod.thread_blocks(FACTS_RED, "", False, inside)[0]["text"]["text"]
    check("P41 a culprit inside the range is still named", "last touched by" in text_inside, text_inside)
    check("P41 and is not called unchanged", "unchanged since the last green nightly" not in text_inside,
          text_inside)

    text_outside = mod.thread_blocks(FACTS_RED, "", False, outside)[0]["text"]["text"]
    check("P41 a culprit outside the range is reported as unchanged",
          text_outside.count("unchanged since the last green nightly") == 2, text_outside)
    check("P41 the unchanged line names the baseline", "`b9a8e27`" in text_outside, text_outside)
    check("P41 the unchanged line names no author",
          "Dana Rivera" not in text_outside and "Sam Okafor" not in text_outside, text_outside)
    check("P41 the unchanged line still names what was unchanged",
          "test unchanged since" in text_outside and "`Retry` unchanged since" in text_outside, text_outside)

    # No range, and a range whose commit list is short of its own count, both fall back to the sentence this
    # reporter always printed. A truncated list would otherwise read every unfetched page as an absence and
    # call every culprit unchanged.
    for label, rng in (("no range", None), ("a range with no shas", {**inside, "shas": None})):
        fallback = mod.thread_blocks(FACTS_RED, "", False, rng)[0]["text"]["text"]
        check(f"P41 {label} falls back to naming the commit", "last touched by" in fallback, fallback)

    check("P41 a truncated compare yields no sha list",
          mod.range_shas({"commits": [{"sha": "a" * 40}]}, 12) is None)
    check("P41 a complete compare yields every sha",
          mod.range_shas({"commits": [{"sha": "a" * 40}, {"sha": "b" * 40}]}, 2) == ["a" * 40, "b" * 40])
    check("P41 a compare that listed nothing yields no sha list", mod.range_shas({}, 3) is None)

    # The baseline reaches Slack from the API rather than from the facts artifact, and is escaped like
    # everything else dynamic: an unescaped one closes the code span and the rest is parsed as mrkdwn.
    poisoned = mod.thread_blocks(FACTS_RED, "", False, {**outside, "base": "a<!channel>"})
    check("P41 the baseline never reaches Slack unescaped", "<!" not in json.dumps(poisoned),
          json.dumps(poisoned))

    # End to end, through the same fake Actions API the range checks use: the compare lists one commit that is
    # neither culprit, so both files predate the last green run.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "30609394288": {"workflow_id": 77},
        "runs": {"workflow_runs": [{"id": 999, "head_sha": "b9a8e27ffff"}]},
        "b9a8e27ffff...045eebf": {"status": "ahead", "total_commits": 1,
                                  "commits": [{"sha": "9999999ffffffffffffffffffffffffffffffffff"}]},
    }
    code, out, calls = run_poster(mod, FACTS_RED, GITHUB_TOKEN="ghs-fake-not-a-real-token",
                                  GITHUB_SHA="045eebf", GITHUB_REF_NAME="main",
                                  GITHUB_API_URL=mod.SLACK_API)
    threaded = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P41 the posted thread reports the files as unchanged",
          "unchanged since the last green nightly" in threaded, threaded)
    check("P41 the posted thread blames nobody for them", "last touched by" not in threaded, threaded)
    check("P41 the range is looked up once for both readers",
          len([c for c in calls if "/compare/" in str(c.get("path", ""))]) == 1,
          str([c.get("path") for c in calls]))

    # Mentions on against the same night: nothing is looked up, because the author of a commit that has been
    # green for a week is not the person who knows, and the lookup is what turns the heuristic into a 3am ping.
    code, out, calls = run_poster(mod, FACTS_RED, SLACK_MENTION_CULPRITS="true",
                                  GITHUB_TOKEN="ghs-fake-not-a-real-token", GITHUB_SHA="045eebf",
                                  GITHUB_REF_NAME="main", GITHUB_API_URL=mod.SLACK_API)
    check("P41 an unchanged culprit is never looked up",
          not [c for c in calls if c["method"] == "users.lookupByEmail"],
          str([c["method"] for c in calls]))

    # P42 a comparison that came out empty is not the same as one that could not be made. Both used to answer
    # None, so the two cases carrying the strongest evidence that nobody is to blame, a re-run of the very
    # commit that went green and a re-run of a commit older than the green baseline, fell back to naming
    # whoever last touched the file.
    check("P42 an empty range reports every culprit as unchanged",
          mod.landed_before_last_green({"sha": "b9a8e27"}, {"base": "b9a8e27", "shas": []}),
          "an empty sha list was read as unknown")
    check("P42 an unknown range still names the commit",
          not mod.landed_before_last_green({"sha": "b9a8e27"}, {"base": "b9a8e27", "shas": None}),
          "a missing sha list was read as empty")

    # Same commit as the last green run, end to end. The lookup returns before the compare, so that call is
    # also the assertion that it never happened.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "30609394288": {"workflow_id": 77},
        "runs": {"workflow_runs": [{"id": 999, "head_sha": "045eebf"}]},
    }
    code, out, calls = run_poster(mod, FACTS_RED, SLACK_MENTION_CULPRITS="true",
                                  GITHUB_TOKEN="ghs-fake-not-a-real-token", GITHUB_SHA="045eebf",
                                  GITHUB_REF_NAME="main", GITHUB_API_URL=mod.SLACK_API)
    summary_text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
    threaded = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P42 a re-run of the green commit renders no range",
          "Since the last green nightly" not in summary_text, summary_text)
    check("P42 and reports the files as unchanged",
          "unchanged since the last green nightly" in threaded, threaded)
    check("P42 and blames nobody", "last touched by" not in threaded, threaded)
    check("P42 and looks nobody up even with mentions on",
          not [c for c in calls if c["method"] == "users.lookupByEmail"], str([c["method"] for c in calls]))
    check("P42 and never asks for a compare of a commit with itself",
          not [c for c in calls if "/compare/" in str(c.get("path", ""))],
          str([c.get("path") for c in calls]))

    # A checkout older than the green baseline: every commit in it was in the tree that went green.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "30609394288": {"workflow_id": 77},
        "runs": {"workflow_runs": [{"id": 999, "head_sha": "b9a8e27ffff"}]},
        "b9a8e27ffff...045eebf": {"status": "behind", "total_commits": 0},
    }
    code, out, calls = run_poster(mod, FACTS_RED, SLACK_MENTION_CULPRITS="true",
                                  GITHUB_TOKEN="ghs-fake-not-a-real-token", GITHUB_SHA="045eebf",
                                  GITHUB_REF_NAME="main", GITHUB_API_URL=mod.SLACK_API)
    summary_text = posts(calls)[0]["payload"]["blocks"][0]["text"]["text"]
    threaded = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P42 a checkout behind the baseline renders no range",
          "Since the last green nightly" not in summary_text, summary_text)
    check("P42 and reports the files as unchanged",
          "unchanged since the last green nightly" in threaded, threaded)
    check("P42 and blames nobody", "last touched by" not in threaded, threaded)
    check("P42 and looks nobody up even with mentions on",
          not [c for c in calls if c["method"] == "users.lookupByEmail"], str([c["method"] for c in calls]))

    # Rewritten history stays unknown, so the fallback keeps naming the commit rather than clearing everyone.
    FakeSlack.behaviour = {
        "chat.postMessage": ok_parent,
        "30609394288": {"workflow_id": 77},
        "runs": {"workflow_runs": [{"id": 999, "head_sha": "b9a8e27ffff"}]},
        "b9a8e27ffff...045eebf": {"status": "diverged", "total_commits": 7},
    }
    code, out, calls = run_poster(mod, FACTS_RED, GITHUB_TOKEN="ghs-fake-not-a-real-token",
                                  GITHUB_SHA="045eebf", GITHUB_REF_NAME="main",
                                  GITHUB_API_URL=mod.SLACK_API)
    threaded = posts(calls)[1]["payload"]["blocks"][0]["text"]["text"]
    check("P42 rewritten history still names the commit", "last touched by" in threaded, threaded)
    check("P42 and claims nothing about the last green run",
          "unchanged since the last green nightly" not in threaded, threaded)


# --------------------------------------------------------------------------------------------------
# Workflows
# --------------------------------------------------------------------------------------------------
#
# The live workflows hold a client credential, and three properties are what keep it where it belongs. Each
# is currently true of how the files are written, which is not the same as being enforced: any of the three
# could be undone by an edit that looks reasonable in isolation, and the consequence would not show up in a
# test run or a review diff as anything alarming.
#
#   * no pull_request trigger, so a fork's pull request cannot reach the secrets
#   * the credential reaches one step, and not the step that runs a third-party action or the tests
#   * nothing passes it with -P, which would put it in a command line
#
# Read textually and on purpose. This harness is standard library only, so there is no YAML parser to lean
# on, and a hand-rolled one would be a second thing to trust. What these checks need is coarse: which lines
# a step spans, and which of them mention a secret.

# Overridable for the same reason the two scripts above are: the sabotage harness rewrites copies and points
# this at them, so a mutation is never applied to the working tree.
WORKFLOWS = Path(os.environ.get("NIGHTLY_WORKFLOWS", SDK / ".github/workflows"))

LIVE_WORKFLOWS = ("live-flows.yml", "live-qa.yml", "live-sandbox.yml")


def workflow_text(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


def trigger_keys(text: str) -> list[str]:
    """The triggers named by `on:`, in any of the three forms YAML allows for it.

    `on: push` and `on: [push, workflow_dispatch]` are the same document as the block form, and reading only
    the block form returned nothing for either. That failed closed on the guard below, but on a reformat that
    changed no semantics, which is a false failure rather than a finding.
    """
    keys: list[str] = []
    on_indent: int | None = None
    child_indent: int | None = None

    for line in text.splitlines():
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())

        if on_indent is None:
            if not stripped.startswith("on:"):
                continue
            on_indent = indent
            inline = stripped[len("on:"):].strip()
            if inline.startswith("["):
                return [key.strip() for key in inline.strip("[]").split(",") if key.strip()]
            if inline and not inline.startswith("#"):
                return [inline]
            continue

        if stripped and not stripped.startswith("#"):
            if indent <= on_indent:
                break
            # Direct children only. `workflow_call` carries `inputs:` and a key per input, and taking those
            # as triggers would make the list say things the document does not.
            if child_indent is None:
                child_indent = indent
            if indent == child_indent and stripped.endswith(":"):
                keys.append(stripped.rstrip(":"))
    return keys


def steps_of(text: str) -> list[str]:
    """Each step as one block of text: the list items under any `steps:`, at whatever depth it sits.

    Measured against a fixed indentation before this: reindenting the file emptied the list, which failed
    closed on the guard below but failed on a reformat that changed no semantics. The `steps:` line supplies
    the depth, an item is a `- ` deeper than it, and a line back at or above that depth ends the block.
    """
    blocks: list[str] = []
    current: list[str] = []
    steps_indent: int | None = None

    for line in text.splitlines():
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())

        if stripped == "steps:":
            if current:
                blocks.append("\n".join(current))
                current = []
            steps_indent = indent
            continue
        if steps_indent is None:
            continue

        # Out of the block: a key at or above the depth `steps:` sits at. A blank line or a comment carries
        # no depth of its own and belongs to whatever is open.
        if stripped and not stripped.startswith("#") and indent <= steps_indent:
            if current:
                blocks.append("\n".join(current))
                current = []
            steps_indent = None
            continue

        if stripped.startswith("- ") and indent > steps_indent:
            if current:
                blocks.append("\n".join(current))
            current = [line]
        elif current:
            current.append(line)

    if current:
        blocks.append("\n".join(current))
    return blocks


def run_block_lines(text: str) -> list[str]:
    """The body of every `run:` step, both the block form and the one-liner.

    The one-liner counts because `run: echo ${{ ... }}` is the same substitution written shorter, and a check
    reading only the block form would pass a file that had moved the expression onto the key's own line.
    """
    lines: list[str] = []
    run_indent: int | None = None

    for line in text.splitlines():
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())

        if stripped in ("run: |", "run: >"):
            run_indent = indent
            continue
        if stripped.startswith("run: "):
            lines.append(stripped)
            run_indent = None
            continue
        if run_indent is None:
            continue
        if not stripped:
            continue
        if indent <= run_indent:
            run_indent = None
            continue
        lines.append(stripped)

    return lines


def emulator_script_lines(text: str) -> list[str]:
    """The command lines of every `script: |` block, which is what the emulator action is handed.

    The block ends where the indentation returns to the key's own depth or shallower, so a following key
    cannot be read as a command. Comments and blanks are dropped, which is what the action does with them.
    """
    lines: list[str] = []
    script_indent: int | None = None

    for line in text.splitlines():
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())

        if stripped == "script: |":
            script_indent = indent
            continue
        if script_indent is None:
            continue
        if not stripped or stripped.startswith("#"):
            continue
        if indent <= script_indent:
            script_indent = None
            continue
        lines.append(stripped)

    return lines


LIVE_POSTER = Path(os.environ.get("NIGHTLY_LIVE_POSTER", SDK / ".github/scripts/live_slack.py"))


def load_live_poster():
    """`live_slack.py`, which imports `nightly_slack`, so its directory has to be importable."""
    sys.path.insert(0, str(LIVE_POSTER.parent))
    try:
        spec = importlib.util.spec_from_file_location("live_slack", LIVE_POSTER)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod
    finally:
        sys.path.pop(0)


def test_live_summary(mod):
    """`summarize` is the boundary between a failure message and a chat channel.

    The live flows submit a card and a bank account, and the assertion text around them is not written with a
    channel in mind. What stops a submitted value reaching one is this function emitting only what it matched,
    so widening the pattern by accident is the failure worth catching, and it is invisible in a diff.
    """
    # The values a failure message can carry, in one string, with a reportable code beside them so the
    # summary is not empty for the wrong reason.
    leaky = ('junit.framework.ComparisonFailure: expected:<Approved> but was:<Declined for 4111111111111111 '
             'held by QA Tester, acct 1234567890, token abc-secret-xyz> code=PAYMENT_DECLINED')
    summary = mod.summarize(leaky)
    check("L1 the classification survives", "ComparisonFailure" in summary, summary)
    check("L1 the wire code survives", "code=PAYMENT_DECLINED" in summary, summary)
    for secret in ("4111111111111111", "QA Tester", "1234567890", "abc-secret-xyz"):
        check(f"L1 {secret!r} does not reach the channel", secret not in summary, summary)

    # The runner writes this one capitalised, and the pattern carried it in lower case once.
    started = "ComposeTimeoutException: No compose hierarchies found in the app"
    check("L2 the capitalised phrase is reportable", "No compose hierarchies" in mod.summarize(started),
          mod.summarize(started))

    # An identifier is matched exactly, so a message writing CODE= is not reportable and cannot carry the
    # text beside it through.
    wrong_case = "CODE=leaked 4111111111111111"
    check("L3 a wrong-case identifier is not reportable", "4111111111111111" not in mod.summarize(wrong_case),
          mod.summarize(wrong_case))
    check("L3 and says so rather than going empty", "no reportable detail" in mod.summarize(wrong_case),
          mod.summarize(wrong_case))

    check("L4 a message with nothing reportable points at the artifact",
          "read the results artifact" in mod.summarize("Payment for QA Tester failed at step 3"))
    check("L4 an empty message does the same", "read the results artifact" in mod.summarize(""))

    # Deduplicated and in order, so a message repeating a code does not repeat it in the channel.
    check("L5 repeats are collapsed, order kept",
          mod.summarize("code=X httpStatus=500 code=X") == "code=X httpStatus=500",
          mod.summarize("code=X httpStatus=500 code=X"))

    # Bounded, because a failure message is not: the thread post is capped separately and this is the value
    # that goes into it.
    check("L6 the summary is bounded", len(mod.summarize("code=A " * 400)) <= 300,
          str(len(mod.summarize("code=A " * 400))))

    # The thread post's own bound. A list cut through the middle of a line reads as the whole list, which is
    # the failure mode: the reader cannot tell a report of three failures from a report of thirty.
    many = [mod.Flow("PayInLiveFlowsInstrumentedTest", f"flow{index}", "code=A " * 40) for index in range(60)]
    body = mod.thread_body(many)
    check("L7 the thread post is bounded", len(body) <= mod.SLACK_TEXT_LIMIT, str(len(body)))
    check("L7 it says how many it dropped", "further failure(s) not listed here" in body, body[-120:])
    check("L7 and every line it kept is whole",
          all(line.startswith(("•", "_")) for line in body.splitlines()),
          body.splitlines()[-1])

    few = [mod.Flow("QaWalkthroughTest", "one", "code=B")]
    check("L7 a list that fits carries no notice", "not listed here" not in mod.thread_body(few),
          mod.thread_body(few))


def test_workflows():
    for name in LIVE_WORKFLOWS:
        text = workflow_text(name)
        keys = trigger_keys(text)
        check(f"W1 {name} has triggers at all", bool(keys), f"{keys}")
        # pull_request_target is the dangerous one and the easy one to add by mistake: it runs against a
        # fork's head with the base repository's secrets available.
        check(f"W1 {name} cannot be triggered by a pull request",
              not any(key.startswith("pull_request") for key in keys), f"{keys}")
        # Again without reading the structure, because the check above cannot see a trigger written in a form
        # it does not model: a file mixing the block and shorthand styles passed every check while carrying
        # one. These files have no other use for the word.
        check(f"W1 {name} does not mention a pull request trigger anywhere",
              "pull_request" not in text,
              next((line.strip() for line in text.splitlines() if "pull_request" in line), ""))

    reusable = workflow_text("live-flows.yml")
    blocks = steps_of(reusable)
    check("W2 the reusable workflow has steps", len(blocks) > 3, f"{len(blocks)}")

    # Both halves, because the credential is the pair: a regression that moved only the client id would
    # otherwise pass, and an id alone is enough to matter beside a secret that leaks another way.
    for half in ("client-secret", "client-id"):
        holding = [b for b in blocks if f"secrets.{half}" in b]
        check(f"W2 exactly one step is given the {half}", len(holding) == 1,
              f"{len(holding)} steps: " + " | ".join(b.splitlines()[0].strip() for b in holding))
        if holding:
            check(f"W2 and the {half} goes to the step that runs the token server", "server.mjs" in holding[0],
                  holding[0].splitlines()[0].strip())

    emulator = [b for b in blocks if "android-emulator-runner" in b]
    check("W3 the emulator steps are found", len(emulator) == 2, f"{len(emulator)}")
    check("W3 no emulator step is given the client secret",
          not any("secrets.client-secret" in b or "secrets.client-id" in b for b in emulator))
    # The address is what replaced the credential, so its absence would mean the tests fall back to whatever
    # the build compiled in rather than reaching the server this workflow started.
    #
    # Matched as a whole key. A substring test read PAYABLI_LIVETEST_TOKEN_HOST_DISABLED as the key being
    # present, which the sabotage pass caught: renaming a variable is exactly how this would be lost.
    def sets_token_host(block: str) -> bool:
        return any(line.strip().startswith("PAYABLI_LIVETEST_TOKEN_HOST:") for line in block.splitlines())

    check("W3 the live step is given the token host", any(sets_token_host(b) for b in emulator))

    check("W4 no live setting is passed as a gradle argument",
          "-Ppayabli.liveTest." not in reusable,
          next((line.strip() for line in reusable.splitlines() if "-Ppayabli.liveTest." in line), ""))

    # The emulator action splits its `script` input on newlines and runs each resulting line as its own
    # `sh -c`, so a trailing backslash joins nothing: the command runs without its arguments and the
    # arguments run as a command. Invisible in review, and these workflows have no pull request trigger, so
    # nothing else would find it before a scheduled run did.
    for line in emulator_script_lines(reusable):
        check("W5 no line of the emulator script continues onto the next",
              not line.endswith("\\"), line)
        check("W5 every line of the emulator script is a command", line.startswith("./"), line)

    # An expression is substituted into a script before any of it runs, so a value that closes its own quote
    # runs as a command with this job's secrets in the environment. Values reach a script as variables.
    for name in LIVE_WORKFLOWS:
        offending = [line.strip() for line in run_block_lines(workflow_text(name)) if "${{" in line]
        check(f"W6 {name} interpolates no expression inside a run script",
              not offending, " | ".join(offending))


HALVES = ("both", "collector", "poster", "workflows", "live")


def main():
    # A typo selects no half at all, and a run of nothing prints "0 passed, 0 failed" and exits 0. That is
    # the vacuous green this whole harness exists to make impossible, so it is rejected rather than run.
    if ONLY not in HALVES:
        print(f"NIGHTLY_ONLY={ONLY!r} is not one of {', '.join(HALVES)}")
        return 2

    server = ThreadingHTTPServer(("127.0.0.1", 0), FakeSlack)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}/api"
    try:
        if ONLY in ("both", "collector"):
            test_collector()
        if ONLY in ("both", "poster"):
            test_poster(load_poster(base))
        if ONLY in ("both", "workflows"):
            test_workflows()
        if ONLY in ("both", "live"):
            test_live_summary(load_live_poster())
    finally:
        server.shutdown()

    print(f"\n{len(PASS)} passed, {len(FAIL)} failed")
    for name in FAIL:
        print(f"  FAILED: {name}")
    # The same guard one level down, for any other route to running nothing: a half that raises before its
    # first check, an accidental early return. Zero checks is never a pass.
    if not PASS and not FAIL:
        print("  FAILED: no checks ran at all")
        return 1
    return 1 if FAIL else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        shutil.rmtree(SCRATCH, ignore_errors=True)
