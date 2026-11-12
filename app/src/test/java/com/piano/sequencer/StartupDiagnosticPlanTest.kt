package com.piano.sequencer

import com.piano.sequencer.midi.DiagnosticPortMode
import org.junit.Assert.*
import org.junit.Test

class StartupDiagnosticPlanTest {
    @org.junit.Test fun pendingMidiOnlyPlanBlocksAutomationAndAudioActions() {
        val pending = StartupDiagnosticState(StartupDiagnosticPlan(StartupOrder.MIDI_ONLY, com.piano.sequencer.midi.DiagnosticPortMode.ALL))
        assertTrue(StartupDiagnosticPolicy.blocksMidiAutomation(pending))
        assertFalse(StartupDiagnosticPolicy.allowsAudioAction(pending))
    }

    @org.junit.Test fun missingPlannedMidiTargetDoesNotFallBack() {
        val devices = listOf("other", "also-other")
        assertNull(StartupDiagnosticPolicy.selectMidiTarget("planned", devices) { it })
        assertEquals("other", StartupDiagnosticPolicy.selectMidiTarget(null, devices) { it })
    }
    @Test fun orderPolicyCoversAllStartupShapes() {
        fun p(order: StartupOrder) = StartupDiagnosticPlan(order, DiagnosticPortMode.PORTS_0_2)
        assertTrue(StartupDiagnosticPolicy.startAudioInitially(p(StartupOrder.AUDIO_THEN_MIDI)))
        assertTrue(StartupDiagnosticPolicy.onAudioCompleted(p(StartupOrder.AUDIO_THEN_MIDI)))
        assertTrue(StartupDiagnosticPolicy.startMidiInitially(p(StartupOrder.MIDI_THEN_AUDIO)))
        assertTrue(StartupDiagnosticPolicy.onMidiCompleted(p(StartupOrder.MIDI_THEN_AUDIO)))
        assertTrue(StartupDiagnosticPolicy.startMidiInitially(p(StartupOrder.MIDI_ONLY)))
        assertFalse(StartupDiagnosticPolicy.startAudioInitially(p(StartupOrder.MIDI_ONLY)))
        assertTrue(StartupDiagnosticPolicy.startAudioInitially(p(StartupOrder.AUDIO_ONLY)))
        assertFalse(StartupDiagnosticPolicy.startMidiInitially(p(StartupOrder.AUDIO_ONLY)))
    }

    @Test fun missingMidiDeviceStillCompletesMIDIThenAudioPolicy() {
        val plan = StartupDiagnosticPlan(StartupOrder.MIDI_THEN_AUDIO, DiagnosticPortMode.NONE)
        var midiComplete = false
        var audioStarted = false
        fun midi(targetAvailable: Boolean, completed: () -> Unit) { if (!targetAvailable) completed() }
        midi(false) { midiComplete = true }
        if (midiComplete && StartupDiagnosticPolicy.onMidiCompleted(plan)) audioStarted = true
        assertTrue(audioStarted)
    }

    @Test fun startupClaimAllowsOnlyFirstSuccessfulPlanAndNormalBoot() {
        val plan = StartupDiagnosticPlan(StartupOrder.MIDI_ONLY, DiagnosticPortMode.NONE)
        assertTrue(StartupDiagnosticPolicy.startupClaim(plan, true, false))
        assertFalse(StartupDiagnosticPolicy.startupClaim(plan, true, true))
        assertFalse(StartupDiagnosticPolicy.startupClaim(plan, false, false))
        assertFalse(StartupDiagnosticPolicy.startupClaim(null, true, false))
    }

    @Test fun phaseCopiesRetainStablePlanIdentityForMIDIThenAudio() {
        val plan = StartupDiagnosticPlan(StartupOrder.MIDI_THEN_AUDIO, DiagnosticPortMode.NONE)
        val updated = StartupDiagnosticState(plan).copy(phase = "midi-connected")
        assertTrue(StartupDiagnosticPolicy.isSamePlan(updated, plan))
        assertTrue(StartupDiagnosticPolicy.onMidiCompleted(updated.plan))
    }
}
