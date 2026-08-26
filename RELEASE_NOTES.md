## BrightOura v0.20 — it waits for the screen to go off, instead of asking you to be quick

v0.19 said *press the power button now*. That is a race, and it is not one a person can be expected
to win: the pairing request arrives four or five seconds after the bond starts, and the platform
decides which branch to take **when the request arrives**, not when the bond began. Press it a
second late and Settings crashes exactly as before.

**So the app waits for the screen instead.** Pairing now holds until `ACTION_SCREEN_OFF` actually
fires, waits a beat for `isInteractive` to catch up with it, and only then asks. Lock the phone
whenever you like; the request goes out on your timing, not against it.

```java
} else if (powerManager.isInteractive() && shouldShowDialog) {
    context.startActivityAsUser(pairingIntent, …);   // the dialog LightOS cannot build
} else {
    context.startServiceAsUser(intent, …);           // a notification, with a Pair button
}
```

`shouldShowDialog` is not ours to move — it is true whenever Settings has recently seen the device,
which on a phone whose Bluetooth screen keeps getting opened is most of the time. `isInteractive` is
the half a person can change, and the two are joined by `&&`, so a sleeping screen settles it alone.
The notification that gets posted instead has a Pair button, and pressing that button is the entire
job of the listener this app has shipped for five releases without it ever firing once.

**The retry waits too.** A failed first attempt means a crashed Settings and a phone somebody has
just picked up to look at — so by then the screen is awake again, and asking from there would take
the same branch a second time.

Forty-five seconds to lock it, and if the screen never goes off the attempt still goes ahead rather
than hanging: a pairing that might work beats one that certainly did not happen. The screen says
which of those it is doing.
