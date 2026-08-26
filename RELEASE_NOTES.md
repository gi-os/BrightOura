## BrightOura v0.11 — three more ways at it

Reads came back empty: the ring pushes its replies and will not hand them over when asked. So this
release stops trying to be clever about the transport and goes after the three things still unknown.

**1. Listening without subscribing.** Subscribing is two halves — the phone is told to deliver
notifications from a characteristic, and the *ring* is told to send them, which is the descriptor
write that needs encryption. This does the first half alone. A strictly compliant peripheral sends
nothing until its descriptor says otherwise, but firmware written against exactly one app's
behaviour often pushes regardless — and this protocol was never meant to be spoken by anybody else.
One local call, no permission, no bond, and it either works or it does not.

**2. Pairing over LE only.** `createBond()` uses `TRANSPORT_AUTO`, and on a stack that also speaks
classic Bluetooth that can mean the phone attempts a **classic pairing against a device that only
speaks LE** — which is exactly "pairing… pairing… and then nothing", because the classic attempt has
nobody to talk to and gives up in silence. `createBond(TRANSPORT_LE)` has existed since Android 6 and
has never been public API; it is the standard workaround for this symptom, and it is reached by
reflection, reported either way, and falls back to the ordinary call.

That this phone's only bonds are a pair of headphones and a speaker — both classic — is consistent
with a stack that has never successfully completed an LE bond, which is what makes this the most
promising of the three.

**3. The whole GATT table.** Every service, characteristic, property and descriptor the ring
advertises, in the diagnosis, ready to paste. **Nobody has dumped a Ring 4** — the protocol notes
were written against a Ring 3 and a Ring 5 — and every remaining idea depends on what is actually
there: a second characteristic that answers reads, a service nobody documented, a characteristic
whose properties say `read` where the notes say `notify`. None of that can be guessed at, and all of
it is one press away now.

## BrightOura v0.10 — it was my own fallback that closed the app

**"It closed the app" was this app leaving.** v0.8 opened a Bluetooth screen automatically when a
pairing request could not be shown, on the reasoning that the request expires in under a minute and
there is no time to explain. What it actually did was switch away from BrightOura *in the middle of
its own connection* — which reads as the app closing itself, kills the GATT conversation it was in,
and leaves the ring half-paired. A fallback that destroys the thing it was helping is not a fallback.
It is a button on the setup screen now, and only that.

**And the trail shows why the probe never got anywhere.** Connected in 749ms, discovered services at
1646ms, subscribed at 1662ms — and the pairing request arrives 800ms later. That is not a
coincidence: **the descriptor write that subscribes is what asks for the bond.** The bond cannot
complete on this phone, so the callback for that write never comes, so the connection sits there
until it times out twenty seconds later having asked the ring nothing at all.

**So it does not subscribe any more.** The link is plain and unencrypted; requests are written the
same way; replies are read from the notify characteristic rather than pushed. That is the opposite of
how BLE is normally written and it is the right way round on a phone whose BLE bonding does not work:
nothing in that path needs encryption, so nothing in it can be blocked by a bond that will not
finish.

Whether the ring answers a read is the one thing left that nobody can know from outside. The probe
will now find out in about two seconds instead of timing out.

## BrightOura v0.9 — asking instead of being told

**"Pairing, pairing, pairing, then nothing" from the phone's own Bluetooth screen is the real
finding.** The ring is in pairing mode — blue on the charger — the phone starts a bond, and the bond
never completes. That is not a missing dialog any more; that is the bond itself failing.

And the diagnosis has the corroborating detail: the two devices this phone *has* bonded are
headphones and a speaker. Both classic Bluetooth. **There is no evidence a BLE bond has ever
succeeded on this phone**, which points at the platform rather than at the ring.

**So this release stops needing one.** Subscribing to notifications is the first thing on the link
that requires encryption, which is why it is the first thing that fails — and a refused subscription
is no longer treated as a dead connection. Instead:

- The connection stays open.
- Requests are still written the same way.
- The reply is **read** from the notify characteristic rather than waited for, a few times, a beat
  apart, until something arrives.

Polling is worse than being pushed to in every respect except the one that matters here: it needs no
encrypted link. Whether *this* ring answers a read — some devices set the value, some only push it —
is not knowable from outside, and is exactly what a probe will now say.

**The probe leads with what the link managed.** A new first row: the properties both characteristics
declare (`read/write/notify`) and whether the push channel was available at all. On a phone that
cannot bond, "NOT subscribed" is the whole story and every empty field under it follows from that one
fact — which is a better thing to read than four dashes and no reason.

**What this does not do is pretend.** If the ring only ever pushes its replies, this will find
nothing and say so, and the answer is that a factory-reset Oura ring cannot be read by an app on a
phone whose BLE bonding is broken. That is worth knowing precisely rather than approximately, and it
would be a fault to report to Light rather than something to code around.

## BrightOura v0.8 — what the diagnosis said, and what is left

**The trail from a real attempt settles most of this.** The connection works: MTU raised, services
discovered, subscribing — and then a pairing request of **variant 3**, which is the plainest kind
there is: a yes or no, nothing to type. Confirming it in-process needs `BLUETOOTH_PRIVILEGED`, which
is signature-level and cannot be granted over adb, so that attempt throws. Raising the Settings
dialog was refused too. The bond is not failing on anything cryptographic — it is failing because
**a yes has nowhere to be said on this phone**.

