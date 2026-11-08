package com.piano.sequencer.midi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiLoadingPolicyTest {
    @Test fun defaultEagerPolicyAllowsStartupResumeAndHotplug() {
        val policy = MidiLoadingPolicy(lazyLoading = false)

        assertTrue(policy.allowAutomaticLoading())
    }

    @Test fun lazyPolicyDefersAutomaticDiscoveryUntilChooserIsOpened() {
        val policy = MidiLoadingPolicy(lazyLoading = true)

        assertFalse(policy.allowAutomaticLoading()) // startup / resume / hotplug
        policy.onChooserOpened()
        assertTrue(policy.allowAutomaticLoading()) // chooser and subsequent reconnects
    }

    @Test fun lazyPolicyNeverDisablesExplicitChooser() {
        val policy = MidiLoadingPolicy(lazyLoading = true)

        policy.onChooserOpened()
        assertTrue(policy.allowAutomaticLoading())
        policy.onChooserOpened() // reopening chooser remains valid
        assertTrue(policy.allowAutomaticLoading())
    }

    @Test fun updatedSettingAppliesAtResumeWithoutResettingChooserActivation() {
        val policy = MidiLoadingPolicy(lazyLoading = false)
        assertTrue(policy.allowAutomaticLoading())
        policy.updateLazyLoading(true)
        assertFalse(policy.allowAutomaticLoading())
        policy.onChooserOpened()
        policy.updateLazyLoading(false)
        assertTrue(policy.allowAutomaticLoading())
        policy.updateLazyLoading(true)
        assertTrue(policy.allowAutomaticLoading())
    }
}
