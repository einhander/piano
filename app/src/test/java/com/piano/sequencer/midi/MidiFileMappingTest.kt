package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Tests for [MidiFileMappingStore] and [NoteToggleStateMachine].
 *
 * Runs on the JVM (no Robolectric) because the store's SharedPreferences
 * source is injectable — we use a stub in-memory implementation.
 */
class MidiFileMappingTest {

    // ── In-memory SharedPreferences stub ──

    private class InMemorySharedPreferences : android.content.SharedPreferences {
        private val data = mutableMapOf<String?, Any?>()
        private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, Any?> = data.filterKeys { it != null }.mapKeys { it.key!! }.toMap()
        override fun getString(key: String, default: String?): String? = data[key] as? String ?: default
        override fun getStringSet(key: String, default: Set<String>?): Set<String>? = data[key] as? Set<String> ?: default
        override fun getInt(key: String, default: Int): Int = (data[key] as? Number)?.toInt() ?: default
        override fun getLong(key: String, default: Long): Long = (data[key] as? Number)?.toLong() ?: default
        override fun getFloat(key: String, default: Float): Float = (data[key] as? Number)?.toFloat() ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = data[key] as? Boolean ?: default
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = InMemoryEditor(this)
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(l)
        }
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(l)
        }

        private inner class InMemoryEditor(private val parent: InMemorySharedPreferences) : android.content.SharedPreferences.Editor {
            private val edits = mutableMapOf<String?, Any?>()
            private var removedKeys = mutableSetOf<String?>()

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                edits[key] = value; return this
            }
            override fun putStringSet(key: String?, values: Set<String>?): android.content.SharedPreferences.Editor {
                edits[key] = values; return this
            }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor {
                edits[key] = value; return this
            }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor {
                edits[key] = value; return this
            }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor {
                edits[key] = value; return this
            }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor {
                edits[key] = value; return this
            }
            override fun remove(key: String?): android.content.SharedPreferences.Editor {
                removedKeys.add(key); return this
            }
            override fun clear(): android.content.SharedPreferences.Editor {
                edits["__CLEAR__"] = true; return this
            }
            override fun commit(): Boolean {
                if (edits.containsKey("__CLEAR__")) {
                    parent.data.clear()
                    removedKeys.clear()
                } else {
                    for ((k, v) in edits) parent.data[k] = v
                    for (k in removedKeys) parent.data.remove(k)
                }
                for (l in listeners) l.onSharedPreferenceChanged(parent, null)
                edits.clear()
                removedKeys.clear()
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    // ── Helper ──

    private fun createStore(): MidiFileMappingStore {
        return MidiFileMappingStore(InMemorySharedPreferences())
    }

    // ── Store tests ──

    @Test
    fun emptyStoreLoadsAsEmpty() {
        val store = createStore()
        assertTrue(store.all().isEmpty())
        assertNull(store.get(1))
    }

    @Test
    fun roundTripSaveAndReload() {
        val store = createStore()
        val c1 = SequencerCell(id = 1, note = 60, filePath = "/path/to/file1.mid", loop = true, tempo = 100.0, channel = 3)
        val c2 = SequencerCell(id = 2, note = 48, filePath = "/path/to/file2.mid", loop = false, tempo = 140.0, channel = -1)

        store.set(c1)
        store.set(c2)

        val all = store.all()
        assertEquals(2, all.size)
        assertEquals(c1, store.get(1))
        assertEquals(c2, store.get(2))
    }

    @Test
    fun roundTripAcrossStores() {
        val sharedData = mutableMapOf<String?, Any?>()
        val sharedPrefs = object : android.content.SharedPreferences {
            private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
            override fun getAll(): Map<String, Any?> = sharedData.filterKeys { it != null }.mapKeys { it.key!! }.toMap()
            override fun getString(key: String, default: String?): String? = sharedData[key] as? String ?: default
            override fun getStringSet(key: String, default: Set<String>?): Set<String>? = sharedData[key] as? Set<String> ?: default
            override fun getInt(key: String, default: Int): Int = (sharedData[key] as? Number)?.toInt() ?: default
            override fun getLong(key: String, default: Long): Long = (sharedData[key] as? Number)?.toLong() ?: default
            override fun getFloat(key: String, default: Float): Float = (sharedData[key] as? Number)?.toFloat() ?: default
            override fun getBoolean(key: String, default: Boolean): Boolean = sharedData[key] as? Boolean ?: default
            override fun contains(key: String): Boolean = sharedData.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = SharedEditor(this)
            override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
            override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }
            private inner class SharedEditor(private val p: android.content.SharedPreferences) : android.content.SharedPreferences.Editor {
                private val edits = mutableMapOf<String?, Any?>()
                private var removedKeys = mutableSetOf<String?>()
                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putStringSet(key: String?, values: Set<String>?): android.content.SharedPreferences.Editor { edits[key] = values; return this }
                override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun remove(key: String?): android.content.SharedPreferences.Editor { removedKeys.add(key); return this }
                override fun clear(): android.content.SharedPreferences.Editor { edits["__CLEAR__"] = true; return this }
                override fun commit(): Boolean {
                    if (edits.containsKey("__CLEAR__")) { sharedData.clear(); removedKeys.clear() }
                    else { for ((k, v) in edits) sharedData[k] = v; for (k in removedKeys) sharedData.remove(k) }
                    for (l in listeners) l.onSharedPreferenceChanged(p, null)
                    edits.clear(); removedKeys.clear(); return true
                }
                override fun apply() { commit() }
            }
        }

        val writer = MidiFileMappingStore(sharedPrefs)
        val c = SequencerCell(id = 1, note = 48, filePath = "/music/test.mid", loop = true, tempo = 90.0, channel = 5)
        writer.set(c)

        val reader = MidiFileMappingStore(sharedPrefs)
        val loaded = reader.get(1)!!
        assertEquals(1, loaded.id)
        assertEquals(c.note, loaded.note)
        assertEquals(c.filePath, loaded.filePath)
        assertEquals(c.loop, loaded.loop)
        assertEquals(c.tempo, loaded.tempo, 0.001)
        assertEquals(c.channel, loaded.channel)
    }

    @Test
    fun setReplacesSameId() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = 60, filePath = "/old.mid", loop = true, tempo = 100.0, channel = 0))
        store.set(SequencerCell(id = 1, note = 60, filePath = "/new.mid", loop = false, tempo = 140.0, channel = 5))

        val all = store.all()
        assertEquals(1, all.size)
        assertEquals("/new.mid", all[0].filePath)
        assertEquals(140.0, all[0].tempo, 0.001)
        assertEquals(5, all[0].channel)
    }

    @Test
    fun removeById() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = 36, filePath = "/path.mid"))
        assertEquals(1, store.all().size)

        store.remove(1)
        assertTrue(store.all().isEmpty())
        assertNull(store.get(1))
    }

    @Test
    fun clearRemovesAll() {
        val store = createStore()
        store.set(SequencerCell(id = 1, filePath = "/a.mid"))
        store.set(SequencerCell(id = 2, filePath = "/b.mid"))
        store.set(SequencerCell(id = 3, filePath = "/c.mid"))
        assertEquals(3, store.all().size)

        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun nextIdEmptyReturnsOne() {
        val store = createStore()
        assertEquals(1, store.nextId())
    }

    @Test
    fun nextIdAfterSetFiveReturnsSix() {
        val store = createStore()
        store.set(SequencerCell(id = 5, filePath = "/x.mid"))
        assertEquals(6, store.nextId())
    }

    @Test
    fun findByNoteReturnsCell() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = 60, filePath = "/test.mid"))
        val found = store.findByNote(60)
        assertNotNull(found)
        assertEquals(60, found!!.note)
    }

    @Test
    fun findByNoteNullWhenAbsent() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = 60, filePath = "/test.mid"))
        assertNull(store.findByNote(72))
    }

    @Test
    fun findByNoteNeverMatchesNegative() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = 60, filePath = "/test.mid"))
        assertNull(store.findByNote(-1))
        assertNull(store.findByNote(-5))
    }

    @Test
    fun legacyMapJsonMigratesToCells() {
        val sharedData = mutableMapOf<String?, Any?>()
        val oldJson = """{"48":{"note":48,"filePath":"/music/test.mid","loop":true,"tempo":90.0,"channel":5}}"""
        sharedData["midi_file_map"] = oldJson

        val sharedPrefs = object : android.content.SharedPreferences {
            private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
            override fun getAll(): Map<String, Any?> = sharedData.filterKeys { it != null }.mapKeys { it.key!! }.toMap()
            override fun getString(key: String, default: String?): String? = sharedData[key] as? String ?: default
            override fun getStringSet(key: String, default: Set<String>?): Set<String>? = sharedData[key] as? Set<String> ?: default
            override fun getInt(key: String, default: Int): Int = (sharedData[key] as? Number)?.toInt() ?: default
            override fun getLong(key: String, default: Long): Long = (sharedData[key] as? Number)?.toLong() ?: default
            override fun getFloat(key: String, default: Float): Float = (sharedData[key] as? Number)?.toFloat() ?: default
            override fun getBoolean(key: String, default: Boolean): Boolean = sharedData[key] as? Boolean ?: default
            override fun contains(key: String): Boolean = sharedData.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = SharedEditor(this)
            override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
            override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }
            private inner class SharedEditor(private val p: android.content.SharedPreferences) : android.content.SharedPreferences.Editor {
                private val edits = mutableMapOf<String?, Any?>()
                private var removedKeys = mutableSetOf<String?>()
                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putStringSet(key: String?, values: Set<String>?): android.content.SharedPreferences.Editor { edits[key] = values; return this }
                override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun remove(key: String?): android.content.SharedPreferences.Editor { removedKeys.add(key); return this }
                override fun clear(): android.content.SharedPreferences.Editor { edits["__CLEAR__"] = true; return this }
                override fun commit(): Boolean {
                    if (edits.containsKey("__CLEAR__")) { sharedData.clear(); removedKeys.clear() }
                    else { for ((k, v) in edits) sharedData[k] = v; for (k in removedKeys) sharedData.remove(k) }
                    for (l in listeners) l.onSharedPreferenceChanged(p, null)
                    edits.clear(); removedKeys.clear(); return true
                }
                override fun apply() { commit() }
            }
        }

        val store = MidiFileMappingStore(sharedPrefs)
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(1, all[0].id)
        assertEquals(48, all[0].note)
        assertEquals("/music/test.mid", all[0].filePath)
        assertTrue(all[0].loop)
        assertEquals(90.0, all[0].tempo, 0.001)
        assertEquals(5, all[0].channel)
        assertTrue(store.needsLegacyBackfill())
    }

    @Test
    fun legacyJsonWithoutChannelDefaultsToMinusOne() {
        val sharedData = mutableMapOf<String?, Any?>()
        val oldJson = """{"48":{"note":48,"filePath":"/t.mid","loop":false,"tempo":120.0}}"""
        sharedData["midi_file_map"] = oldJson

        val sharedPrefs = object : android.content.SharedPreferences {
            private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
            override fun getAll(): Map<String, Any?> = sharedData.filterKeys { it != null }.mapKeys { it.key!! }.toMap()
            override fun getString(key: String, default: String?): String? = sharedData[key] as? String ?: default
            override fun getStringSet(key: String, default: Set<String>?): Set<String>? = sharedData[key] as? Set<String> ?: default
            override fun getInt(key: String, default: Int): Int = (sharedData[key] as? Number)?.toInt() ?: default
            override fun getLong(key: String, default: Long): Long = (sharedData[key] as? Number)?.toLong() ?: default
            override fun getFloat(key: String, default: Float): Float = (sharedData[key] as? Number)?.toFloat() ?: default
            override fun getBoolean(key: String, default: Boolean): Boolean = sharedData[key] as? Boolean ?: default
            override fun contains(key: String): Boolean = sharedData.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = SharedEditor(this)
            override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
            override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }
            private inner class SharedEditor(private val p: android.content.SharedPreferences) : android.content.SharedPreferences.Editor {
                private val edits = mutableMapOf<String?, Any?>()
                private var removedKeys = mutableSetOf<String?>()
                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putStringSet(key: String?, values: Set<String>?): android.content.SharedPreferences.Editor { edits[key] = values; return this }
                override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun remove(key: String?): android.content.SharedPreferences.Editor { removedKeys.add(key); return this }
                override fun clear(): android.content.SharedPreferences.Editor { edits["__CLEAR__"] = true; return this }
                override fun commit(): Boolean {
                    if (edits.containsKey("__CLEAR__")) { sharedData.clear(); removedKeys.clear() }
                    else { for ((k, v) in edits) sharedData[k] = v; for (k in removedKeys) sharedData.remove(k) }
                    for (l in listeners) l.onSharedPreferenceChanged(p, null)
                    edits.clear(); removedKeys.clear(); return true
                }
                override fun apply() { commit() }
            }
        }

        val store = MidiFileMappingStore(sharedPrefs)
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(-1, all[0].channel)
    }

    @Test
    fun newFormatJsonNoBackfill() {
        val sharedData = mutableMapOf<String?, Any?>()
        val newJson = """[{"id":1,"note":48,"filePath":"/t.mid","loop":true,"tempo":90.0,"channel":5}]"""
        sharedData["midi_file_map"] = newJson

        val sharedPrefs = object : android.content.SharedPreferences {
            private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
            override fun getAll(): Map<String, Any?> = sharedData.filterKeys { it != null }.mapKeys { it.key!! }.toMap()
            override fun getString(key: String, default: String?): String? = sharedData[key] as? String ?: default
            override fun getStringSet(key: String, default: Set<String>?): Set<String>? = sharedData[key] as? Set<String> ?: default
            override fun getInt(key: String, default: Int): Int = (sharedData[key] as? Number)?.toInt() ?: default
            override fun getLong(key: String, default: Long): Long = (sharedData[key] as? Number)?.toLong() ?: default
            override fun getFloat(key: String, default: Float): Float = (sharedData[key] as? Number)?.toFloat() ?: default
            override fun getBoolean(key: String, default: Boolean): Boolean = sharedData[key] as? Boolean ?: default
            override fun contains(key: String): Boolean = sharedData.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = SharedEditor(this)
            override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
            override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }
            private inner class SharedEditor(private val p: android.content.SharedPreferences) : android.content.SharedPreferences.Editor {
                private val edits = mutableMapOf<String?, Any?>()
                private var removedKeys = mutableSetOf<String?>()
                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putStringSet(key: String?, values: Set<String>?): android.content.SharedPreferences.Editor { edits[key] = values; return this }
                override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun remove(key: String?): android.content.SharedPreferences.Editor { removedKeys.add(key); return this }
                override fun clear(): android.content.SharedPreferences.Editor { edits["__CLEAR__"] = true; return this }
                override fun commit(): Boolean {
                    if (edits.containsKey("__CLEAR__")) { sharedData.clear(); removedKeys.clear() }
                    else { for ((k, v) in edits) sharedData[k] = v; for (k in removedKeys) sharedData.remove(k) }
                    for (l in listeners) l.onSharedPreferenceChanged(p, null)
                    edits.clear(); removedKeys.clear(); return true
                }
                override fun apply() { commit() }
            }
        }

        val store = MidiFileMappingStore(sharedPrefs)
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(1, all[0].id)
        assertEquals(48, all[0].note)
        assertEquals("/t.mid", all[0].filePath)
        assertTrue(all[0].loop)
        assertEquals(90.0, all[0].tempo, 0.001)
        assertEquals(5, all[0].channel)
        assertFalse(store.needsLegacyBackfill())
    }

    @Test
    fun twoLegacyEntriesGetIdsOneAndTwoInAscendingNoteOrder() {
        val sharedData = mutableMapOf<String?, Any?>()
        val oldJson = """{"60":{"note":60,"filePath":"/c5.mid","loop":false,"tempo":140.0},"48":{"note":48,"filePath":"/c4.mid","loop":true,"tempo":100.0}}"""
        sharedData["midi_file_map"] = oldJson

        val sharedPrefs = object : android.content.SharedPreferences {
            private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
            override fun getAll(): Map<String, Any?> = sharedData.filterKeys { it != null }.mapKeys { it.key!! }.toMap()
            override fun getString(key: String, default: String?): String? = sharedData[key] as? String ?: default
            override fun getStringSet(key: String, default: Set<String>?): Set<String>? = sharedData[key] as? Set<String> ?: default
            override fun getInt(key: String, default: Int): Int = (sharedData[key] as? Number)?.toInt() ?: default
            override fun getLong(key: String, default: Long): Long = (sharedData[key] as? Number)?.toLong() ?: default
            override fun getFloat(key: String, default: Float): Float = (sharedData[key] as? Number)?.toFloat() ?: default
            override fun getBoolean(key: String, default: Boolean): Boolean = sharedData[key] as? Boolean ?: default
            override fun contains(key: String): Boolean = sharedData.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = SharedEditor(this)
            override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
            override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }
            private inner class SharedEditor(private val p: android.content.SharedPreferences) : android.content.SharedPreferences.Editor {
                private val edits = mutableMapOf<String?, Any?>()
                private var removedKeys = mutableSetOf<String?>()
                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putStringSet(key: String?, values: Set<String>?): android.content.SharedPreferences.Editor { edits[key] = values; return this }
                override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun remove(key: String?): android.content.SharedPreferences.Editor { removedKeys.add(key); return this }
                override fun clear(): android.content.SharedPreferences.Editor { edits["__CLEAR__"] = true; return this }
                override fun commit(): Boolean {
                    if (edits.containsKey("__CLEAR__")) { sharedData.clear(); removedKeys.clear() }
                    else { for ((k, v) in edits) sharedData[k] = v; for (k in removedKeys) sharedData.remove(k) }
                    for (l in listeners) l.onSharedPreferenceChanged(p, null)
                    edits.clear(); removedKeys.clear(); return true
                }
                override fun apply() { commit() }
            }
        }

        val store = MidiFileMappingStore(sharedPrefs)
        val all = store.all()
        assertEquals(2, all.size)
        assertEquals(1, all[0].id)
        assertEquals(48, all[0].note)
        assertEquals("/c4.mid", all[0].filePath)
        assertEquals(2, all[1].id)
        assertEquals(60, all[1].note)
        assertEquals("/c5.mid", all[1].filePath)
    }

    @Test
    fun markBackfillDoneClearsFlag() {
        val sharedData = mutableMapOf<String?, Any?>()
        val oldJson = """{"48":{"note":48,"filePath":"/t.mid","loop":true,"tempo":90.0,"channel":5}}"""
        sharedData["midi_file_map"] = oldJson

        val sharedPrefs = object : android.content.SharedPreferences {
            private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
            override fun getAll(): Map<String, Any?> = sharedData.filterKeys { it != null }.mapKeys { it.key!! }.toMap()
            override fun getString(key: String, default: String?): String? = sharedData[key] as? String ?: default
            override fun getStringSet(key: String, default: Set<String>?): Set<String>? = sharedData[key] as? Set<String> ?: default
            override fun getInt(key: String, default: Int): Int = (sharedData[key] as? Number)?.toInt() ?: default
            override fun getLong(key: String, default: Long): Long = (sharedData[key] as? Number)?.toLong() ?: default
            override fun getFloat(key: String, default: Float): Float = (sharedData[key] as? Number)?.toFloat() ?: default
            override fun getBoolean(key: String, default: Boolean): Boolean = sharedData[key] as? Boolean ?: default
            override fun contains(key: String): Boolean = sharedData.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = SharedEditor(this)
            override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
            override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }
            private inner class SharedEditor(private val p: android.content.SharedPreferences) : android.content.SharedPreferences.Editor {
                private val edits = mutableMapOf<String?, Any?>()
                private var removedKeys = mutableSetOf<String?>()
                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putStringSet(key: String?, values: Set<String>?): android.content.SharedPreferences.Editor { edits[key] = values; return this }
                override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { edits[key] = value; return this }
                override fun remove(key: String?): android.content.SharedPreferences.Editor { removedKeys.add(key); return this }
                override fun clear(): android.content.SharedPreferences.Editor { edits["__CLEAR__"] = true; return this }
                override fun commit(): Boolean {
                    if (edits.containsKey("__CLEAR__")) { sharedData.clear(); removedKeys.clear() }
                    else { for ((k, v) in edits) sharedData[k] = v; for (k in removedKeys) sharedData.remove(k) }
                    for (l in listeners) l.onSharedPreferenceChanged(p, null)
                    edits.clear(); removedKeys.clear(); return true
                }
                override fun apply() { commit() }
            }
        }

        val store = MidiFileMappingStore(sharedPrefs)
        assertTrue(store.needsLegacyBackfill())
        store.markBackfillDone()
        assertFalse(store.needsLegacyBackfill())
    }

    // ── NoteToggleStateMachine tests (M3: tri-state Result enum) ──

    @Test
    fun freshPressTogglesOn() {
        val sm = NoteToggleStateMachine()
        val result = sm.noteOn(60)
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, result)
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun keyRepeatReturnsIgnored() {
        val sm = NoteToggleStateMachine()
        // First noteOn → TOGGLE_ON
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60))
        assertTrue(sm.isPlaying(60))
        // Second noteOn without noteOff → IGNORED
        assertEquals(NoteToggleStateMachine.Result.IGNORED, sm.noteOn(60))
        // Still playing
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun noteOffDoesNotStop() {
        val sm = NoteToggleStateMachine()
        sm.noteOn(60)
        assertTrue(sm.isPlaying(60))
        sm.noteOff(60)
        // Still playing (loop keeps playing after release)
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun pressReleasePressStops() {
        val sm = NoteToggleStateMachine()
        // First press → TOGGLE_ON
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60))
        assertTrue(sm.isPlaying(60))
        // Release
        sm.noteOff(60)
        // Second press → TOGGLE_OFF (was playing, now toggles off)
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_OFF, sm.noteOn(60))
        assertTrue(!sm.isPlaying(60))
    }

    @Test
    fun multipleNotesIndependent() {
        val sm = NoteToggleStateMachine()
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60))
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(61))
        assertTrue(sm.isPlaying(60))
        assertTrue(sm.isPlaying(61))
        sm.noteOff(60)
        assertTrue(sm.isPlaying(60)) // still playing (loop)
        assertTrue(sm.isPlaying(61))
    }

    @Test
    fun resetClearsAll() {
        val sm = NoteToggleStateMachine()
        sm.noteOn(60)
        sm.noteOn(61)
        sm.reset()
        assertTrue(!sm.isPlaying(60))
        assertTrue(!sm.isPlaying(61))
    }

    @Test
    fun noteOffWithoutPriorNoteOnIsSafe() {
        val sm = NoteToggleStateMachine()
        // Note-off before any note-on should not crash
        sm.noteOff(60)
        // Now press → should still toggle ON (first event ever)
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60))
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun stopPlayingResetsState() {
        val sm = NoteToggleStateMachine()
        sm.noteOn(60)
        assertTrue(sm.isPlaying(60))
        sm.stopPlaying(60)
        assertTrue(!sm.isPlaying(60))
        // Next press should be TOGGLE_ON again (state reset)
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60))
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun concurrentAccessSafe() {
        val sm = NoteToggleStateMachine()
        val executor = Executors.newFixedThreadPool(4)
        val count = 100
        for (i in 0 until count) {
            executor.submit {
                sm.noteOn(60)
            }
        }
        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        // All concurrent noteOns after the first should be IGNORED
        // The first one (whichever thread wins) should be TOGGLE_ON
        assertTrue(sm.isPlaying(60))
    }

    // ── MidiFileLearnState tests (M1: real state machine) ──

    @Test
    fun learnStateStartSetsLearning() {
        MidiFileLearnState.cancel() // ensure clean state
        assertEquals(MidiFileLearnState.State.IDLE, MidiFileLearnState.getState())
        var capturedNote = -1
        MidiFileLearnState.startLearning { capturedNote = it }
        assertEquals(MidiFileLearnState.State.LEARNING, MidiFileLearnState.getState())
    }

    @Test
    fun captureNoteResetsToIdle() {
        MidiFileLearnState.cancel()
        var capturedNote = -1
        MidiFileLearnState.startLearning { capturedNote = it }
        MidiFileLearnState.captureNote(60)
        assertEquals(60, capturedNote)
        assertEquals(MidiFileLearnState.State.IDLE, MidiFileLearnState.getState())
    }

    @Test
    fun captureNoteWhileIdleDoesNothing() {
        MidiFileLearnState.cancel()
        var capturedNote = -1
        MidiFileLearnState.startLearning { capturedNote = it }
        // Capture while learning
        MidiFileLearnState.captureNote(60)
        // Now in IDLE — capture again should do nothing
        MidiFileLearnState.captureNote(61)
        assertEquals(60, capturedNote) // should still be 60, not 61
    }

    @Test
    fun cancelResetsToIdle() {
        MidiFileLearnState.cancel()
        MidiFileLearnState.startLearning { }
        assertEquals(MidiFileLearnState.State.LEARNING, MidiFileLearnState.getState())
        MidiFileLearnState.cancel()
        assertEquals(MidiFileLearnState.State.IDLE, MidiFileLearnState.getState())
    }

    @Test
    fun reLearnCancelsPrevious() {
        MidiFileLearnState.cancel()
        var captured1 = -1
        var captured2 = -1
        MidiFileLearnState.startLearning { captured1 = it }
        // Start a new learn — should cancel the previous callback
        MidiFileLearnState.startLearning { captured2 = it }
        // Capture with the new callback
        MidiFileLearnState.captureNote(60)
        assertEquals(-1, captured1) // first callback should not have been invoked
        assertEquals(60, captured2) // second callback should have been invoked
    }

    // ── noteToName tests ──

    @Test
    fun noteToNameBasic() {
        assertEquals("C-1", noteToName(0))
        assertEquals("C4", noteToName(60))
        assertEquals("B4", noteToName(71))
        assertEquals("C5", noteToName(72))
        assertEquals("B-1", noteToName(11))
        assertEquals("G9", noteToName(127))
    }

    @Test
    fun noteToNameSharps() {
        assertEquals("C#-1", noteToName(1))
        assertEquals("F#4", noteToName(66))
        assertEquals("G#4", noteToName(68))
    }
}