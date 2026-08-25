package com.gios.brightoura.ui

/**
 * The names of the ring's event tags.
 *
 * Only for reading a screen — nothing branches on these. They come from the protocol notes that
 * this app's BLE layer was ported from, and an unnamed tag is shown as its number rather than
 * guessed at: the whole point of keeping raw frames is that a tag we cannot name today is still
 * there to be named later.
 */
object EventNames {

    fun of(tag: Int): String = NAMES[tag] ?: "Unknown"

    private val NAMES = mapOf(
        0x44 to "Heartbeat intervals",
        0x60 to "Heartbeat intervals",
        0x71 to "Heartbeat intervals, green",
        0x6e to "Heartbeat intervals, SpO2 channel",
        0x46 to "Skin temperature",
        0x69 to "Skin temperature, period",
        0x75 to "Skin temperature, sleep",
        0x47 to "Motion",
        0x6b to "Motion, period",
        0x72 to "Motion during sleep",
        0x64 to "Raw PPG",
        0x68 to "Raw PPG data",
        0x81 to "Raw PPG, cardiovascular",
        0x6f to "Blood oxygen",
        0x70 to "Blood oxygen, smoothed",
        0x77 to "Blood oxygen, raw",
        0x8b to "Blood oxygen, R and PI",
        0x5d to "Heart rate variability",
        0x62 to "On-demand measurement",
        0x49 to "Sleep summary",
        0x4c to "Sleep summary",
        0x4f to "Sleep summary",
        0x58 to "Sleep summary",
        0x4b to "Sleep stages",
        0x4e to "Sleep stages",
        0x5a to "Sleep stages",
        0x50 to "Activity and steps",
        0x51 to "Activity summary",
        0x52 to "Activity summary",
        0x7e to "Steps, detailed",
        0x7f to "Steps, detailed",
        0x73 to "Exercise heart rate",
        0x74 to "Exercise intensity",
        0x45 to "Worn or not",
        0x53 to "Worn or not",
        0x80 to "Heartbeat quality",
    )
}
