package com.piano.sequencer.midi

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class FileInstrumentSetStoreLifecycleTest {
    private val data = mutableMapOf<String?, Any?>()
    private val prefs = MidiFileMappingTestPrefs(data)

    @Test fun orphanPurgeKeepsReferencedSharedOrMissingIdentifiers() {
        val store = FileInstrumentSetStore(prefs)
        val orphan = store.create("orphan", emptyList())!!
        val referenced = store.create("referenced", emptyList())!!
        assertEquals(1, store.removeUnreferenced(setOf(referenced.id, "missing-but-referenced")))
        assertNull(store.all().firstOrNull { it.id == orphan.id })
        assertNotNull(store.all().firstOrNull { it.id == referenced.id })
    }

    @Test fun exclusiveSetReusedAtCapacityWithoutMainPreferenceWrites() {
        prefs.edit().putInt("chan_prog_0", 0x1234).commit()
        val store = FileInstrumentSetStore(prefs)
        val old = store.create("old", emptyList())!!
        repeat(14) { store.create("other$it", emptyList())!! }
        val cell = SequencerCell(1, filePath = "/new.mid")
        val replaced = store.replaceForCell(cell, old.id, emptySet(), List(16) { 0x010203 }, "new")!!
        assertEquals(old.id, replaced.instrumentSetId)
        assertEquals(old.channelBase, store.all().first { it.id == old.id }.channelBase)
        assertEquals(0x010203, store.all().first { it.id == old.id }.programs[0])
        assertEquals(0x1234, prefs.getInt("chan_prog_0", 0))
    }

    @Test fun sharedReplacementAllocatesNewSetAndRetainsOld() {
        val store = FileInstrumentSetStore(prefs)
        val old = store.create("shared", emptyList())!!
        val replacement = store.replaceForCell(SequencerCell(1, filePath = "/new.mid"), old.id,
            setOf(old.id), List(16) { 3 }, "new")!!
        assertNotEquals(old.id, replacement.instrumentSetId)
        assertNotNull(store.all().firstOrNull { it.id == old.id })
    }

    private class MidiFileMappingTestPrefs(private val values: MutableMap<String?, Any?>) : SharedPreferences {
        override fun getAll() = values.filterKeys { it != null }.mapKeys { it.key!! }
        override fun getString(key: String, def: String?) = values[key] as? String ?: def
        override fun getStringSet(key: String, def: Set<String>?) = values[key] as? Set<String> ?: def
        override fun getInt(key: String, def: Int) = values[key] as? Int ?: def
        override fun getLong(key: String, def: Long) = values[key] as? Long ?: def
        override fun getFloat(key: String, def: Float) = values[key] as? Float ?: def
        override fun getBoolean(key: String, def: Boolean) = values[key] as? Boolean ?: def
        override fun contains(key: String) = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        private inner class Editor : SharedPreferences.Editor {
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
