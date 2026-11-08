package com.piano.sequencer.midi

import org.junit.Assert.*
import org.junit.Test

class UsbDiagnosticLifecycleTest {
    @Test fun immediateTransitionsPreserveFifoAndHonestDurations() {
        val state = UsbDiagnosticLifecycle()
        val request = state.transition("connect-request", "keyboard", 100)!!
        val connected = state.transition("connected", "keyboard", 145)!!
        val disconnect = state.transition("disconnect-request", null, 200)!!
        val closed = state.transition("disconnected", null, 240)!!
        assertEquals(listOf("connect-request", "connected", "disconnect-request", "disconnected"), listOf(request, connected, disconnect, closed).map { it.stage })
        assertEquals(45L, connected.durationMs)
        assertEquals(40L, closed.durationMs)
    }

    @Test fun onlyNewestTransitionFollowupsRemainValidAndCloseIsFinalOnce() {
        val state = UsbDiagnosticLifecycle()
        val stale = state.transition("connect-request", "old", 1)!!.followupGeneration!!
        val current = state.transition("connect-request", "new", 2)!!.followupGeneration!!
        assertFalse(state.isCurrent(stale))
        assertTrue(state.isCurrent(current))
        assertNotNull(state.close(3))
        assertNull(state.close(4))
        assertFalse(state.isCurrent(current))
        assertNull(state.transition("connected", "late", 5))
    }
}
