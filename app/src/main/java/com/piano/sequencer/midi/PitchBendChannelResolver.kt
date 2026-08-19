package com.piano.sequencer.midi

/**
 * Resolves which channels pitch bend / mod / breath messages should be sent
 * to, so they follow the channel the keyboard is currently using.
 *
 * If a note has been played on a channel, that channel is used. Otherwise the
 * channels in [fallbackMask] (bit i set = channel i active) are used, in
 * ascending order. An empty mask falls back to channel 1 (index 0) so the
 * message is never a silent no-op.
 *
 * Callers run on binder threads (MidiReceiver.onSend); the [lastNoteChannel]
 * input is read via a @Volatile field in MainActivity. The resolver itself is
 * a pure function with no internal state.
 */
object PitchBendChannelResolver {

    fun resolve(lastNoteChannel: Int, fallbackMask: Int): IntArray {
        if (lastNoteChannel in 0..15) {
            return intArrayOf(lastNoteChannel)
        }
        val channels = (0..15).filter { (fallbackMask shr it) and 1 == 1 }
        return if (channels.isEmpty()) intArrayOf(0) else channels.toIntArray()
    }
}