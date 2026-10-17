package com.piano.sequencer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackedTaskExecutorTest {
    @Test fun idleWaitIncludesRunningTasksEvenWhenFastWorkerIsFree() {
        val pool = Executors.newFixedThreadPool(4)
        val tracked = TrackedTaskExecutor(pool)
        val started = CountDownLatch(3)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val callbackRan = AtomicBoolean(false)

        repeat(3) {
            tracked.execute {
                started.countDown()
                release.await()
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        tracked.execute { /* fast task occupies free worker and finishes */ }
        tracked.awaitIdle { callbackRan.set(true); drained.countDown() }

        // All queued fast work and idle markers could finish; the three active
        // accepted tasks must still prevent false quiescence.
        assertFalse(drained.await(100, TimeUnit.MILLISECONDS))
        assertFalse(callbackRan.get())
        release.countDown()
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertTrue(callbackRan.get())
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test fun nestedAcceptedTasksAreIncludedDuringDrain() {
        val pool = Executors.newSingleThreadExecutor()
        val tracked = TrackedTaskExecutor(pool)
        val childStarted = CountDownLatch(1)
        val releaseChild = CountDownLatch(1)
        val drained = CountDownLatch(1)
        tracked.execute {
            tracked.execute {
                childStarted.countDown()
                releaseChild.await()
            }
        }
        assertTrue(childStarted.await(2, TimeUnit.SECONDS))
        tracked.awaitIdle { drained.countDown() }
        assertFalse(drained.await(100, TimeUnit.MILLISECONDS))
        releaseChild.countDown()
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test fun lateExternalTaskIsCancelledWithoutThrowAndResumeReopensAdmission() {
        val pool = Executors.newSingleThreadExecutor()
        val tracked = TrackedTaskExecutor(pool)
        val drained = CountDownLatch(1)
        val ran = AtomicInteger()
        tracked.awaitIdle { drained.countDown() }
        assertTrue(drained.await(2, TimeUnit.SECONDS))

        assertFalse(tracked.execute { ran.incrementAndGet() })
        tracked.resume()
        assertTrue(tracked.execute { ran.incrementAndGet() })
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        assertTrue(ran.get() == 1)
    }
}
