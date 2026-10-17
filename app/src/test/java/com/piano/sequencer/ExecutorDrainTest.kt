package com.piano.sequencer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutorDrainTest {
    @Test fun barrierRunsAfterAcceptedTasksWithoutShuttingExecutorDown() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val barrier = CountDownLatch(1)
        val events = mutableListOf<String>()
        executor.execute {
            started.countDown()
            release.await()
            events += "task"
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        ExecutorDrain.enqueue(executor) { events += "barrier"; barrier.countDown() }
        assertEquals(emptyList<String>(), events)
        release.countDown()
        assertTrue(barrier.await(2, TimeUnit.SECONDS))
        executor.execute { events += "still-open" }
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(listOf("task", "barrier", "still-open"), events)
    }
}
