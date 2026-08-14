# Tests for the nightly reporter

`../nightly_report.py` decides the nightly verdict and gates the run; `../nightly_slack.py` is the only
thing that reports a failure. Between them they are the reason anyone finds out a night went red, so they
are covered here rather than trusted.

Two entry points and no setup. The requirements are `python3` and `git`: the collector half shells out to
`git` to build the synthetic repository it runs against, so it fails without one on `PATH`. No third-party
Python package is involved at all, which is deliberate: it matches the repository's posture on third-party
code and means these tests add no supply-chain surface to a security SDK.

```bash
python3 .github/scripts/tests/verify.py     # 405 checks, about 4 seconds
python3 .github/scripts/tests/sabotage.py   # 46 deliberate breaks, about a minute
```

Both run in CI through `.github/workflows/scripts.yml`, but only when `.github/scripts/**` or that
workflow file changes. The workflow is in its own `paths` filter so a change to it tests itself.

## verify.py

The collector runs as a **subprocess inside a synthetic git repository**, so repository-root resolution,
globbing and `git log` are real rather than mocked. The poster runs **in-process against a fake Slack on
loopback**, so the threading contract is exercised rather than asserted about.

Four environment variables, all optional:

| | |
|---|---|
| `NIGHTLY_ONLY` | `collector`, `poster` or `both` (default). `sabotage.py` runs one half at a time |
| `NIGHTLY_COLLECTOR` / `NIGHTLY_POSTER` | run the checks against some other copy of a script |
| `NIGHTLY_SDK` | repository root, if the default is wrong |

The default resolves from the file's own location, so a fresh clone needs nothing set.

## sabotage.py

Breaks each claimed behaviour one at a time and confirms a check goes red. A test suite that passes proves
only that it ran; this is what shows the checks are load-bearing.

**It rewrites copies in a scratch directory, never the files in the working tree.** The file in the
repository is opened for reading only: it supplies every anchor and it is what each copy is restored from.
A run that dies halfway through therefore leaves nothing behind to repair, which is what makes this safe to
run in CI and safe to interrupt locally.

Three safeguards, because a sabotage harness that lies is worse than none: an anchor must match **exactly
once**, the mutated copy must still **compile**, and every copy is **restored** pass or fail.

When an edit to either script moves an anchor, the find-and-replace matches nothing, so the reporter is
never actually broken and every check correctly passes. The run reports `INVALID` and names the anchor.
Re-point it in the same change. That is the safeguard working, and it has caught a detached anchor five
times.

## The four disciplines

Each of these exists because its absence produced a false pass. Keep them when adding a check.

**An assertion must be able to fail, never raise.** A raising assertion kills the run before it prints a
verdict, and zero `FAIL` lines is indistinguishable from zero failures — worst in exactly the cases this
harness exists for, comparing against an older revision and sabotage runs that remove the thing being
indexed. The structural fix is the sentinel accessors in `verify.py`: a missing call yields a falsy object
whose every lookup returns another sentinel. Probe with `getattr` rather than reading an attribute an older
revision does not have.

**A sabotage anchor must match exactly once.** Zero matches breaks nothing, so every check passes and the
run would report `1 breaks, 0 caught, 1 missed`: measured, an unguarded miss reads as a hole in the suite
rather than as a stale anchor, which sends the reader to the wrong file. Two matches breaks two sites at
once, so which one a failing check caught is unknowable.

**Red before, green after, every time.** A check written after the fix proves nothing. Point the harness at
the previous revision and confirm the new check fails there:

```bash
git show HEAD~1:.github/scripts/nightly_slack.py > /tmp/poster_before.py
NIGHTLY_POSTER=/tmp/poster_before.py python3 .github/scripts/tests/verify.py
```

Record how many checks fail on the previous commit. That number is the evidence, and it is what turns
"the reviewer claims X" into "X reproduces on the previous commit".

**An assertion can pass vacuously.** One filter anchored at the start of a URL path matched nothing, because
the API base carries a prefix, so the check passed while testing nothing; it was caught only because its red
counterpart failed. Prefer a pair: the guard, and the case that proves the guard is not vacuous.
