## BrightOura v0.23 — the pairing helper says what it is waiting for

The helper that answers a Bluetooth pairing request from the shell spends most of its life doing
nothing on purpose: the platform raises the request several seconds after the bond starts, and the
helper has to be sitting there when it does. That is up to twenty-four seconds of silence, and
BrightControl now reads a command's output live — so twenty-four seconds of silence is a transcript
that looks like a hung command. Which is exactly how the last one was reported.

So it counts down while it waits:

```
createBond true
state BONDING
waiting… 21s left, state BONDING
waiting… 18s left, state BONDING
setPairingConfirmation true
RESULT bonded
```

Every three seconds, and only when nothing else has happened — a state change resets the clock,
because a line that says something real is worth more than a line that says it is still here.
