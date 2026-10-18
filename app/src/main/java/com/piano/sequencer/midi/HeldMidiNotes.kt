package com.piano.sequencer.midi

/** Physical keys, not sounding voices: sustain and sequencer playback do not count. */
class HeldMidiNotes {
    private val notes = mutableMapOf<String, MutableSet<Int>>()
    private val listeners = mutableSetOf<() -> Unit>()

    @Synchronized fun snapshot(): BooleanArray = BooleanArray(16) { channel ->
        notes.values.any { keys -> keys.any { it / 128 == channel } }
    }

    @Synchronized fun addListener(listener: () -> Unit) { listeners.add(listener) }
    @Synchronized fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    @Synchronized fun note(source: String, channel: Int, note: Int, velocity: Int) {
        if (channel !in 0..15 || note !in 0..127) return
        val keys = notes.getOrPut(source) { mutableSetOf() }
        val changed = if (velocity > 0) keys.add(channel * 128 + note) else keys.remove(channel * 128 + note)
        if (keys.isEmpty()) notes.remove(source)
        if (changed) notifyListeners()
    }

    @Synchronized fun clearChannel(source: String, channel: Int) {
        if (notes[source]?.removeAll { it / 128 == channel } == true) notifyListeners()
    }

    @Synchronized fun clearSource(source: String) {
        if (notes.remove(source) != null) notifyListeners()
    }

    @Synchronized fun clear() {
        if (notes.isNotEmpty()) { notes.clear(); notifyListeners() }
    }

    private fun notifyListeners() { listeners.toList().forEach { it() } }

    companion object { val process = HeldMidiNotes() }
}
