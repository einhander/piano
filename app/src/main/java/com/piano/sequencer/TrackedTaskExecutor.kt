package com.piano.sequencer

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Tracks accepted executor work (including child tasks submitted by running tasks) to true quiescence. */
class TrackedTaskExecutor(private val delegate: Executor) {
    private val lock = Any()
    private val taskDepth = ThreadLocal.withInitial { 0 }
    private var acceptingExternal = true
    private var draining = false
    private var outstanding = 0
    private val idleCallbacks = mutableListOf<() -> Unit>()

    fun execute(task: () -> Unit): Boolean {
        val accepted = synchronized(lock) {
            if (!acceptingExternal && taskDepth.get() == 0) false
            else { outstanding++; true }
        }
        if (!accepted) return false
        try {
            delegate.execute {
                taskDepth.set(taskDepth.get() + 1)
                try { task() } finally {
                    taskDepth.set(taskDepth.get() - 1)
                    completeTask()
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            completeTask()
            return false
        } catch (error: Throwable) {
            completeTask()
            throw error
        }
    }

    /** Close external admission and callback only after all accepted/nested work completes. */
    fun awaitIdle(onIdle: () -> Unit) {
        val callNow = synchronized(lock) {
            acceptingExternal = false
            draining = true
            if (outstanding == 0) true else {
                idleCallbacks += onIdle
                false
            }
        }
        if (callNow) onIdle()
    }

    /** Reopen after an aborted, non-destructive exit attempt. */
    fun resume() = synchronized(lock) {
        acceptingExternal = true
        draining = false
    }

    private fun completeTask() {
        val callbacks = synchronized(lock) {
            outstanding--
            check(outstanding >= 0)
            if (draining && outstanding == 0) idleCallbacks.toList().also { idleCallbacks.clear() }
            else emptyList()
        }
        callbacks.forEach { it() }
    }
}
