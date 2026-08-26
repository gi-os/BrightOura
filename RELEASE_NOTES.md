## BrightOura v0.30 — why the bond collapsed, in words

```
setPairingConfirmation true
state NONE
RESULT refused after confirming
```

The confirmation went through and the bond then fell apart. That was an honest answer to the wrong
question: *why* it fell apart is carried on the bond-state broadcast as `EXTRA_REASON`, a number this
helper had never asked for. The state read back afterwards is a bare NONE, which is why "refused
after confirming" was the end of the story instead of the start of one.

The reasons point in completely different directions:

| reason | what it means |
|---|---|
| authentication failed | the keys did not match |
| the ring rejected it | it does not want to pair with this phone |
| the ring went away | out of range, asleep, or busy with another phone |
| authentication timed out | nobody answered in time |
| **too many failed attempts** | **the stack is refusing for now** |

Only the last one has a remedy, and after a dozen-plus attempts tonight it is the one I would bet on.
When it appears, the helper says what to do rather than leaving it to be guessed:

```
the stack is refusing because too many pairings have failed recently. Switch
Bluetooth off and on — that clears the count — and leave it a minute before
trying again.
```

**And the transcript no longer ends on "Killed".** Every one so far has, which reads like a crash and
was only this process declining to exit while a framework thread was still up. It says `done` and
exits, so the last line of a transcript is the answer rather than an alarm.
