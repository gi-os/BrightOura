## BrightOura v1.0 — the ring's data, on the phone at last

For every release until now this app could find the ring, adopt it, and drain its history — but only
if the ring would pair over Bluetooth, and on the Light Phone III it never would. Its pairing
handshake stalls LightOS's Bluetooth stack every time (proven again on a factory-reset ring: 30
seconds in `BONDING`, then gone). That wall does not move from inside an app.

So this release stops trying to climb it. A Mac on your network holds the ring instead — it pairs
where the phone cannot — and serves the ring's synced history to the phone over the network. The
phone decodes and shows it with the same code the Bluetooth path used, so nothing about the numbers
changed; only where they come from did.

**New: the DATA tab.**

- Point it at the Mac bridge (defaults to the home Mac mini, editable on the screen).
- **SYNC** pulls the ring's history the Mac has drained and decodes it here — heart rate, HRV, skin
  temperature away from your own baseline, steps, and the hours the ring believed it was worn,
  broken out by day.
- Battery and firmware, read the same way.

Still deliberately no invented scores: this shows what the ring measured, not a model's guess at a
number Oura computes elsewhere.

The Bluetooth tabs (RING, SET UP, FRAMES) are unchanged and still there — the direct path remains
for any phone whose stack can complete the pairing.

### Setting up the bridge

The Mac runs `open_oura` (bonded to the ring) plus a small read-only JSON server; the phone reaches
it over your LAN or Tailscale. The server address goes in the DATA tab. Full setup notes ship with
the bridge.
