package com.piano.sequencer.midi

import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceAttachmentRegistryTest {
    @Test
    fun oneDetachRetainsSharedServiceUntilLastOwnerDetaches() {
        val registry = ServiceAttachmentRegistry<Any>()
        val mainActivity = Any()
        val effectsActivity = Any()
        val service = Any()

        registry.attach(mainActivity, service)
        registry.attach(effectsActivity, service)
        registry.attach(mainActivity, service) // reconnect/repeated callback is idempotent

        registry.detach(effectsActivity, service)
        assertSame(service, registry.active()) // dispatch path still has service

        registry.detach(mainActivity, service)
        assertNull(registry.active())
    }

    @Test
    fun staleDetachDoesNotRemoveOwnersReconnectedToAnotherService() {
        val registry = ServiceAttachmentRegistry<Any>()
        val owner = Any()
        val oldService = Any()
        val newService = Any()

        registry.attach(owner, oldService)
        registry.attach(owner, newService)
        registry.detach(owner, oldService)

        assertSame(newService, registry.active())
    }

    @Test
    fun mostRecentlyAttachedOwnerSelectsActiveServiceAndFallbackIsLatestRemaining() {
        val registry = ServiceAttachmentRegistry<Any>()
        val firstOwner = Any()
        val secondOwner = Any()
        val firstService = Any()
        val secondService = Any()

        registry.attach(firstOwner, firstService)
        registry.attach(secondOwner, secondService)
        assertSame(secondService, registry.active())

        registry.detach(secondOwner, secondService)
        assertSame(firstService, registry.active())
    }
}
