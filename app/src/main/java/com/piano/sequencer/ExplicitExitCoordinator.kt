package com.piano.sequencer

/** Small asynchronous exit state machine; a failed durability stage never advances to process kill. */
class ExplicitExitCoordinator(
    private val drainDurableWork: ((Result<Unit>) -> Unit) -> Unit,
    private val stopService: ((Result<Unit>) -> Unit) -> Unit,
    private val terminate: () -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private var started = false
    private var finished = false

    @Synchronized fun begin() {
        if (started || finished) return
        started = true
        drainDurableWork { durable ->
            durable.fold(
                onSuccess = {
                    stopService { stopped ->
                        stopped.fold(
                            onSuccess = { synchronized(this) { if (!finished) { finished = true; terminate() } } },
                            onFailure = onFailure
                        )
                    }
                },
                onFailure = onFailure
            )
        }
    }
}
