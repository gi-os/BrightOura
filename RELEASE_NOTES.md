## BrightOura v0.18 — losing the race is now something the app recovers from

v0.17 stopped the *system* from bonding the ring before we could, by dropping the watch profile from
the association. That closes one of the two ways to lose the race. This closes the other, and makes
losing it survivable either way.

**The ring can start the pairing itself.** A peripheral may ask for security the moment a link is
up, and that path goes nowhere near `createBond` — so no caller is recorded against the address, and
`canBondWithoutDialog` reads false for the same reason it did when the system got there first. On
this phone that means the consent dialog, and the consent dialog means Settings crashing. Nothing
this app does before the fact can prevent a remote device from asking.

**So the app now asks again, properly, once.** A consent request arriving is the tell that the bond
was not credited to us. By the time it is known, everything needed to fix it is true: the bond that
beat us has failed and torn itself down, nothing is in flight, and the companion association is
still minutes from expiring. Asking from there is the first `createBond` of a fresh attempt, which
is the entire point. Once only — a retry loop is a phone that pairs forever and never says why.

**And the retry no longer answers itself.** The bond-state channel is conflated, so the `BOND_NONE`
announced by tearing down the failed attempt was still sitting in it: the retry's wait would have
received that stale failure instantly and reported a minute-long timeout that never happened. The
teardown's noise is dropped before the new request goes out.

The screen says which of these it is as it happens, rather than after: *"That pairing was not
credited to this app — asking again, properly."*
