## BrightOura v1.1 — it just shows the ring

v1.0 added the Mac bridge; v1.1 makes it feel like the app is simply connected to the ring.

- **Opens straight to the data and reads on its own.** No SYNC button to remember — opening the
  app reads the ring from the Mac, the way a directly-connected app would.
- **The data is the app.** The ring's measurements are the whole screen now. The Bluetooth pairing
  tabs are gone from view — they only reappear if the Mac bridge cannot be reached, which is the
  only time direct pairing is worth trying.
- **Fixes "Cleartext HTTP not permitted."** The bridge is a plain-HTTP service on your own network,
  which Android blocks by default; the app now allows it.

Everything the ring measured — heart rate, HRV, temperature from your own baseline, steps, worn
hours — by day, decoded on the phone. Still no invented scores.
