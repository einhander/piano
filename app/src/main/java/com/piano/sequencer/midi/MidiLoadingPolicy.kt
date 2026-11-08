package com.piano.sequencer.midi

/** Tracks whether automatic MIDI discovery is permitted for the current screen visit. */
internal class MidiLoadingPolicy(lazyLoading: Boolean) {
    private var lazyLoading = lazyLoading
    private var chooserOpened = false

    fun allowAutomaticLoading(): Boolean = !lazyLoading || chooserOpened

    fun onChooserOpened() {
        chooserOpened = true
    }

    fun updateLazyLoading(enabled: Boolean) {
        lazyLoading = enabled
    }
}
