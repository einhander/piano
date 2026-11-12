package com.piano.sequencer.midi

/** Diagnostic selection policy. Normal operation always opens every output port. */
enum class DiagnosticPortMode(val selectedPortNumbers: Set<Int>?) {
    NONE(emptySet()),
    PORTS_0_2(setOf(0, 2)),
    ALL(null);

    fun selects(portNumber: Int): Boolean = selectedPortNumbers?.contains(portNumber) ?: true
}

data class MidiPortDiagnosticState(
    val portNumber: Int,
    val name: String?,
    val selected: Boolean,
    val opened: Boolean,
    val connected: Boolean,
    val outcome: String,
    val elapsedMs: Long,
)

data class MidiConnectionDiagnosticState(
    val deviceKey: String?,
    val deviceName: String?,
    val mode: DiagnosticPortMode,
    val deviceHandleOpen: Boolean,
    val connected: Boolean,
    val ports: List<MidiPortDiagnosticState>,
)
