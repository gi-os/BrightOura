## BrightOura v0.27 — the looper was prepared and never run

Real progress first: v0.26's fix worked exactly as intended.

```
service manager installed
manager: ok
manager.getAdapter(): ok
adapter state 12 enabled=true
```

An adapter, in a process that had never had one. And then:

```
removeBond false
createBond false
state NONE
```

**False, from an adapter that reports itself enabled.** That is the signature of a
`BluetoothAdapter` whose internal binder is still null — and that binder does not arrive with the
adapter. `IBluetoothManager` hands it over **asynchronously**, through a callback posted to this
process's main looper.

The looper was prepared and never run. So the callback sat in a queue nobody was reading, the
service stayed null, and every operation that needs it returned false — politely, as usual, without
throwing.

**The main thread now does its job.** Framework setup happens there (it has to: `systemMain()`
prepares the main looper on whichever thread calls it), the pairing work moves to a worker thread,
and the main thread loops — dispatching the callback that carries the service — until the worker is
done.

**And the service is proven before anything is asked of it.** `getBondedDevices()` returns null,
not an empty set, while the binder is missing, which makes it the cheapest honest test available:

```
bluetooth service reachable
device …  state NONE
createBond true
```

If it says **NOT reachable** the helper stops there and says so, rather than reporting three
separate false returns from three operations that never had a chance.
