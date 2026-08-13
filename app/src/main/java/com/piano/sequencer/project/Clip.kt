package com.piano.sequencer.project

import java.util.UUID

data class Clip(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Clip",
    var clipId: Int = 0,
    var trackId: String = "",
    var startTick: Int = 0,
    var lengthTicks: Int = 960,
    var events: MutableList<MidiEvent> = mutableListOf(),
    var audioFilePath: String? = null
) {
    data class MidiEvent(
        var tick: Int = 0,
        var status: Int = 0,
        var data1: Int = 0,
        var data2: Int = 0
    )

    fun touch() {}
}