package com.piano.sequencer.midi

/**
 * Resolves the full target-channel set for a keyboard message when the
 * multi-channel mode is active.
 *
 * [baseTargets] is what the message would have gone to without the mode. When
 * [enabled] is false, the input is returned unchanged. When enabled, channels
 * in [selectedMask] are added, deduplicated, and sorted ascending.
 *
 * Callers run on binder threads (MidiReceiver.onSend); this is a pure function
 * with no internal state.
 */
object MultiChannelResolver {
    fun resolve(baseTargets: IntArray, selectedMask: Int, enabled: Boolean): IntArray {
        if (!enabled) return baseTargets
        val mask = selectedMask and 0xFFFF
        if (mask == 0) return baseTargets
        var baseBits = 0
        for (c in baseTargets) if (c in 0..15) baseBits = baseBits or (1 shl c)
        if (mask and baseBits == mask) return baseTargets
        val all = mask or baseBits
        return (0..15).filter { (all shr it) and 1 == 1 }.toIntArray()
    }
}
