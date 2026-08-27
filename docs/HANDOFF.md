# Handoff: everything learned, and where it stopped

Written 2026-08-27, at v0.30.40. BrightOura is parked, not abandoned. This is the whole
record — what the ring does, what this app does, the wall it is standing at, and what a
person picking it up would have to do. It is written for somebody who has never
seen the repo and does not have the ring.

Nothing here is aspiration. Where something is unverified it says so, because the
expensive mistake in this project has been treating a thing that should work as a thing
that does.

---

## 1. In one paragraph

The Oura ring's Bluetooth protocol is known and this app implements it: framing, the
challenge-response, the history drain, the decoders. None of that is the problem. The
problem is one layer below all of it — the ring will not talk about anything interesting
over an unencrypted link, an encrypted link needs a Bluetooth bond, and **LightOS cannot
complete a BLE consent bond.** The screen that would accept the pairing crashes. Nine
routes around that screen were tried. The last one gets further than any of them and
still ends with the bond collapsing after the confirmation is accepted.

## 2. Status, honestly

| part | state |
|---|---|
| Scan and find the ring | **Works.** Matched by service, name or existing bond, not by advertised service alone |
| Connect over GATT | **Works.** `TRANSPORT_LE` explicitly, MTU raised, service discovery completes |
| Read the GATT table off a real Ring 4 | **Works** — and it is the only Ring 4 dump we know of, see §4 |
| Probe firmware / serial / hardware id | **Unproven.** Auth-free by protocol; no transcript in the repo shows the values coming back. See §7 |
| Bond the ring | **Blocked.** §5 is the whole story |
| Authenticate | Not reachable without a bond |
| Drain history | Not reachable |
| Decode frames | **Written and tested — against hand-built bytes only.** 40 unit tests, zero real frames |
| Scores | Deliberately absent. They are computed by Oura's cloud, not held on the ring |

The single sentence a successor needs: **no byte of measurement data has ever come off a
real ring into this app.** Everything downstream of the bond is a careful implementation
of somebody else's notes, waiting for one link to come up.

## 3. What the ring wants

