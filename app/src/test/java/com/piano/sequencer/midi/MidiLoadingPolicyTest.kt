package com.piano.sequencer.midi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiLoadingPolicyTest {
    @Test fun defaultEagerPolicyAllowsStartupResumeAndHotplug() {
        val policy = MidiLoadingPolicy(lazyLoading = false)

        assertTrue(policy.allowAutomaticLoading())
    }

    @Test fun lazyPolicyDefersAutomaticDiscoveryUntilSynthReady() {
        val policy = MidiLoadingPolicy(lazyLoading = true)

        assertFalse(policy.allowAutomaticLoading()) // startup / resume / hotplug
        policy.setSynthReady(true)
        assertTrue(policy.allowAutomaticLoading()) // startup / resume / hotplug
    }

    @Test fun chooserCannotBypassLazyReadinessGate() {
        val policy = MidiLoadingPolicy(lazyLoading = true)

        assertFalse(policy.chooserCanLoadExplicitly())
        assertFalse(policy.allowAutomaticLoading())
        policy.setSynthReady(true)
        assertTrue(policy.chooserCanLoadExplicitly())
        assertTrue(policy.allowAutomaticLoading())
    }

    @Test fun updatedSettingAppliesAtResumeWithoutResettingChooserActivation() {
        val policy = MidiLoadingPolicy(lazyLoading = false)
        assertTrue(policy.allowAutomaticLoading())
        policy.updateLazyLoading(true)
        assertFalse(policy.allowAutomaticLoading())
        policy.setSynthReady(true)
        policy.updateLazyLoading(false)
        assertTrue(policy.allowAutomaticLoading())
        policy.updateLazyLoading(true)
        assertTrue(policy.allowAutomaticLoading())
        policy.setSynthReady(false)
        assertFalse(policy.allowAutomaticLoading())
    }
}
