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
