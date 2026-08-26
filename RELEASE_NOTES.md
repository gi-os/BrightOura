## BrightOura v0.22 — the decoding layer, with the guesses labelled as guesses

Nothing on screen yet. This is the part underneath: the sync loop has been keeping every frame the
ring hands over as raw bytes, and this is what turns those bytes into numbers — written as plain
Kotlin with no Android in it, so it can be run against hand-built frames on a desk rather than
against a ring that only has one night per night.

**Three levels of confidence, kept apart on purpose:**

- **Documented.** The frame envelope, the event-tag table and the meaning of the timestamp come from
  the Ring 4 protocol notes. Several events pack into one notification, `tag | length | payload`,
  and the first four bytes of a payload are the timestamp.
- **Validated by somebody else's overnight capture.** Inter-beat intervals (`0x60`, `0x80`) and HRV
  (`0x5D`) — an interval of 800 ms is 75 bpm, and the ring's own quality rides in the same pair of
  bytes without disturbing it. These produce real units.
- **Inferred, and marked as such.** Temperature and step counts are known to exist in their events
  and known roughly what they should read; nobody has written down the byte layout. They are decoded
  to the most plausible reading with `inferred = true` on the reading itself, so a screen can show
  them differently and nobody builds a habit on a scaling error.

**A frame nothing can read is counted, never invented** — stored, named ("Sleep summary (2)", not
"0x4c"), and reported as `unread`, which is how we know which decoder to write next. And because
the raw log keeps everything, a better decoder is a re-parse rather than another night of waiting.

**The day rollup refuses to flatter you.** A resting heart rate is the tenth percentile rather than
the minimum — one dropped beat reads as 38 bpm, and reporting that as a resting rate tells somebody
something about their heart that a bad contact told them. Fewer than thirty beats gives no resting
rate at all. Steps are summed, because each event is a count since the last one. Temperature is
reported as deviation from your own median, because 33.5 °C is not a fact anybody can use.

**No sleep score.** Oura's scores are the output of a model this app does not have, and an invented
one that lands eleven points off the official app is worse than none: it looks like the same thing
and is not. What is here is measurement — beats, degrees, steps, and the hours the ring believed it
was worn.

Twenty-one tests on the decoders and thirteen on the rollup, all of them run against bytes and lists
built by hand.
