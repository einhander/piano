package com.piano.sequencer.project

import java.util.UUID

data class Project(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Untitled",
    var bpm: Double = 120.0,
    var ppq: Int = 960,
    var numerator: Int = 4,
    var denominator: Int = 4,
    var tracks: MutableList<Track> = mutableListOf(),
    var scenes: MutableList<Scene> = mutableListOf(),
    var masterGain: Float = 1.0f,
    var polyphony: Int = 64,
    var soundFontPath: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun touch() { updatedAt = System.currentTimeMillis() }
}