Ported from [`open_oura`](https://github.com/Th0rgal/open_oura) (MIT), which reversed it
from the official Android app's native libraries and verified it against a Ring 3 Horizon
and a Ring 5, cross-checked against the ringverse Ring 4 notes. Ring 3, 4 and 5 share the
service, the framing and the authentication — generations differ in which event tags they
emit, not in how you talk to them.

**GATT.** Service `98ed0001-a541-11e4-b6a0-0002a5d5c51b`; write `98ed0002`; notify
`98ed0003`.

**Frames.** `tag | length | payload`, little-endian throughout. Extended operations ride
under outer tag `0x2f` with the first payload byte as the real operation. Tag `0x41` and
above is a history event rather than a reply.

**Authentication.** Smaller than its reputation. The ring holds a 16-byte key, offers a
15-byte nonce per connection, and the client returns the nonce encrypted under the key:

```
install key   24 10 <16-byte key>     → 25 01 00      (factory-reset ring only)
get nonce     2f 01 2b                → 2f 10 2c <15-byte nonce>
authenticate  2f 11 2d <16 bytes>     → 2f 02 2e 00   accepted
                                      → 2f 02 2e 01   wrong key
```

`AES/ECB/PKCS5Padding` over the 15-byte nonce is the same operation as
`AES-128-ECB(key, nonce ‖ 0x01)` — PKCS#5 pads 15 bytes with exactly one `0x01`. Both
descriptions circulate; `AuthTest` asserts they agree so a refactor cannot quietly pick
one.

**Session scope.** Authentication is redone on every connection. Firmware, serial and
hardware id answer cold. Battery, history, features and the live streams answer
`2f 02 2f 01` until authenticated — that exact frame is the ring saying "authenticate
first", not an error.

**Two things that bite.** The ring's MTU is 203 and history frames use it; the default 23
fragments them and this code does not reassemble, so a refused MTU request is a stop
rather than a warning. And the ring advertises with a rotating private address, so an
address from a minute ago fails as GATT status 133, which reads exactly like "the ring is
not there."

## 4. The Ring 4 GATT table

Worth its own section because it contradicts the notes and nobody else appears to have
published it. The protocol notes describe two characteristics. The ring in hand has
**five in the Oura service, plus a second service nobody has written about**:

| characteristic | properties |
|---|---|
| `98ed0002` | write — documented |
| `98ed0003` | notify — documented |
| `98ed0004` | read, write, notify, indicate — **undocumented** |
| `98ed0005` | write, notify — undocumented |
| `98ed0006` | write, notify — undocumented |
| `00060001` (second service) | read and write — undocumented |

`98ed0004` reading *and* writing *and* notifying is what a request/response channel looks
like when it does not need a subscription. `Ring.alternates()` collects these and
`Session.probe()` walks them, dumping whatever comes back into the diagnosis. That walk
has never been read on a bonded link, and it is the cheapest unexplored lead in the repo.

## 5. The wall: a consent bond on LightOS

Read this before writing any code. Every route below was tried, in this order, and the
order matters — each one exists because the one above it failed.

**The mechanism.** Subscribing to the notify characteristic is the first operation on the
link that requires encryption, so the bond request is raised by our own subscribe.
Observed timing from one trace: connected at 749 ms, subscribe at 1662 ms, pairing request
at 2507 ms. The request arrives as `PAIRING_VARIANT_CONSENT` (variant 3) — a plain yes/no
with no PIN.

**Why nothing can say yes.** Android delivers a pairing request as a *notification with a
full-screen intent* and expects the launcher to raise the dialog behind it. LightOS does
not raise it: the phone chimes, the request expires in under a minute, and the bond fails
with every layer working as designed. Behind that notification is
`com.android.settings/.bluetooth.BluetoothPairingDialog`, and on this phone that fragment
builds a **null** dialog and dies with a `NullPointerException` in
`DialogFragment.prepareDialog`. The notification's own "Pair & connect" action fires
`ACTION_PAIRING_DIALOG`, whose only job is to start that same activity, so it takes the
pairing service down with it.

| # | route | outcome |
|---|---|---|
| 1 | Wait for the user to accept the prompt | No prompt is ever drawn. Also wrong in principle: most BLE bonds are Just Works and have no dialog at all |
| 2 | `setPairingConfirmation(true)` from the app | Throws. Needs `BLUETOOTH_PRIVILEGED`, which is `signature\|privileged` and ungrantable by `pm grant` |
| 3 | Start the Settings pairing dialog ourselves | Starts the crashing activity |
| 4 | Send the user to Bluetooth settings | AOSP Settings crashes on the pairing screen. LightOS's own Bluetooth screen (found by searching its activity list — it pairs earbuds) says "pairing" and then stops |
| 5 | Notification listener, press the Pair button | A notification action's `PendingIntent` runs as the system that created it, so this needs no permission — but the button starts route 3's activity |
| 6 | `pm clear com.android.settings` in case a crashing Settings never posts the request | Reversible, tried, did not change the outcome |
| 7 | `CompanionDeviceManager`, watch profile | The picker **is** an activity and does draw here, which is the one dialog this phone will show. The platform skips the consent dialog when the bond is asked for by an app holding a companion association for the same address, approved under ten minutes ago. Two traps: `createBond` records no caller when the state is already not `BOND_NONE`, and the watch profile bonds from the *system* at association time, making our later call a no-op with no caller on file. Made plain-association-only for that reason |
| 8 | Ask with the screen off | `BluetoothPairingRequest` starts the dialog activity only while the phone is interactive; asleep it posts the notification instead. Real, and it is why the listener had never fired — but the notification route is route 5 |
| 9 | **Pair from the shell** | Where it stands. `com.android.shell` holds `BLUETOOTH_PRIVILEGED` and `BLUETOOTH_STACK`, so it is the one process on the phone that can answer a pairing request with no UI at all |

### Route 9, in detail

`app/src/main/java/com/gios/brightoura/helper/Confirm.java` is a plain `main` class inside
this app's own (world-readable) APK, run through BrightControl's adb shell:

```
CLASSPATH=/data/app/…/base.apk app_process / com.gios.brightoura.helper.Confirm <MAC>
```

It bonds *and* answers in one loop as one uid, which removes a race across two processes
and a consent screen. Getting a working Bluetooth adapter inside a bare `app_process` took
four separate discoveries, all of which are load-bearing and none of which throw when
they are missing:

1. `getSystemService(BLUETOOTH_SERVICE)` and `getDefaultAdapter()` both return **null**.
   Since Android 13 Bluetooth is a mainline module whose service wrapper is registered
   during application init, which never happens for a plain main class.
2. `BluetoothFrameworkInitializer`'s service manager has to be constructed and installed
   by hand first — `ActivityThread` normally does this.
3. The adapter's binder arrives **asynchronously on a callback posted to the main looper**.
   Prepare the looper and never run it and `mService` stays null, so every bond operation
   returns `false` without throwing. Framework setup happens on the main thread, the work
   moves to a worker, and main loops until the worker quits. Prove it with
   `getBondedDevices()` — it returns null, not empty, while the binder is missing.
4. `AdapterService.createBond` returns false when
   `isPackageNameAccurate(callingPackage, callingUid)` disagrees. An adapter from
   `getSystemService` carries op package `android` against uid 2000.
   `BluetoothAdapter.createAdapter(AttributionSource)` is the only route that sets this;
   build it for uid 2000 / `com.android.shell`.

### The two transcripts that matter

Before the attribution fix ([light-reports #121](https://github.com/gi-os/light-reports/issues/121)) —
every line healthy and the bond never starts:

```
createBond false
RESULT gave up in state NONE (no request ever arrived)
```

After it ([#125](https://github.com/gi-os/light-reports/issues/125)) — the furthest this
has ever got:

```
createAdapter(shell): ok
attributed to com.android.shell (uid 2000) via createAdapter
createBond true
state BONDING
setPairingConfirmation true
waiting… 51s left, state BONDING
…
state NONE
RESULT refused after confirming
```

The confirmation is accepted, the two ends exchange for roughly 27 seconds, and the bond
then collapses to `NONE`. v0.30.40 exists only to answer *why*: `EXTRA_REASON` on
`ACTION_BOND_STATE_CHANGED` carries it, and nothing had ever asked. **That reason has not
been read yet. Read it first.** The candidates point in completely different directions —
keys mismatched, ring rejected it, ring went away, timed out, or *too many failed
attempts*, which is the stack's own backoff after a dozen-plus tries in an evening and the
only one with a remedy (toggle Bluetooth, wait a minute).

## 6. If you want to take this on

In order. Each step is written as what it would *prove*, because several of them can kill
the project honestly and that is worth more than another route.

1. **Read the collapse reason.** Install v0.30.40 or later, toggle Bluetooth off and on
   first to clear the stack's failure count, and run the helper once. One word decides
   everything below. If it is "too many failed attempts", the previous attempts were
   never being judged on their merits and route 9 may already work.
2. **Establish whether the plain link can be read at all.** Run the probe on a ring still
   paired to Oura's app and see whether firmware and serial come back. This is auth-free
   by protocol and needs no bond. If it works, the app has a real feature today and a
   much better story; if it does not, the frontier is lower than the bond and §7 is where
   to look.
3. **Walk `98ed0004`.** The undocumented read/write/notify characteristic is the only
   plausible request/response channel that would not need a subscription, and therefore
   would not need a bond. `Session.probe()` already reads every readable characteristic;
   what is missing is *writing* a request to `98ed0004` and reading the answer back from
   it. This is the one idea that could make the whole wall irrelevant, and it has not
   been tried.
4. **Bond the ring from another phone, then move it.** A bond lives in the stack, keyed by
   the identity resolving key, not in the app. If an Android phone with a working Settings
   can pair the ring, the question becomes whether that bond can be transplanted — it
   cannot be copied out of `/data/misc/bluedroid/bt_config.conf` without root, but it does
   answer whether the ring bonds at all with anything, which route 9 has never separated
   from LightOS being at fault.
5. **File it upstream at Light.** LightOS crashing on `PAIRING_VARIANT_CONSENT` is not an
   app bug and cannot be fixed from inside one. Every BLE peripheral needing an encrypted
   link is unusable on this phone, not just a ring. A reproduction is two lines: pair
   anything BLE that requires consent.
6. **Only then, the ring itself.** Reset it, install a key, switch measuring on, sync. And
   expect the first real frames to disagree with the decoders — the temperature and step
   layouts are inferred, marked `inferred = true`, and exist to be corrected by exactly
   this moment.

### What not to repeat

Routes 1 through 8 are closed, and the reasons are mechanical rather than circumstantial.
In particular: do not spend another evening on the Settings dialog, do not look for a
notification to press, and do not expect `adb` to help directly — no adb command starts or
confirms a bond. The platform exposes enable, disable and discoverable, and nothing else.
Shell is useful here for `dumpsys` before and after an attempt and for `logcat` across
one, not as a pairing tool.

## 7. Map of the code

Plain Android APK, not a Light SDK tool — `app/`, not `tool/`. That matters: the SDK
sandbox's dependency allowlist would not have permitted this, and nothing here goes
through LightOS's SDK server.

| file | what it owns |
|---|---|
| `ble/Protocol.kt` | GATT UUIDs, frame format, request builders. A port, not a guess |
| `ble/Auth.kt` | Key generation and the nonce proof. Pure, no Android |
| `ble/Ring.kt` | One connection as a suspending conversation. Callbacks feed a single channel; every command is write-then-take. Also the GATT dump and the walk of the undocumented characteristics |
| `ble/Session.kt` | A whole trip: connect, authenticate, ask, hang up. Nothing holds a link open across screens, because auth is session-scoped and a dropped link mid-screen is a state nobody wants to draw |
| `ble/Sync.kt` | The history drain. Cursor kept in the ring's own deciseconds, never converted through the phone's clock |
| `ble/Pairing.kt` | Routes 2, 3, 4 and 5, plus `bondOverLe` by reflection |
| `ble/Companion.kt` | Route 7 |
| `helper/Confirm.java` | Route 9. Java because `app_process` wants a plain `main` and there must be no framework of ours around it. Held against R8 by a keep rule, since nothing calls it |
| `data/Readings.kt`, `data/Day.kt` | Decode and rollup. **Deliberately free of Android imports** so they run against hand-built bytes on a desk. Three confidence levels kept apart: documented, validated by somebody else's capture, inferred |
| `data/EventLog.kt` | Every frame kept as raw bytes, decoded or not. The ring's buffer is finite: what it gives up once it will not give up again, so decoding is a re-parse and never a re-sync |
| `data/Vault.kt` | The ring's key, sealed with a hardware-keystore key that cannot be exported |
| `data/Trace.kt`, `data/Failures.kt` | The step-by-step trail, and shake-to-report into `gi-os/light-reports`. A BLE conversation leaves nothing behind; this app's whole job is the part that fails |

**Dependency worth knowing:** the pairing helper and the notification-listener grant both
arrive through **BrightControl** — its adb screen rebuilds the `app_process` line itself
from the requesting package (`adb/GrantRequest.kt` there). BrightOura cannot pair without
BrightControl installed and its wireless debugging working.

**Releases** are cut by a push to `main` (`build.yml`), with `versionCode` and
`versionName` overwritten from the workflow run number — `paths-ignore` covers `**.md`, so
a docs change like this one ships nothing.

## 8. The wider lesson, since it recurred the same week

The ring is one instance of a pattern worth naming for anyone building on this phone:
**LightOS is Android with pieces of the user-facing platform absent, and the absences do
not announce themselves.** Here it is a dialog that never draws and an Activity that
crashes. In the sibling repos the same week it was the SDK's own service: every Light SDK
tool with a text field started crashing on a newer LightOS because the keyboard-options
reply gained a field, lost a field, and the decode of it was the one unguarded step in the
call — so a screen that only wanted to draw a keyboard took the process with it (fixed in
BrightNews v2.6.2, BrightSudoku v1.2.2, BrightNonogram v0.4.1).

The habit that pays in both cases: assume the contract with the platform will move, make
the failure legible at the moment it happens, and keep the raw bytes. Two transcripts in
`light-reports` are the only reason the bonding story has a next step at all.

## 9. Sources

- [`open_oura`](https://github.com/Th0rgal/open_oura) — the protocol, MIT, verified against a Ring 3 Horizon and a Ring 5
- ringverse protocol notes — the Ring 4 cross-check
- [light-reports #121](https://github.com/gi-os/light-reports/issues/121), [#125](https://github.com/gi-os/light-reports/issues/125) — the two transcripts
- `git log` in this repo — every route, in the order it was tried, with why it failed