**And the ring has never been keyed.** It advertises `Oura 20160C…`, its own serial. A ring that has
been through an app's onboarding renames itself; one straight out of a reset does not. That is also
*why* it insists on an encrypted link before it will talk — a reset ring does, and a keyed one is
happy without. The row says so now: **never keyed**.

**Two of the numbers in that trail are worth keeping.** The first connect took the address the scan
handed over twenty seconds earlier and got nothing at all — the ring's address rotates, the retry
re-scanned and connected in two seconds. And the pairing variant is now spelled out in words
alongside the number, because 3 versus 0 is the difference between "there is nowhere to say yes" and
"there is nowhere to type a PIN", and those are not the same problem.

**When a request cannot be confirmed, the app now goes looking for a screen by itself** rather than
explaining and waiting: the pairing dialog with no package named — so any handler this build
registers gets a chance, not only Settings — and then Light's own Bluetooth screen, opened without
being asked. The request expires in under a minute; a user reading an explanation has already missed
it.

**What the two bonded devices in that diagnosis prove:** headphones and a JBL speaker, both bonded,
both fine. Pairing works on this phone when it is *started from Light's own Bluetooth screen*. What
does not work is a BLE bond started by an app, whose consent lands in a notification nothing draws.

## BrightOura v0.7 — around the crashing Settings app

**The system settings app crashes on its pairing screen, so this stops sending you there.** That is
not something an app can fix from outside, and retrying it is not a plan. LightOS has a Bluetooth
screen of its own — it is how this phone pairs earbuds, and it draws its own prompt rather than
relying on the notification nothing renders here.

So the Bluetooth row now looks for *that* first: it asks the package manager which of LightOS's own
activities sound like Bluetooth and opens it directly. Found by search rather than hardcoded, because
an activity name inside somebody else's launcher is exactly the constant that changes in an update.
If there is no match it opens LightOS's own settings, and only then falls back to the app that
crashes.

**Copy the diagnosis.** A row that puts the whole state of this on the clipboard: adapter, whether
the permissions really are granted, every device the phone is bonded to and its bond state, whether
the system holds a companion association, whether a key is stored, and the last attempt step by step.
This repository has no reporting key yet, so the fastest path from a stuck phone to a fix is a paste
into a chat window rather than a report that queues forever.

**And the two things worth trying before anything in this app**, now written on the screen where
they matter:

- **Unpair or switch off the phone that already owns the ring.** A ring holding one link refuses a
  second, and that failure looks identical to "the ring is not there" — status 133, no answer.
- **Pair from Light's own Bluetooth screen**, not the system settings app.

## BrightOura v0.6 — the watcher was listening at the wrong moment

**The chime during a probe had nothing listening to it.** v0.4 watched for the pairing request only
while an *explicit* bond was in flight — and that is not when the request arrives. It arrives the
moment a connection touches something needing an encrypted link, which is during the probe itself.
So the phone chimed, the one chance to put the dialog on screen went past unheard, and the request
expired. The watcher now covers the whole connection.

**And the ring listed twice, which was also real.** A ring advertises with a rotating private
address, so the same ring shows up under two of them inside a minute — and a bonded ring adds a
third entry from the bond list sharing an address with neither. One row per ring now, keyed on its
name, keeping the bonded entry if there is one and otherwise the strongest signal. Each row says
whether the phone considers it paired, because that is the fact the whole setup turns on.

**The instruction that actually works is now first on the screen: pair it in the phone's own
Bluetooth settings.** LightOS draws its own pairing prompt there. The system's notification-based
request — the chime with nothing behind it — is what this app cannot reach, and no amount of
retrying inside the app will change that. A bond made once in that screen outlives every other path
here, and a probe afterwards needs none of them.

The other three routes stay as fallbacks: the companion picker, answering the request in-process,
and raising the Settings dialog directly. A failed probe now points at the one that works instead of
suggesting you try again.

## BrightOura v0.5 — let the phone do the pairing

**The probe is what triggers the chime, which tells us the ring does want a bonded link.** So the
pairing cannot be avoided — it has to be *completed*, on a phone that never draws the request.

**The companion-device flow is the one dialog this phone will draw.** It is a different shape from
an ordinary pairing request: the app asks, the **system** runs a device picker, and the picker comes
back as an `IntentSender` the app launches itself — an activity, not a notification. So it appears
on a phone that renders no notifications at all. And with the watch profile, the platform takes on
the pairing as part of associating, which is precisely the step that has been failing.

**Setup → Let the phone pair it.** One system dialog, in the system's own words, listing what it is
being asked for. The association is remembered afterwards, so this is a one-time step rather than a
login. If the watch profile is refused it falls back to a plain association, which still gets the
ring into a picker the user can confirm.

Everything from v0.4 is still there and still tried first — answering the request ourselves, raising
the Settings dialog directly, and Bluetooth settings by hand. This adds the path that does not depend
on any of them working.

Order to try, if a ring is being stubborn: **Let the phone pair it** → then **Probe**. The rest are
fallbacks for a phone where the companion service is missing.

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
