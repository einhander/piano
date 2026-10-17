package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplicitExitCoordinatorTest {
    @Test fun idempotentAndWaitsForDurableAndServiceStages() {
        val events = mutableListOf<String>()
        lateinit var durable: (Result<Unit>) -> Unit
        lateinit var stopped: (Result<Unit>) -> Unit
        val exit = ExplicitExitCoordinator(
            { durable = it },
            { stopped = it },
            { events += "kill" },
            { events += "failure" }
        )
        exit.begin(); exit.begin()
        assertEquals(emptyList<String>(), events)
        durable(Result.success(Unit))
        assertEquals(emptyList<String>(), events)
        stopped(Result.success(Unit))
        assertEquals(listOf("kill"), events)
    }

    @Test fun failuresNeverKillProcess() {
        val events = mutableListOf<String>()
        val exit = ExplicitExitCoordinator(
            { it(Result.failure(IllegalStateException("flush"))) },
            { events += "stop" },
            { events += "kill" },
            { events += "failure" }
        )
        exit.begin()
        assertEquals(listOf("failure"), events)
    }
}
