package com.piano.sequencer.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurableFailureTrackerTest {
    @Test fun unrelatedSuccessDoesNotEraseFailureButSameOperationRetryDoes() {
        val failures = DurableFailureTracker()
        val saveError = IllegalStateException("save")
        failures.failed("project-save", saveError)
        failures.succeeded("resource-import")
        assertEquals(saveError, failures.firstFailure())
        failures.succeeded("project-save")
        assertNull(failures.firstFailure())
    }
}
