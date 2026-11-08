package com.piano.sequencer.midi

/** Handler-owned transition state, deliberately independent of Android APIs. */
internal class UsbDiagnosticLifecycle {
    data class Event(
        val stage: String,
        val midi: Any?,
        val status: String,
        val durationMs: Long?,
        val followupGeneration: Long? = null
    )

    private var generation = 0L
    private var sequence = 0L
    private var current: Any? = null
    private var connectStart: Long? = null
    private var disconnectStart: Long? = null
    private var completedConnectDuration: Long? = null
    private var closed = false

    fun transition(stage: String, midi: Any?, elapsed: Long): Event? {
        if (closed) return null
        generation++
        val followup = generation
        return when (stage) {
            "connect-request" -> {
                current = midi; connectStart = elapsed; disconnectStart = null
                Event(stage, midi, "opening", null, followup)
            }
            "connected", "open-failed" -> {
                val duration = connectStart?.let { elapsed - it }
                completedConnectDuration = duration
                if (stage == "connected") current = midi
                else current = midi
                connectStart = null
                Event(stage, midi, if (stage == "connected") "open" else "failed", duration, followup)
            }
            "disconnect-request" -> {
                disconnectStart = elapsed
                Event(stage, current, "closing", null, followup)
            }
            "disconnected" -> {
                val duration = disconnectStart?.let { elapsed - it } ?: completedConnectDuration
                val previous = current
                current = null; connectStart = null; disconnectStart = null; completedConnectDuration = null
                Event(stage, midi ?: previous, "closed", duration, followup)
            }
            else -> Event(stage, midi ?: current, "unknown", null, followup)
        }
    }

    fun snapshot(elapsed: Long) = Event("snapshot", current, if (closed) "closed" else "current", null)
    fun isCurrent(token: Long) = !closed && generation == token
    fun currentMidi(): Any? = current
    fun nextSnapshotSequence() = ++sequence
    fun close(elapsed: Long): Event? {
        if (closed) return null
        generation++
        val duration = disconnectStart?.let { elapsed - it }
        closed = true
        return Event("post-close", current, "closed", duration ?: completedConnectDuration)
    }
}
