## BrightOura v0.16 — pairing goes through the association, and lands on the same address

**The ring could not be paired on this phone, and the reason was never the ring.** The crash log
finally says it outright: the bond starts, the stack asks Settings to draw the consent dialog, and
Settings dies building it — `NullPointerException` in `DialogFragment.prepareDialog`, because
LightOS's pairing fragment returns a **null dialog** for pairing variant 3. Nothing is on screen to
accept, the bond times out, the ring goes back to unpaired. Every device that needs a consent
dialog hits this, which is also why an iPad would not pair.

**Android has a way around its own dialog, and it is stricter than "associate the device".** The
framework's rule, verbatim:

```java
canPairWithoutPrompt(pkg, mac, user) {
    association = getFirstAssociationByAddress(user, pkg, mac);   // this exact address
    return now - association.getTimeApprovedMs() < 10 * 60 * 1000; // approved under 10 min ago
}
```

Both halves had been failing. This app already ran the system picker, but then went back to the
scan list to bond — and an Oura ring advertises with a **rotating private address**, so the address
it bonded was not the address that was associated. The phone was holding two associations from
hours earlier, for two addresses the ring had already moved on from, while every bond attempt used
a third.

**So the picker's answer is now the thing that gets paired.** The address comes off the picker's own
result — `EXTRA_ASSOCIATION` where the platform provides it, the returned scan result otherwise —
and pairing starts on that address immediately, in the same breath, with no second scan in between.
No prompt is drawn, because the platform has no reason to draw one. Settings is never started, so
Settings never crashes.

**Stale associations are dropped as they are replaced.** One ring should not accumulate an
association per address it has ever advertised, and a list full of dead addresses is worse than an
empty one: it makes the setup look done while the only check that matters keeps failing. Each
successful pick keeps its own association and clears the rest.

**And the diagnosis says which addresses are associated,** not just whether any are. "Associated:
true" was technically correct and completely useless on a phone holding two associations that could
never match.
