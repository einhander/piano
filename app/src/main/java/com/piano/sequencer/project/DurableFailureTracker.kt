package com.piano.sequencer.project

/** Keeps independent durable-operation failures until that operation succeeds. */
class DurableFailureTracker {
    private val failures = mutableMapOf<String, Throwable>()
    fun failed(operation: String, error: Throwable) { failures[operation] = error }
    fun succeeded(operation: String) { failures.remove(operation) }
    fun firstFailure(): Throwable? = failures.values.firstOrNull()
}
