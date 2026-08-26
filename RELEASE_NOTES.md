## BrightOura v0.24 — "bluetooth is off" was the helper being unable to see, not the phone being off

The pairing helper stopped before it started, on a phone with Bluetooth plainly on:

```
FAILED bluetooth is off
```

That was my own guard, and it could not tell the difference between two very different things. The
helper runs under `app_process` — a bare VM with no ActivityThread behind it — and in that process
the adapter cannot always reach the Bluetooth service. When it cannot, `isEnabled()` returns
**false** rather than throwing. So a defensive check meant to catch "you forgot to turn Bluetooth on"
became the only thing standing between a working phone and a bond.

**It no longer refuses to continue.** If Bluetooth genuinely is off, `createBond` and
`setPairingConfirmation` fail on their own and give reasons that are actually true. A check that
cannot distinguish "off" from "cannot see" is worse than no check.

**And it prints a second opinion instead**, from a source that does not depend on this process
having a working adapter:

```
adapter state 10 enabled=false setting bluetooth_on=1
createBond true
state BONDING
```

`bluetooth_on=1` with an adapter reporting disabled is the exact signature of the bug — and now it is
on screen rather than hidden behind a refusal. Two routes to the adapter are tried as well, the
manager and the static default, because in a bare process neither is reliable alone.
