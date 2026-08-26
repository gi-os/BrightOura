## BrightOura v0.21 — pairing goes through the shell, because nothing else here can do it

Five releases spent on the app side of this, and the app side was never where it could be fixed.
Every route the platform offers ends at the same activity:

- **Awake**, the request opens `com.android.settings/.bluetooth.BluetoothPairingDialog`. LightOS's
  fragment builds a **null** dialog for the consent variant and dies in `DialogFragment.prepareDialog`.
- **Asleep**, the request is posted as a notification instead — v0.20 got that far, and the
  notification really is posted, with a "Pair & connect" button, and this app's listener really does
  press it. That button fires `ACTION_PAIRING_DIALOG`, whose only job is to start the same activity.
  It crashed the pairing service too.
- **The companion-association route** (v0.16–v0.19) never flipped `canBondWithoutDialog`, with the
  exact address, a plain association and under a second between approval and the bond.

Three routes, one dead end. There is no fourth.

**So: `setPairingConfirmation`, which answers a request with no UI at all.** Its permission is
`BLUETOOTH_PRIVILEGED` — `signature|privileged`, unreachable for a sideloaded app and always will
be. But `com.android.shell` holds it granted, along with `BLUETOOTH_STACK`, and BrightControl has
held an adb shell since its first release.

**Pair through BrightControl** hands over one line — `confirm pairing <MAC>` — and BrightControl
(v3.43 or newer) rebuilds the command itself:

```
sh -c 'CLASSPATH=<this app's own APK> app_process / com.gios.brightoura.helper.Confirm <MAC> 24000'
```

The class is new here, and it is the only new code in this release: it asks for the bond, then
answers the request it raised, in one loop as one uid. Confirming from one process while bonding
from another is a race across a consent screen, and there was nothing to be gained by keeping them
apart — a bond belongs to the phone, not to whoever asked for it. It clears a half-made bond first,
because one of those poisons every attempt after it, and it reports each state change so a failure
says which step failed.

Nothing is dropped in shared storage and no dex ships: the helper lives inside this APK, which is
world-readable, and `app_process` is pointed straight at it. Nothing is left behind afterwards.

The old routes are still on the screen, one row down, honestly labelled — they are the ones that
would work on a phone whose pairing dialog is not broken.
