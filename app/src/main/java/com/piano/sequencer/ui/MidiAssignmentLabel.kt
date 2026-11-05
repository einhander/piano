package com.piano.sequencer.ui

/** Compact display only; persisted source identity remains untouched. */
fun midiAssignmentLabel(source: String?, channel: Int?): String {
    val parts = source?.split('|').orEmpty()
    val device = parts.take(2).filter { it.isNotBlank() }.distinct().joinToString(" ")
        .takeIf { parts.size >= 3 && it.isNotBlank() } ?: "Any device".takeIf { source == null } ?: "MIDI device"
    val port = parts.lastOrNull { it.startsWith("port=") }?.substringAfter('=')?.toIntOrNull()
    return "MIDI assigned · $device · Port ${port ?: "Any"} · Ch ${channel?.plus(1) ?: "Any"}"
}
