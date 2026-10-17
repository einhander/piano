package com.piano.sequencer

import java.util.concurrent.Executor

/** Enqueue a non-destructive completion marker behind work already accepted by serial executor. */
object ExecutorDrain {
    fun enqueue(executor: Executor, complete: () -> Unit) = executor.execute(complete)
}
