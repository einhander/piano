package com.piano.sequencer.project

data class Track(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Track 1",
    var trackId: Int = 0,
    var volume: Float = 1.0f,
    var pan: Float = 0.5f,
    var muted: Boolean = false,
    var solo: Boolean = false,
    var isRecordArmed: Boolean = false,
    var clips: MutableList<Clip> = mutableListOf(),
    var program: Int = 0,
    var transpose: Int = 0,
    var velocityScale: Float = 1.0f
) {
    fun touch() {}
    fun withRecordArm(armed: Boolean): Track = copy(isRecordArmed = armed)
}