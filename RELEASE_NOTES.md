## BrightOura v0.28 — attributed to the shell, which is what `createBond` was checking

light-reports#121 is the full transcript, and everything in it reads healthy except one line:

```
service manager installed
manager.getAdapter(): ok
bluetooth service reachable
device 56:46:70:0D:35:05 state NONE
createBond false
```

`AdapterService.createBond` checks `isPackageNameAccurate(callingPackage, callingUid)` and returns
**false** when they disagree — silently, without throwing, with no line anywhere saying why. An
adapter from `getSystemService` carries the *base* context's op package, `"android"`, while this
process runs as uid 2000. A package that does not belong to the calling uid is a refusal the stack is
entitled to make, and that is the entire failure.

`BluetoothAdapter.createAdapter(AttributionSource)` is the one route that sets this, and it was the
*last* one tried — because until v0.26 it returned null, the module's service manager being missing.
With that installed it works, so it goes first now, built for uid 2000 and `com.android.shell`: true
of both halves, so the check passes, and the request is attributed to the shell whose privileges are
being borrowed anyway.

The others are kept as fallbacks, and they are real ones — an adapter that cannot bond can still read
state, and reading state is how the next failure gets diagnosed.

**And the attribution is printed**, because it is now the crux:

```
attributed to com.android.shell (uid 2000)
```

One line, next to the state, so a refusal of this kind is never again a silent false from an otherwise
healthy transcript.
