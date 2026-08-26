## BrightOura v0.25 — a third route to the Bluetooth adapter, and a diagnosis when there is none

```
FAILED no bluetooth adapter in this process
```

Both of the obvious routes returned **null** — not an exception, null, which is the least helpful
thing a framework can say. The cause is structural: since Android 13 the Bluetooth stack lives in its
own mainline module, and the wrapper that answers `getSystemService(BLUETOOTH_SERVICE)` is registered
during *application* initialisation. `app_process` running a plain main class is not an application,
so that registration may never have happened at all.

**The third route is the one `BluetoothManager` uses on the inside:**
`BluetoothAdapter.createAdapter(AttributionSource)` — straight to the `bluetooth_manager` binder with
none of the module plumbing above it. Hidden, so reflected; the attribution source is built for this
process's real uid and for `com.android.shell`, which is the package whose privileges the far side
checks anyway.

**And every step now says whether it worked**, because "no adapter" does not distinguish three quite
different failures:

```
bluetooth_manager binder: present
manager: ok
manager.getAdapter(): null
getDefaultAdapter(): null
createAdapter(): ok
adapter state 12 enabled=true setting bluetooth_on=1
createBond true
```

If the binder itself comes back `missing`, no route can work and the answer is not another route —
it is that this process cannot reach Bluetooth at all, and the helper has to be launched some other
way. That is worth knowing in one line rather than three evenings.
