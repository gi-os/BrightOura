package com.gios.brightoura

import com.gios.brightoura.data.Readings
import com.gios.brightoura.data.Readings.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bytes off the wire, turned into numbers. Built by hand from the documented layouts. */
class ReadingsTest {

    private fun hex(s: String) =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun frame(tag: Int, payload: String) = Readings.Frame(tag, 10, hex(payload))

    // ---- the envelope -------------------------------------------------------

    @Test
    fun `several events arrive in one notification`() {
        val frames = Readings.frames(hex("5d 06 00 00 01 00 3c 28  53 05 00 00 01 00 01"))
        assertEquals(listOf(0x5D, 0x53), frames.map { it.tag })
        // The first four bytes of a payload are the timestamp, little-endian.
        assertEquals(65_536L, frames[0].ticks)
    }

    @Test
    fun `a truncated notification keeps the frames before the truncation`() {
        // A length running past the end of the buffer stops the walk. Throwing here would lose
        // the good frames in front of it, and a truncated notification is a thing that happens.
        assertEquals(1, Readings.frames(hex("5d 06 00 00 01 00 3c 28  60 40 00 00 01 00 ff")).size)
    }

    @Test
    fun `a reply is not an event`() {
        // Anything below 0x41 answers something this app asked, and is handled where it was asked.
        assertTrue(Readings.frames(hex("0d 06 64 00 00 ff ff ff")).isEmpty())
    }

    // ---- heart --------------------------------------------------------------

    @Test
    fun `an interval of 800ms is 75 beats a minute`() {
        val heart = Readings.read(frame(0x60, "64 00")).single() as Reading.Heart
        assertEquals(800, heart.intervalMs)
        assertEquals(75, heart.bpm)
    }

    @Test
    fun `quality rides in the same pair of bytes without moving the interval`() {
        val heart = Readings.read(frame(0x60, "64 10")).single() as Reading.Heart
        assertEquals(800, heart.intervalMs)
        assertEquals(2, heart.quality)
    }

    @Test
    fun `six intervals come out of one frame`() {
        assertEquals(6, Readings.read(frame(0x60, "64 00 64 00 64 00 64 00 64 00 64 00")).size)
    }

    @Test
    fun `an impossible interval is not reported as a heart rate`() {
        // A bad read is a plausible number in the wrong place. A screen that says 12 bpm teaches
        // somebody to distrust the readings that are right.
        assertTrue(Readings.read(frame(0x60, "00 00")).single() is Reading.Unread)
    }

    @Test
    fun `hrv carries its own rate and rmssd`() {
        val hrv = Readings.read(frame(0x5D, "28 65")).single() as Reading.Hrv
        assertEquals(40, hrv.bpm)
        assertEquals(101, hrv.rmssdMs)
    }

    // ---- the inferred ones --------------------------------------------------

    @Test
    fun `a worn temperature is read and marked as a guess`() {
        val temp = Readings.read(frame(0x46, "fd 0c")).single() as Reading.Temp
        assertEquals(33.25, temp.celsius, 0.001)
        // Nobody has written the scaling down. Labelling it is what makes decoding it defensible.
        assertTrue(temp.inferred)
    }

    @Test
    fun `a temperature nothing plausible comes out of is not a number`() {
        assertTrue(Readings.read(frame(0x46, "00 00")).single() is Reading.Unread)
    }

    @Test
    fun `steps are read, and an inflated count is refused`() {
        assertEquals(300, (Readings.read(frame(0x7E, "2c 01")).single() as Reading.Steps).count)
        // Tens of thousands in one event is the wrong two bytes, not a long walk.
        assertTrue(Readings.read(frame(0x7E, "ff ff")).single() is Reading.Unread)
    }

    @Test
    fun `wear is on or off`() {
        assertTrue((Readings.read(frame(0x53, "01")).single() as Reading.Wear).worn)
        assertTrue(!(Readings.read(frame(0x53, "00")).single() as Reading.Wear).worn)
    }

    // ---- what cannot be read yet -------------------------------------------

    @Test
    fun `an undecoded event is counted and named, never invented`() {
        val unread = Readings.read(frame(0x4C, "de ad be ef")).single() as Reading.Unread
        assertEquals(0x4C, unread.tag)
        assertEquals(4, unread.bytes)
        // "0x4c, 118 of them" tells nobody anything; the name says which decoder to write next.
        assertEquals("Sleep summary (2)", Readings.name(0x4C))
    }

    // ---- the clock ----------------------------------------------------------

    @Test
    fun `ring time converts through one pair of corresponding instants`() {
        val clock = Readings.Clock(anchorTicks = 1_000, anchorEpochMs = 1_700_000_000_000)
        assertEquals(1_700_003_600_000, clock.epochMs(4_600))
        assertEquals(4_600L, clock.ticks(1_700_003_600_000))
        // Events from before the anchor are ordinary, not an error: the ring's buffer predates
        // the moment this app asked it what time it was.
        assertEquals(1_699_999_400_000, clock.epochMs(400))
    }
}
