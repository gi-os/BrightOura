## BrightOura v0.4 — the chime with no dialog

**"I hear a notification but nothing appears on screen" is the whole bug, and it is not ours.**
Android's pairing request is a *notification with a full-screen intent*: the system posts it and
expects the launcher to raise the dialog behind it. LightOS does not. So the phone chimes, the
request sits in a shade that cannot be opened, the window expires, and the bond fails with every
layer working as designed and nothing on screen to accept.

Three ways at it now, in order of how well they work:

**1. Answer it ourselves.** The request is broadcast, and for the "Just Works" variants a listener
can confirm it directly. On modern Android that call wants `BLUETOOTH_PRIVILEGED`, which is
signature-level and cannot be granted over adb — so it usually throws, and the throw is *reported*
rather than swallowed. Sometimes it works, and it is the only path that needs no screen at all.

**2. Raise the dialog ourselves.** The dialog is an ordinary activity in the Settings package.
Started directly, with the device and the pairing variant, it appears — which is exactly what the
notification was supposed to do. Two attempts, action-with-package then component-by-name, because
which one a build accepts is not something to guess from out here.

**3. Bluetooth settings, once.** A bond made in the system's own screen is a bond this app never has
to ask for again. There is a row for it on the setup screen, and it says why: on this phone the
pairing request chimes without appearing.

If all three are refused, the app now says the honest thing — this ring wants a bond and this phone
will not make one — instead of timing out with a shrug.

Worth remembering from v0.3: **most rings need no bond at all.** Probe first. The bond path only
matters for a factory-reset ring that demands an encrypted link, and the probe will say so.

## BrightOura v0.3 — the prompt was never coming, and that was the bug

**"No pairing prompt ever appears" is not a symptom, it is the normal case.** Most BLE bonds are
"Just Works": the phone and the device agree on a key with **no dialog of any kind**. v0.2 asked
Android to pair *before* connecting and then waited a minute for a user to accept something — so on
a ring that needed no pairing, and on a ring that pairs silently, the app sat there and then reported
a pairing failure on hardware that was ready to talk.

**Connect first; bond only when the link says to.** A ring still onboarded to Oura's app will answer
firmware and serial with no bond at all. If a GATT operation comes back with an
insufficient-encryption status — 5, 15, or Android's own 137 — *then* the link genuinely needs a
bond, and only then is one asked for. Nothing else in the GATT status space means "pair with me",
and treating other failures as a pairing problem is how an app asks somebody to accept a prompt that
was never going to appear.

**A retry that re-scans, because the ring's address rotates.** An Oura ring advertises with a
rotating private address, so the address from a scan a minute ago can already be stale — and a
connect to a stale address fails with status 133, which reads exactly like "the ring is not there".
The probe now looks again and tries once more, which turns the commonest failure on this phone into
a retry nobody has to understand.

**Status codes in words.** `133` is the number every Android BLE developer knows and no user could:
it almost always means asleep, out of range, or busy with another phone. The screen says that now,
along with the rest of them.

**And it says whether a scan can even happen.** Bluetooth off and a refused scan permission produce
an identical empty result from inside a scan, and neither is fixed by trying again — so both are
checked and reported before the scan, not after it.

**A Pair link button, for the ring that will not say so.** The connect path asks for a bond by itself
when the link demands one. This is the manual version, and it reports what happened rather than
implying a prompt: `createBond` refused, pairing finished, or a minute passed with nothing.

## BrightOura v0.2 — it pairs now, and it says what it is doing

**Three things stopped the ring connecting, and all three were mine.**

**The scan filtered on the ring's advertised service UUID.** Correct for a ring that advertises it,
and a ring that keeps it in its GATT table instead is then invisible — there is nothing to press.
Everything nearby is scanned now and the rings are picked out of it three ways: the service if it is
advertised, the name if the ring gave one, or an existing bond with something called Oura. The count
of other devices seen is reported too, because "nothing at all answered" and "eleven things
answered, none of them a ring" are different problems with the same empty list.

