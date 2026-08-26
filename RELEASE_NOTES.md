## BrightOura v0.29 — the bond was in flight when the clock killed it

The furthest this has ever got, from tonight's transcript:

```
createBond true
state BONDING
setPairingConfirmation true
RESULT gave up in state BONDING (request was answered)
Killed
```

Every hard part worked. `createBond` went through — the shell-attributed adapter from v0.28 was the
missing piece — the platform raised its consent request, and **the shell answered it**. The bond was
still completing when the budget expired, and the budget then killed the only attempt that has ever
reached this point.

That budget was 24 seconds, chosen because a pairing *request* stands for about thirty. Which is true
of the request and says nothing about the **bond**, which is what happens after it is answered.

**Fifty-five seconds now, and BONDING earns more.** Reaching the deadline mid-bond is the one case
where stopping is worse than waiting: the request has been accepted and the two ends are exchanging
keys. So the state buys another forty-five seconds, once — a deadline that fires through BONDING is a
deadline interrupting the thing it was waiting for.

**And giving up no longer implies the bond is dead.** Nothing here cancels it, so it says so:

```
the bond is still in progress and is not cancelled — it may complete on its own.
Check the ring before starting over.
```

Worth taking literally. If the last attempt reached BONDING, the phone may already be paired with the
ring — check before starting over, because starting over clears a half-made bond that might have
finished.
