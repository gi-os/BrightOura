## BrightOura v0.17 — the pairing has to be started by us, not for us

**v0.16 got the address right and still met the dialog.** The association named
`4B:C8:F0:B4:09:9B`, approved at 23:23:13; the bond ran on the same address at 23:23:13.642, half a
second later; and the phone still logged `canBondWithoutDialog=false` and raised the consent dialog
that takes Settings down with it. Right address, well inside the ten minutes, and it made no
difference.

**Because there is a third condition, and it is about who asked.** The platform decides by looking
up the caller recorded against the address:

```java
boolean createBond(device, transport, ..., callingPackage) {
    if (deviceProp != null && deviceProp.getBondState() != BOND_NONE) {
        return deviceProp.getBondState() == BOND_BONDING;   // early return
    }                                                       // caller never recorded
    mBondAttemptCallerInfo.put(device.getAddress(), new CallerInfo(callingPackage, user));
}
```

Whoever bonds **first** owns that record. Everyone after gets the early return and changes nothing.

**The watch profile was starting the bond.** Asking for `DEVICE_PROFILE_WATCH` was meant to hand the
pairing to the platform, which sounded like the right move on a phone whose pairing dialog is
broken. It was the thing breaking it: associating with that profile bonds the device from the
system, our `createBond` arrived milliseconds later into a state that was no longer BOND_NONE, and
the caller on file was a package with no association. The check failed for the one reason nobody
was looking at.

So the association is plain now. It records the approval and bonds nothing, which leaves the first
`createBond` to this app — the app that holds the association, on the address the picker just
returned. The consent dialog has no reason to be raised, so it is not, so Settings never runs.

**And a bond already in flight gets taken back rather than joined.** Anything other than BOND_NONE
is cleared first, and the code now *waits for the state to actually read NONE* instead of guessing
600 milliseconds at it. Losing that race used to cost the entire attempt, silently.

**A consent request arriving is now reported as the diagnosis it is.** If that dialog is ever
requested again, the screen says what it means — the pairing was not credited to this app — instead
of a minute of "Pairing…" ending in a failure with no cause attached.
