package com.gios.brightoura

import com.gios.brightoura.data.Day
import com.gios.brightoura.data.Readings
import com.gios.brightoura.data.Readings.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A night of readings folded into the handful of numbers worth showing. */
class DayTest {

    private val clock = Readings.Clock(anchorTicks = 0, anchorEpochMs = 0)

    /** Mostly fifty, with one dropped beat and one spike — which is what a real night looks like. */
    private val night: List<Reading> = buildList {
        repeat(200) { add(Reading.Heart(it.toLong(), 50, 1_200, 0)) }
        add(Reading.Heart(500, 38, 1_578, 3))
        add(Reading.Heart(501, 130, 461, 1))
        repeat(6) { add(Reading.Hrv((600 + it * 300).toLong(), 48, 90 + it)) }
        add(Reading.Steps(700, 300))
        add(Reading.Steps(800, 250))
        add(Reading.Temp(900, 33.6))
        add(Reading.Temp(950, 33.4))
        add(Reading.Wear(0, true))
        add(Reading.Wear(28_800, false))
        add(Reading.Unread(999, 0x4C, 12))
    }

    @Test
    fun `a resting rate is not the single lowest beat`() {
        val day = Day.summarise(20_000, night, clock, baseline = 33.0)
        // A single 38 is a bad contact. Reporting it as a resting rate makes somebody believe
        // something about their heart that a sensor artefact told them.
        assertEquals(50, day.restingBpm)
        // It is still reported, as the lowest thing seen. Those are different questions.
        assertEquals(38, day.lowestBpm)
        assertEquals(130, day.highestBpm)
    }

    @Test
    fun `too few beats gives no resting rate at all`() {
        val thin = Day.summarise(20_000, listOf(Reading.Heart(1, 44, 1_363, 0)), clock)
        // A percentile over a handful of beats is the smallest of them wearing a better name.
        assertNull(thin.restingBpm)
    }

    @Test
    fun `steps are summed rather than maxed`() {
        // These are counts since the last event, so reading the largest reports the busiest ten
        // minutes as the whole day.
        assertEquals(550, Day.summarise(20_000, night, clock).steps)
    }

    @Test
    fun `temperature is reported against your own normal`() {
        val day = Day.summarise(20_000, night, clock, baseline = 33.0)
        // 33.5 is not a fact anybody can use. Half a degree above your own median is the signal.
        assertEquals(0.5, day.tempDeviation!!, 0.001)
    }

    @Test
    fun `no baseline means no deviation, not a deviation from nothing`() {
        val day = Day.summarise(20_000, listOf(Reading.Temp(1, 33.4)), clock, baseline = null)
        assertNull(day.tempDeviation)
    }

    @Test
    fun `worn time comes from the on and off pair`() {
        assertEquals(480, Day.summarise(20_000, night, clock).wornMinutes)
    }

    @Test
    fun `a ring still on the finger counts to its last word, not to midnight`() {
        val open = Day.summarise(
            20_000,
            listOf(Reading.Wear(0, true), Reading.Heart(3_600, 50, 1_200, 0)),
            clock,
        )
        // A ring being worn now says nothing about the hours after the last thing it told us.
        assertEquals(0, open.wornMinutes)
    }

    @Test
    fun `undecoded frames are counted apart from the ones that read`() {
        val day = Day.summarise(20_000, night, clock)
        assertEquals(1, day.unread)
        assertEquals(night.size - 1, day.readings)
    }

    @Test
    fun `a baseline is the median of what it is given`() {
        assertEquals(33.2, Day.baseline(listOf(33.0, 33.4, 33.2))!!, 0.001)
        assertNull(Day.baseline(emptyList()))
    }
}