**Nothing ever asked Android to pair.** The ring refuses notify subscription and writes on an
unencrypted link, and the platform answers *that* with a GATT failure rather than a pairing prompt —
so the connection failed with no dialog to accept and nothing on screen to explain it. `createBond()`
is called before connecting now, with a minute to answer, which is what actually raises the system
prompt.

**And two smaller ones in the same path.** `connectGatt` was using `TRANSPORT_AUTO`, which on some
phones tries BR/EDR against a device that only speaks BLE and fails as though the ring were not
there — it asks for `TRANSPORT_LE` explicitly now. And a *refused* MTU request left the flow waiting
for a callback that was never coming; it falls through to service discovery at the default MTU
instead, which is worse but works.

**The screen stays awake while it is working.** LightOS's timeout is short, the pairing prompt is a
notification you have to reach for, and a screen that sleeps mid-pairing takes the prompt with it.
Held only while something is in flight — an idle app does not keep the panel on.

**And it says what it is trying.** Every step reports itself: asking to pair, connecting, raising the
MTU, discovering services, subscribing, authenticating. Ten to sixty seconds of silence is how a
person ends up pressing a button twice, which starts a second conversation with the same ring and
breaks both.

**A failure files itself, with the trail attached.** Not offered — filed. Everywhere else in the
collection a report waits for a tap, and this is the one app where that is wrong: it talks to
hardware whose protocol came from somebody else's reverse engineering, against a ring generation
nobody has tested it on, and the breadcrumbs explaining a failure are gone the moment the screen
changes. Steps, timings and status codes go in; no serial, no key, no measurements. One report per
kind of failure per ten minutes.

Reports queue on disk and go out when a build has a reporting key. This repository has no
`REPORT_TOKEN` secret yet, so they will wait — the RING screen says so when any are waiting, rather
than letting them pile up silently.

## BrightOura v0.1 — it talks to the ring

**First release, and deliberately the unglamorous half.** Find a ring, look at it without touching
it, adopt it, drain its history. No scores, no sleep summary, no charts — decoding somebody else's
event stream and then drawing a confident number from it is how an app lies to you about your own
night, so the frames come first and the numbers come once they have been checked against a real
ring.

**Probe before pairing, and probing changes nothing.** Firmware, serial and hardware id answer
without authentication, on a ring that still belongs to Oura's app. So the first question — can this
app see *my* ring — is answered before anybody is asked to factory-reset anything.

**The authentication, ported rather than invented.** The ring holds a 16-byte key and offers a
15-byte nonce per connection; the proof is `AES/ECB/PKCS5Padding` over that nonce. That is the same
operation as the `AES-128-ECB(key, nonce ‖ 0x01)` description found elsewhere, because PKCS#5 pads
15 bytes with exactly one `0x01` — and there is a test asserting the two agree, so a future refactor
cannot quietly change the answer. Session-scoped, so it is redone every connection.

**Pairing switches the measuring on, and that is not politeness.** A ring keyed outside Oura's app
has daytime heart rate and blood oxygen *off* — Oura's app turns them on at onboarding. Without this
step the ring would authenticate, sync, and produce no heart rate, and the app would look broken
while working perfectly. It reports how many of the three features answered rather than a boolean:
a ring that takes heart rate and refuses SpO2 is a working ring.

**Every frame is kept as raw bytes**, named where the protocol notes name it and kept anyway where
they do not. The ring's history buffer is finite: what it has given up once it will not give up
again, so a decoder that only stores what it currently understands is throwing away next month's
features. Decoding is a pass over the log, and can be rewritten as often as it needs to be.

**The cursor is the ring's own clock**, in deciseconds, stored exactly as the ring reported it.
Converting through a phone clock that drifts, changes zone and is wrong for a minute after every
boot is how a sync silently re-reads a week or skips one.

**The key is sealed by the phone's hardware keystore** — not user-authentication-bound, because a
sync runs with the screen off, but unexportable, which is the threat that actually exists: a backup
or an adb pull of app data.

Ring 4 is the target; Ring 3 and Ring 5 share the same service, framing and auth, so they should
work and the event tags are where they differ.
