package com.piano.sequencer.midi

import com.piano.sequencer.EffectStateRestore
import org.junit.Assert.*
import org.junit.Test

class InstrumentIdentityPolicyTest {
    @Test fun absoluteMappingAndStableIdentityKeys() {
        assertEquals(0, resolveInstrumentIdentityChannel(MAIN_INSTRUMENT_SET_ID, 0, null))
        assertEquals(0, resolveInstrumentIdentityChannel(null, 0, 16))
        assertEquals(16, resolveInstrumentIdentityChannel("file-a", 0, 16))
        assertEquals(16, resolveInstrumentIdentityChannel("file-b", 0, 16))
        assertEquals(127, resolveInstrumentIdentityChannel("file-z", 15, 112))
        assertEquals(-1, resolveInstrumentIdentityChannel("file-z", 0, 128))
        assertEquals(-1, resolveInstrumentIdentityChannel("file-z", 16, 16))
        assertNotEquals(EffectStateRestore.instrumentIdentityRackKey(MAIN_INSTRUMENT_SET_ID, 0), EffectStateRestore.instrumentIdentityRackKey("file-a", 0))
        assertNotEquals(EffectStateRestore.instrumentIdentityParamKey(MAIN_INSTRUMENT_SET_ID, 0, "lsp.filter", 2), EffectStateRestore.instrumentIdentityParamKey("file-a", 0, "lsp.filter", 2))
        assertEquals("instrument_fx_v1_rack_main_0", EffectStateRestore.instrumentIdentityRackKey(MAIN_INSTRUMENT_SET_ID, 0))
        assertEquals("instrument_delay_v2_main_0_0", EffectStateRestore.instrumentIdentityDelayKey(MAIN_INSTRUMENT_SET_ID, 0, 0))
    }

    @Test fun newSetCapacityLeavesUnsupportedPersistedSetsInPlace() {
        val data = mutableMapOf<String?, Any?>()
        val prefs = Prefs(data)
        val store = FileInstrumentSetStore(prefs)
        repeat(7) { assertNotNull(store.create("s$it", emptyList())) }
        assertNull(store.create("eighth", emptyList()))
        assertEquals(7, store.all().size)
    }

    @Test fun programActivationNeverResetsRestoredIdentityAndRemovalResetsBeforeReuse() {
        val calls = mutableListOf<String>()
        var resetCount = 0
        val store = FileInstrumentSetStore(Prefs(mutableMapOf()))
        val old = store.create("old", emptyList())!!
        val initialActivation = applyFileProgramAssignments(old.programs) { ch, _ -> calls += "program:$ch"; true }
        assertTrue(initialActivation)
        assertEquals(0, resetCount) // restored FX survives first playback and program application
        calls.clear()
        assertTrue(applyFileProgramAssignments(List(16) { it }) { ch, _ -> calls += "program:$ch"; true })
        assertEquals(0, resetCount) // changed program list does not imply identity reset

        var deleted = false
        val failed = prepareFileSetRangeRemoval({ calls += "stop" }, { calls += "reset"; resetCount++; false })
        if (failed) store.remove(old.id).also { deleted = it }
        assertFalse(failed); assertFalse(deleted)
        assertNotNull(store.all().firstOrNull { it.id == old.id })

        calls.clear()
        val ready = prepareFileSetRangeRemoval({ calls += "stop" }, { calls += "reset"; resetCount++; true })
        assertTrue(ready)
        assertEquals(listOf("stop", "reset"), calls)
        assertTrue(store.remove(old.id))
        val replacement = store.create("replacement", emptyList())!!
        assertEquals(old.channelBase, replacement.channelBase)
        assertNotEquals(old.id, replacement.id)
        assertNotEquals(
            com.piano.sequencer.EffectStateRestore.instrumentIdentityRackKey(old.id, 0),
            com.piano.sequencer.EffectStateRestore.instrumentIdentityRackKey(replacement.id, 0)
        )
    }

    @Test fun legacyMigrationAndMissingFileKeysAreMainOnlyAndFailOff() {
        assertEquals(MAIN_INSTRUMENT_SET_ID, legacyInstrumentStateTargetSetId())
        assertTrue(legacyInstrumentMigrationNeeded(false))
        assertFalse(legacyInstrumentMigrationNeeded(true))
        assertFalse(restoredInstrumentEnabled(null))
        assertFalse(restoredInstrumentEnabled(false))
        assertTrue(restoredInstrumentEnabled(true))
    }

    private class Prefs(private val values: MutableMap<String?, Any?>) : android.content.SharedPreferences {
        override fun getAll() = values.filterKeys { it != null }.mapKeys { it.key!! }
        override fun getString(key: String, def: String?) = values[key] as? String ?: def
        override fun getStringSet(key: String, def: Set<String>?) = values[key] as? Set<String> ?: def
        override fun getInt(key: String, def: Int) = values[key] as? Int ?: def
        override fun getLong(key: String, def: Long) = values[key] as? Long ?: def
        override fun getFloat(key: String, def: Float) = values[key] as? Float ?: def
        override fun getBoolean(key: String, def: Boolean) = values[key] as? Boolean ?: def
        override fun contains(key: String) = values.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Ed()
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
        private inner class Ed : android.content.SharedPreferences.Editor {
            override fun putString(k: String?, v: String?) = apply { values[k] = v }.let { this }
            override fun putStringSet(k: String?, v: Set<String>?) = apply { values[k] = v }.let { this }
            override fun putInt(k: String?, v: Int) = apply { values[k] = v }.let { this }
            override fun putLong(k: String?, v: Long) = apply { values[k] = v }.let { this }
            override fun putFloat(k: String?, v: Float) = apply { values[k] = v }.let { this }
            override fun putBoolean(k: String?, v: Boolean) = apply { values[k] = v }.let { this }
            override fun remove(k: String?) = apply { values.remove(k) }.let { this }
            override fun clear() = apply { values.clear() }.let { this }
            override fun commit() = true
            override fun apply() {}
        }
    }
}
