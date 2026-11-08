package com.piano.sequencer.midi

/** Controls automatic MIDI discovery; lazy mode waits for a fully ready synth. */
internal class MidiLoadingPolicy(lazyLoading: Boolean) {
    private var lazyLoading = lazyLoading
    private var synthReady = false

    fun allowAutomaticLoading(): Boolean = !lazyLoading || synthReady

    fun chooserCanLoadExplicitly(): Boolean = allowAutomaticLoading()

    fun setSynthReady(ready: Boolean) { synthReady = ready }

    fun updateLazyLoading(enabled: Boolean) {
        lazyLoading = enabled
    }
}
