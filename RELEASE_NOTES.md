## BrightOura v0.19 — pair it with the screen off

**The notification listener has never fired once, and now I know why.** It was built to press the
Pair button on the system's pairing notification, on the theory that LightOS posts the notification
and never draws it. Wrong half. Here is what the platform actually does with a pairing request, one
line below the check the last three releases were about:

```java
} else if (powerManager.isInteractive() && shouldShowDialog) {
    context.startActivityAsUser(pairingIntent, …);   // the dialog
} else {
    context.startServiceAsUser(intent, …);           // a notification
}
```

Awake, the phone starts the dialog **activity** — and on LightOS that activity builds a null dialog
for the consent variant and takes Settings down with it. There was never a notification to answer.
Every attempt so far has been made with the screen on, so the crashing branch won every time,
including the ones from inside the Bluetooth screen, where `shouldShowDialog` is true by definition.

**Asleep, the same request is posted as a notification.** A notification has buttons, and pressing
that button is precisely what this app's listener does. No dialog is started, so Settings is never
involved, so nothing crashes.

So the app now says so at the moment it matters — as the request goes out, not afterwards: *press
the power button now.* And if the listener grant is missing it says that first, because a sleeping
phone with nobody to answer the request is worse than an awake one: it fails silently and looks like
the ring's fault.

**Also worth writing down: the bond is not optional.** Independent work on the Ring 3 Horizon finds
that after a factory reset, link encryption is required before any notify subscription or write —
the app-level auth key cannot be installed over an unencrypted link. So there is no version of this
that skips pairing and goes straight to the protocol. The bond has to happen; this is the route by
which it can.
