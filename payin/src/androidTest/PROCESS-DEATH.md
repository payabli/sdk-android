# Process death and a submission in flight

A checklist, walked on each attached device. **The expected answer is that the state is gone.** What the walk
confirms is that the loss is clean and that nothing was written to disk to make it otherwise.

A submission survives a rotation and a trip to the background, and `PayInSubmissionRetentionInstrumentedTest`
asserts both. It does not survive the process ending, and neither does the key that would make a retry safe:
`PayInSubmissionState.Failed.retryKey` is part of the state this path loses, so an app coming back from a kill
holds no record of the attempt at all.

**So a payment interrupted by process death is not recoverable from inside the app.** Whether the service took
it is answered by reconciling outside it. A host that needs to survive this sets `idempotencyKey` on the
transaction options itself and persists it before submitting, which is what that field is for; the key the
flow mints when none is given lives only as long as the flow does.

Persisting the state instead would mean writing a payment's progress where the system can put it on disk, so
the loss is the design rather than a gap in it.

## Before starting

The sample app is the only build that draws the form, so the walk is done there.

```bash
./gradlew :example:installDebug
adb shell am start -n com.payabli.example.app/.MainActivity
```

`ANDROID_SERIAL=<serial>` in front of each command when more than one device is attached; `adb devices`
lists them.

## The two checks

Each starts the same way: open a payment screen, fill the card fields, submit, and while the button still
reads as busy do the thing in the second column.

| Check | What to do | Expected |
|---|---|---|
| Closed and reopened | Swipe the app away from the recents list, then launch it again | The form opens empty and idle. No result, no error, no spinner left running. |
| Reclaimed in the background | Background the app, then `adb shell am kill com.payabli.example.app`, then return to it | The same: empty and idle, and no crash on the way back. |

**Don't keep activities** answers a different question. It destroys the Activity and keeps the process, which
is the case the instrumented tier covers; `am kill` ends the process, which is the case that tier cannot
reach.

```bash
# Developer options, if the Activity-only case is wanted on its own
adb shell settings put global always_finish_activities 1
adb shell settings put global always_finish_activities 0
```

## What would be a failure

- A spinner or a disabled form on the way back in, which would mean a state was restored without the
  coroutine that was going to complete it.
- A result or an error from the submission that was interrupted, which would mean the outcome was written
  somewhere it outlived the process.
- A crash reading a restored value.

## Devices walked

Record the model and API level of each device the walk was done on in the pull request, as the attestation
work does. Process death is a platform behavior, so an emulator answers this question as well as a phone and
an emulator run counts.
