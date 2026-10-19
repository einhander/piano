package com.piano.sequencer.midi

import java.util.LinkedHashMap

/** Tracks one active service reference per owner; repeated attach is idempotent. */
internal class ServiceAttachmentRegistry<T : Any> {
    private val owners = LinkedHashMap<Any, T>()

    @Synchronized
    fun attach(owner: Any, service: T) {
        owners.remove(owner)
        owners[owner] = service
    }

    @Synchronized
    fun detach(owner: Any, service: T? = null) {
        val attached = owners[owner] ?: return
        if (service == null || attached === service) owners.remove(owner)
    }

    @Synchronized
    fun active(): T? = owners.values.lastOrNull()
}
