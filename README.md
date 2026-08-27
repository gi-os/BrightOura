<div align="center">
  <img src="docs/icon.png" alt="" width="72" /><br/>
  <h1>BrightOura</h1>
  <p><strong>Your Oura ring, read straight off the ring.</strong><br/>
  For the Light Phone III. No account, no cloud, no network.</p>
</div>

## What it is

The ring measures continuously and hands everything to Oura's phone app, which uploads it. On a
phone with no Oura app — this one — a ring is jewellery. But the ring's own Bluetooth protocol has
been reverse-engineered, and it turns out the ring gives up almost everything on request.

This app asks. Heartbeat intervals, heart-rate variability, skin temperature, blood oxygen, motion,
step counts, and the ring's own sleep staging — all of it read over Bluetooth, stored on the phone,
and never sent anywhere.

## What this release does

The risky half first, in the order the risk lives.

| step | what happens |
|---|---|
| **Find** | Scans for the ring's service UUID, not its name — a reset ring calls itself something different from a paired one |
| **Look** | Reads firmware, serial and hardware id **without authenticating and without changing anything** |
| **Adopt** | Installs a key of ours, then switches the ring's measuring on |
| **Sync** | Drains the ring's history into a log on the phone, every frame kept as raw bytes |

There are deliberately **no scores and no sleep summary on screen yet**. Decoding somebody else's
event stream and then drawing a confident number from it is how an app lies to you about your own
night. The frames come first. The numbers come once they have been checked against a real ring.

## How the ring's authentication works

Smaller than its reputation. The ring holds a 16-byte key; every connection it offers a 15-byte
nonce, and a client proves itself by encrypting that nonce with the key:

```
install key   24 10 <16-byte key>     → 25 01 00      (factory-reset ring only)
get nonce     2f 01 2b                → 2f 10 2c <15-byte nonce>
authenticate  2f 11 2d <16 bytes>     → 2f 02 2e 00   accepted
                                      → 2f 02 2e 01   wrong key
```

The 16 bytes are `AES/ECB/PKCS5Padding` over the nonce — which is the same thing as
`AES-128-ECB(key, nonce ‖ 0x01)`, because PKCS#5 pads a 15-byte input with exactly one `0x01`. Both
descriptions are in the wild and they describe one operation; there is a test in this repo that says
so, so a later refactor cannot quietly change the answer.

Authentication is **session-scoped**: it is redone on every connection. Firmware and serial answer
cold; battery, history, features and the live streams answer `2f 02 2f 01` until the session is
authenticated — which is the ring asking, not an error.

## What it costs

**Adopting a ring takes it off Oura's app.** A key can only be installed on a factory-reset ring, so
setup means resetting yours. Re-onboarding it in Oura's app later gives it a new key and takes it
back from here. That trade is stated on the setup screen before anything is touched, and the probe
step works *without* it — so you can confirm this app sees your ring before deciding.

**A ring keyed outside Oura's app has its measuring switched off.** Oura's app turns daytime heart
rate and blood oxygen on during onboarding; a ring adopted here would authenticate, sync, and
produce no heart rate at all. Pairing switches them on, and says how many answered.

## Where the protocol came from

The [`open_oura`](https://github.com/Th0rgal/open_oura) project, which reverse-engineered it from
the official Android app's native libraries and verified it live against a Ring 3 Horizon and a
Ring 5, cross-checked against the ringverse Ring 4 notes. Ring 3, 4 and 5 share the same service,
framing and authentication — the generations differ in which event tags they emit, not in how you
talk to them.

Nothing in this app was guessed. Where the notes name an event, this app names it; where they do
not, the frame is kept anyway, because the ring's history buffer is finite and what it has given up
once it will not give up again.

## What is not here

The 0–100 Sleep, Readiness and Activity scores, and workout classification. Those are not in the
ring: they are computed by Oura's own engine from these same signals. This app can compute its own
numbers from the same inputs, and when it does they will be labelled as its own rather than dressed
up as Oura's.

## Where this stands

Parked at v0.30. The protocol is implemented and the decoders are tested, and the ring is
still not readable, because LightOS cannot complete the Bluetooth bond the ring requires —
the pairing screen behind the request crashes, and nine routes around it end at the same
place. [docs/HANDOFF.md](docs/HANDOFF.md) is the whole record: what works, every route
tried and why it failed, the two transcripts that got furthest, and what somebody picking
this up should do first.

## Privacy

Bluetooth, and one network permission for shake-to-report. The ring's data is written to this app's
private storage and goes nowhere else. The ring's key is sealed with a key generated inside the
phone's hardware keystore, which cannot be exported even by this app.
