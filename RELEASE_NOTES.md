## BrightOura v0.26 — the process had nothing pointing at the Bluetooth binder

Three routes to the adapter, all returning **null without throwing**, on a phone where the
`bluetooth_manager` binder was present and `BluetoothManager` itself constructed fine. That
combination is the whole diagnosis:

```
bluetooth_manager binder: present
manager: ok
manager.getAdapter(): null
getDefaultAdapter(): null
createAdapter(): null
```

Since Android 13 the Bluetooth stack is a mainline module, and `BluetoothAdapter.createAdapter()`
does not look the binder up itself — it asks `BluetoothFrameworkInitializer.getBluetoothServiceManager()`
for it. **That object is installed by `ActivityThread` while an application starts.** The helper runs
under `app_process` as a plain main class, which is not an application, so the setter is never
called, the manager is null, and every route politely reports nothing. The binder was reachable the
entire time; there was simply nothing pointing at it.

So the helper now installs it, exactly as application startup would: construct the service manager,
hand it to the initializer, then try the three routes again. Being installed twice counts as success
— the framework throws `IllegalStateException` for a second call, which means somebody else got there
first, which is the state we wanted anyway.

Both plausible package names are tried, the constructor is taken as declared because it is not public
API, and every step says what happened — including "no BluetoothServiceManager class on this build",
which would mean this route is closed and the next one is the raw `IBluetooth` binder.

Also printed now: whether a `bluetooth` service is registered alongside `bluetooth_manager`, because
that is the binder the fallback would use.
