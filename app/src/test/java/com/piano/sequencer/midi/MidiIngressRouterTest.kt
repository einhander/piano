package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MidiIngressRouterTest {
    @Test fun rootReceiverReadsRegisteredPortPolicyAndDeactivationClearsIt() {
        val root = MidiInputReceiver()
        val usbPort1 = root.createPortReceiver(1, "smk-usb", smk37Usb = true)
        val usbPort2 = root.createPortReceiver(2, "smk-usb", smk37Usb = true)
        val blePort1 = root.createPortReceiver(1, "ble", smk37Usb = false)
        assertEquals(MidiIngressRouter.PortRoute.IGNORE, root.routeForPort("smk-usb|port=1"))
        assertEquals(MidiIngressRouter.PortRoute.CONTROL_ONLY, root.routeForPort("smk-usb|port=2"))
        assertEquals(MidiIngressRouter.PortRoute.NORMAL, root.routeForPort("ble|port=1"))
        assertEquals(MidiIngressRouter.PortRoute.NORMAL, root.routeForPort("smk-usb|port=0"))
        assertTrue(root.routeForPort("smk-usb|port=1") != MidiIngressRouter.PortRoute.NORMAL)
        assertFalse(root.routeForPort("ble|port=1") != MidiIngressRouter.PortRoute.NORMAL)
        usbPort1.deactivate()
        usbPort2.deactivate()
        blePort1.deactivate()
        assertEquals(MidiIngressRouter.PortRoute.NORMAL, root.routeForPort("smk-usb|port=1"))
    }

    @Test fun unconsumedNoteDispatches() {
        val events = mutableListOf<String>()
        val router = MidiIngressRouter({ _, _, _ -> false }, { _, _, _ -> false }, { s, n, v, c -> events += "$s:$n:$v:$c" })
        router.noteOn(2, 60, 99)
        assertEquals(listOf("144:60:99:2"), events)
    }

    @Test fun consumedNoteDoesNotDispatch() {
        val events = mutableListOf<Int>()
        val router = MidiIngressRouter({ _, _, _ -> true }, { _, _, _ -> true }, { _, _, _, _ -> events += 1 })
        router.noteOn(0, 60, 100)
        assertEquals(emptyList<Int>(), events)
    }

    @Test fun learnConsumptionRunsBeforeDispatch() {
        val order = mutableListOf<String>()
        val router = MidiIngressRouter({ _, _, _ -> order += "learn"; true }, { _, _, _ -> true }, { _, _, _, _ -> order += "dispatch" })
        router.noteOn(0, 60, 100)
        assertEquals(listOf("learn"), order)
    }

    @Test fun smk37UsbPortsAreScopedAndControlOnlyNeverDispatches() {
        val events = mutableListOf<String>()
        val triggerResults = mutableMapOf<String, Boolean>()
        val router = MidiIngressRouter(
            { _, n, _, s -> events += "on:$s:$n"; triggerResults[s] ?: false },
            { _, n, _, s -> events += "off:$s:$n"; triggerResults[s] ?: false },
            { status, n, _, _ -> events += "dispatch:$status:$n" },
            { _, n, _ -> events += "chord:$n" },
            { s ->
                when (s) {
                    "smk|port=0" -> MidiIngressRouter.PortRoute.NORMAL
                    "smk|port=1" -> MidiIngressRouter.PortRoute.IGNORE
                    "smk|port=2" -> MidiIngressRouter.PortRoute.CONTROL_ONLY
                    else -> MidiIngressRouter.PortRoute.NORMAL
                }
            }
        )
        router.noteOn(0, 60, 100, "smk|port=0")
        router.noteOn(0, 60, 100, "smk|port=1")
        router.noteOn(0, 36, 127, "smk|port=2")
        triggerResults["smk|port=2"] = true
        router.noteOff(0, 36, 0, "smk|port=2")
        router.noteOn(0, 60, 100, "ble|port=0")
        router.noteOn(0, 60, 100, "other|port=1")
        assertEquals(
            listOf("chord:60", "on:smk|port=0:60", "dispatch:144:60", "on:smk|port=2:36", "off:smk|port=2:36", "chord:60", "on:ble|port=0:60", "dispatch:144:60", "chord:60", "on:other|port=1:60", "dispatch:144:60"),
            events
        )
    }
}
