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

    /**
     * Shared in-memory prefs stub (B4 tests). Unlike the inline anonymous stubs
     * above, the backing map is exposed so two store instances can share it
     * (round-trip tests) or be pre-seeded (backward-compat tests).
     */
    private class SharedPrefsStub(private val data: MutableMap<String?, Any?>) : android.content.SharedPreferences {
        private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, Any?> = data.filterKeys { it != null }.mapKeys { it.key!! }.toMap()
        override fun getString(key: String, default: String?): String? = data[key] as? String ?: default
        override fun getStringSet(key: String, default: Set<String>?): Set<String>? = data[key] as? Set<String> ?: default
        override fun getInt(key: String, default: Int): Int = (data[key] as? Number)?.toInt() ?: default
        override fun getLong(key: String, default: Long): Long = (data[key] as? Number)?.toLong() ?: default
        override fun getFloat(key: String, default: Float): Float = (data[key] as? Number)?.toFloat() ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = data[key] as? Boolean ?: default
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }

        private inner class Editor(private val parent: SharedPrefsStub) : android.content.SharedPreferences.Editor {
            private val edits = mutableMapOf<String?, Any?>()
            private val removedKeys = mutableSetOf<String?>()
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { edits[key] = value; return this }
            override fun putStringSet(key: String?, values: Set<String>?): android.content.SharedPreferences.Editor { edits[key] = values; return this }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { edits[key] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { edits[key] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { edits[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { edits[key] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { removedKeys.add(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { edits["__CLEAR__"] = true; return this }
            override fun commit(): Boolean {
                if (edits.containsKey("__CLEAR__")) { parent.data.clear(); removedKeys.clear() }
                else { for ((k, v) in edits) parent.data[k] = v; for (k in removedKeys) parent.data.remove(k) }
                for (l in listeners) l.onSharedPreferenceChanged(parent, null)
                edits.clear(); removedKeys.clear(); return true
            }
            override fun apply() { commit() }
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
        val result = sm.noteOn(60, loop = true)
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, result)
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun keyRepeatReturnsIgnored() {
        val sm = NoteToggleStateMachine()
        // First noteOn → TOGGLE_ON
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = true))
        assertTrue(sm.isPlaying(60))
        // Second noteOn without noteOff → IGNORED
        assertEquals(NoteToggleStateMachine.Result.IGNORED, sm.noteOn(60, loop = true))
        // Still playing
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun noteOffDoesNotStop() {
        val sm = NoteToggleStateMachine()
        sm.noteOn(60, loop = true)
        assertTrue(sm.isPlaying(60))
        sm.noteOff(60)
        // Still playing (loop keeps playing after release)
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun pressReleasePressStops() {
        val sm = NoteToggleStateMachine()
        // First press → TOGGLE_ON
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = true))
        assertTrue(sm.isPlaying(60))
        // Release
        sm.noteOff(60)
        // Second press → TOGGLE_OFF (was playing, now toggles off)
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_OFF, sm.noteOn(60, loop = true))
        assertTrue(!sm.isPlaying(60))
    }

    @Test
    fun multipleNotesIndependent() {
        val sm = NoteToggleStateMachine()
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = true))
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(61, loop = true))
        assertTrue(sm.isPlaying(60))
        assertTrue(sm.isPlaying(61))
        sm.noteOff(60)
        assertTrue(sm.isPlaying(60)) // still playing (loop)
        assertTrue(sm.isPlaying(61))
    }

    @Test
    fun resetClearsAll() {
        val sm = NoteToggleStateMachine()
        sm.noteOn(60, loop = true)
        sm.noteOn(61, loop = true)
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
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = true))
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun stopPlayingResetsState() {
        val sm = NoteToggleStateMachine()
        sm.noteOn(60, loop = true)
        assertTrue(sm.isPlaying(60))
        sm.stopPlaying(60)
        assertTrue(!sm.isPlaying(60))
        // Next press should be TOGGLE_ON again (state reset)
        assertEquals(NoteToggleStateMachine.Result.TOGGLE_ON, sm.noteOn(60, loop = true))
        assertTrue(sm.isPlaying(60))
    }

    @Test
    fun concurrentAccessSafe() {
        val sm = NoteToggleStateMachine()
        val executor = Executors.newFixedThreadPool(4)
        val count = 100
        for (i in 0 until count) {
            executor.submit {
                sm.noteOn(60, loop = true)
            }
        }
        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        // All concurrent noteOns after the first should be IGNORED
        // The first one (whichever thread wins) should be TOGGLE_ON
        assertTrue(sm.isPlaying(60))
    }

    // ── MidiFileLearnState tests (M1: real state machine; B4: LearnedEvent) ──

    @Test
    fun learnStateStartSetsLearning() {
        MidiFileLearnState.cancel() // ensure clean state
        assertEquals(MidiFileLearnState.State.IDLE, MidiFileLearnState.getState())
        MidiFileLearnState.startLearning { }
        assertEquals(MidiFileLearnState.State.LEARNING, MidiFileLearnState.getState())
    }

    @Test
    fun captureNoteResetsToIdle() {
        MidiFileLearnState.cancel()
        var captured: LearnedEvent? = null
        MidiFileLearnState.startLearning { captured = it }
        MidiFileLearnState.captureNote(60)
        assertEquals(LearnedEvent.Note(60), captured)
        assertEquals(MidiFileLearnState.State.IDLE, MidiFileLearnState.getState())
    }

    @Test
    fun captureNoteWhileIdleDoesNothing() {
        MidiFileLearnState.cancel()
        var captured: LearnedEvent? = null
        MidiFileLearnState.startLearning { captured = it }
        // Capture while learning
        MidiFileLearnState.captureNote(60)
        // Now in IDLE — capture again should do nothing
        MidiFileLearnState.captureNote(61)
        assertEquals(LearnedEvent.Note(60), captured) // should still be 60, not 61
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
        var captured1: LearnedEvent? = null
        var captured2: LearnedEvent? = null
        MidiFileLearnState.startLearning { captured1 = it }
        // Start a new learn — should cancel the previous callback
        MidiFileLearnState.startLearning { captured2 = it }
        // Capture with the new callback
        MidiFileLearnState.captureNote(60)
        assertNull(captured1) // first callback should not have been invoked
        assertEquals(LearnedEvent.Note(60), captured2) // second callback should have been invoked
    }

    // ── B4: first event of ANY type wins (note / CC / pitch bend) ──

    @Test
    fun firstCCWinsOverLaterNote() {
        MidiFileLearnState.cancel()
        var captured: LearnedEvent? = null
        MidiFileLearnState.startLearning { captured = it }
        MidiFileLearnState.captureCC(7)
        assertEquals(LearnedEvent.CC(7), captured)
        assertEquals(MidiFileLearnState.State.IDLE, MidiFileLearnState.getState())
        // Learn already exited — a later note must be ignored
        MidiFileLearnState.captureNote(60)
        assertEquals(LearnedEvent.CC(7), captured)
    }

    @Test
    fun firstPitchBendWinsOverLaterCC() {
        MidiFileLearnState.cancel()
        var captured: LearnedEvent? = null
        MidiFileLearnState.startLearning { captured = it }
        MidiFileLearnState.capturePitchBend()
        assertEquals(LearnedEvent.PitchBend, captured)
        // Learn already exited — a later CC must be ignored
        MidiFileLearnState.captureCC(7)
        assertEquals(LearnedEvent.PitchBend, captured)
    }

    @Test
    fun captureCCWhileIdleDoesNothing() {
        MidiFileLearnState.cancel()
        var captured: LearnedEvent? = null
        MidiFileLearnState.startLearning { captured = it }
        MidiFileLearnState.captureNote(60)
        // Now in IDLE — a CC capture should do nothing
        MidiFileLearnState.captureCC(7)
        assertEquals(LearnedEvent.Note(60), captured)
    }

    @Test
    fun triggerKeyOfEncodesAllTypes() {
        assertEquals(60, triggerKeyOf(LearnedEvent.Note(60)))
        assertEquals(135, triggerKeyOf(LearnedEvent.CC(7)))
        assertEquals(256, triggerKeyOf(LearnedEvent.PitchBend))
    }

    // ── B4: JSON backward compatibility (old entries lack triggerType/ccNumber) ──

    @Test
    fun oldJsonWithoutTriggerFieldsDeserializesAsNote() {
        val data = mutableMapOf<String?, Any?>()
        // Entry saved by an older app version: no triggerType / ccNumber
        data["midi_file_map"] = """[{"id":1,"note":48,"filePath":"/t.mid","loop":true,"tempo":90.0,"channel":5}]"""
        val store = MidiFileMappingStore(SharedPrefsStub(data))
        val c = store.get(1)!!
        assertEquals(48, c.note)
        assertEquals("NOTE", c.triggerType)
        assertNull(c.ccNumber)
        assertTrue(c.hasTrigger())
        assertEquals(48, c.triggerKey())
    }

    @Test
    fun ccCellRoundTripPreservesFields() {
        val data = mutableMapOf<String?, Any?>()
        val writer = MidiFileMappingStore(SharedPrefsStub(data))
        val c = SequencerCell(
            id = 1, note = -1, filePath = "/cc.mid", loop = true, tempo = 100.0,
            channel = 3, triggerType = "CC", ccNumber = 7
        )
        writer.set(c)
        val reader = MidiFileMappingStore(SharedPrefsStub(data))
        val loaded = reader.get(1)!!
        assertEquals(c, loaded)
        assertEquals("CC", loaded.triggerType)
        assertEquals(7, loaded.ccNumber)
        assertEquals(-1, loaded.note)
        assertEquals(135, loaded.triggerKey())
    }

    @Test
    fun pitchBendCellRoundTripPreservesFields() {
        val data = mutableMapOf<String?, Any?>()
        val writer = MidiFileMappingStore(SharedPrefsStub(data))
        val c = SequencerCell(
            id = 2, note = -1, filePath = "/pb.mid", loop = false, tempo = 140.0,
            channel = -1, triggerType = "PITCH_BEND", ccNumber = null
        )
        writer.set(c)
        val reader = MidiFileMappingStore(SharedPrefsStub(data))
        val loaded = reader.get(2)!!
        assertEquals(c, loaded)
        assertEquals("PITCH_BEND", loaded.triggerType)
        assertNull(loaded.ccNumber)
        assertEquals(-1, loaded.note)
        assertEquals(256, loaded.triggerKey())
    }

    // ── B4: trigger lookups ──

    @Test
    fun findByCCReturnsCell() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = -1, filePath = "/a.mid", triggerType = "CC", ccNumber = 7))
        store.set(SequencerCell(id = 2, note = 60, filePath = "/b.mid"))
        val found = store.findByCC(7)
        assertNotNull(found)
        assertEquals(1, found!!.id)
    }

    @Test
    fun findByCCNullWhenAbsent() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = -1, filePath = "/a.mid", triggerType = "CC", ccNumber = 7))
        assertNull(store.findByCC(10))
    }

    @Test
    fun findByCCRejectsOutOfRange() {
        val store = createStore()
        assertNull(store.findByCC(-1))
        assertNull(store.findByCC(128))
    }

    @Test
    fun findByPitchBendReturnsCell() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = -1, filePath = "/a.mid", triggerType = "PITCH_BEND"))
        store.set(SequencerCell(id = 2, note = 60, filePath = "/b.mid"))
        val found = store.findByPitchBend()
        assertNotNull(found)
        assertEquals(1, found!!.id)
    }

    @Test
    fun findByPitchBendNullWhenAbsent() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = 60, filePath = "/b.mid"))
        assertNull(store.findByPitchBend())
    }

    @Test
    fun findByNoteIgnoresCcAndPitchBendCells() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = -1, filePath = "/a.mid", triggerType = "CC", ccNumber = 7))
        store.set(SequencerCell(id = 2, note = -1, filePath = "/b.mid", triggerType = "PITCH_BEND"))
        assertNull(store.findByNote(7))  // CC 7 is not note 7 (disjoint key spaces)
        assertNull(store.findByNote(-1))
    }

    @Test
    fun findByTriggerDispatchesByType() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = 60, filePath = "/n.mid"))
        store.set(SequencerCell(id = 2, note = -1, filePath = "/c.mid", triggerType = "CC", ccNumber = 7))
        store.set(SequencerCell(id = 3, note = -1, filePath = "/p.mid", triggerType = "PITCH_BEND"))
        assertEquals(1, store.findByTrigger("NOTE", 60)!!.id)
        assertEquals(2, store.findByTrigger("CC", 7)!!.id)
        assertEquals(3, store.findByTrigger("PITCH_BEND", 0)!!.id)
    }

    // ── B4: trigger uniqueness (one trigger → one cell) ──

    @Test
    fun learningCCRemovesSameCCFromOtherCells() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = -1, filePath = "/a.mid", triggerType = "CC", ccNumber = 1))
        store.set(SequencerCell(id = 2, note = -1, filePath = "/b.mid", triggerType = "CC", ccNumber = 2))
        store.set(SequencerCell(id = 3, filePath = "/c.mid"))
        store.applyLearnedTrigger(3, LearnedEvent.CC(1))
        // Cell 1 loses CC 1 (uniqueness); cell 2 keeps CC 2; cell 3 gains CC 1
        val c1 = store.get(1)!!
        assertEquals("NOTE", c1.triggerType)
        assertEquals(-1, c1.note)
        assertNull(c1.ccNumber)
        assertEquals("CC", store.get(2)!!.triggerType)
        assertEquals(2, store.get(2)!!.ccNumber)
        val c3 = store.get(3)!!
        assertEquals("CC", c3.triggerType)
        assertEquals(1, c3.ccNumber)
        assertEquals(-1, c3.note)
    }

    @Test
    fun learningNoteRemovesSameNoteFromOtherCells() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = 60, filePath = "/a.mid"))
        store.set(SequencerCell(id = 2, filePath = "/b.mid"))
        store.applyLearnedTrigger(2, LearnedEvent.Note(60))
        assertEquals(-1, store.get(1)!!.note)
        val c2 = store.get(2)!!
        assertEquals(60, c2.note)
        assertEquals("NOTE", c2.triggerType)
        assertNull(c2.ccNumber)
    }

    @Test
    fun learningPitchBendRemovesOtherPitchBendCells() {
        val store = createStore()
        store.set(SequencerCell(id = 1, note = -1, filePath = "/a.mid", triggerType = "PITCH_BEND"))
        store.set(SequencerCell(id = 2, filePath = "/b.mid"))
        store.applyLearnedTrigger(2, LearnedEvent.PitchBend)
        val c1 = store.get(1)!!
        assertEquals("NOTE", c1.triggerType)
        assertEquals(-1, c1.note)
        val c2 = store.get(2)!!
        assertEquals("PITCH_BEND", c2.triggerType)
        assertNull(c2.ccNumber)
        assertEquals(-1, c2.note)
    }

    @Test
    fun learningCCDoesNotTouchNoteCellWithSameNumber() {
        // Key spaces are disjoint: note 7 (0-127) vs CC 7 (128+7)
        val store = createStore()
        store.set(SequencerCell(id = 1, note = 7, filePath = "/a.mid"))
        store.set(SequencerCell(id = 2, filePath = "/b.mid"))
        store.applyLearnedTrigger(2, LearnedEvent.CC(7))
        assertEquals(7, store.get(1)!!.note) // untouched
        assertEquals("CC", store.get(2)!!.triggerType)
    }

    @Test
    fun applyLearnedTriggerReturnsNullForMissingCell() {
        val store = createStore()
        assertNull(store.applyLearnedTrigger(99, LearnedEvent.Note(60)))
    }

    // ── B4: ContinuousPressDetector (time-based idle detection, threshold = 50ms) ──
    // Press = value change AFTER A STABLE VALUE (≥ threshold since last event);
    // while the value keeps changing (still moving) = key repeat; same-value
    // retransmit = repeat. `now` is passed in (ms) — no clock inside the detector.

    @Test
    fun pressDetectorFirstChangeIsPress() {
        val d = ContinuousPressDetector(128)
        assertTrue(d.isPress(7, 50, 1000))   // first change (from sentinel) = press
        assertFalse(d.isPress(7, 50, 1010))  // same-value retransmit = repeat
        assertTrue(d.isPress(7, 51, 1200))   // change after idle (200ms > 50ms) = press
    }

    @Test
    fun pressDetectorMovingWheelIsRepeat() {
        val d = ContinuousPressDetector(128)
        assertTrue(d.isPress(7, 50, 1000))   // first = press
        assertFalse(d.isPress(7, 51, 1020))  // 20ms later — wheel still moving = repeat
        assertFalse(d.isPress(7, 52, 1040))  // still moving = repeat
        assertFalse(d.isPress(7, 53, 1060))  // still moving = repeat
        assertTrue(d.isPress(7, 54, 1300))   // idle 240ms, then change = press
    }

    @Test
    fun pressDetectorSameValueAfterIdleIsRepeat() {
        val d = ContinuousPressDetector(128)
        assertTrue(d.isPress(7, 50, 1000))
        // Idle 500ms, but the value never changed → repeat
        assertFalse(d.isPress(7, 50, 1500))
    }

    @Test
    fun pressDetectorSwitchCcsPressOnEachChange() {
        val d = ContinuousPressDetector(128)
        assertTrue(d.isPress(7, 0, 1000))     // switch-like CC
        assertTrue(d.isPress(7, 127, 1300))   // 300ms apart = press
        assertTrue(d.isPress(7, 0, 1600))     // back to 0 after idle = press
    }

    @Test
    fun pressDetectorReturningToOldValueIsPressAfterIdle() {
        val d = ContinuousPressDetector(128)
        assertTrue(d.isPress(7, 50, 1000))
        assertTrue(d.isPress(7, 51, 1300))    // idle, change = press
        assertTrue(d.isPress(7, 50, 1600))    // back to a previously seen value after idle = press
    }

    @Test
    fun pressDetectorIndependentPerIndex() {
        val d = ContinuousPressDetector(128)
        assertTrue(d.isPress(0, 10, 1000))
        assertTrue(d.isPress(1, 10, 1010))    // different index, same value, 10ms later = press
        assertFalse(d.isPress(0, 10, 1020))   // same value = repeat
    }

    @Test
    fun pressDetectorResetClearsValueAndTime() {
        val d = ContinuousPressDetector(128)
        assertTrue(d.isPress(3, 42, 1000))
        d.reset(3)
        // After reset, lastValue is the sentinel — the same value is a press again,
        // even immediately (the time is cleared too).
        assertTrue(d.isPress(3, 42, 1010))
    }

    @Test
    fun pressDetectorCustomThreshold() {
        val d = ContinuousPressDetector(128, threshold = 100)
        assertTrue(d.isPress(7, 50, 1000))
        assertFalse(d.isPress(7, 51, 1090))   // 90ms < 100ms = still moving = repeat
        assertTrue(d.isPress(7, 52, 1200))    // 210ms > 100ms = press
    }

    @Test
    fun pressDetectorRejectsOutOfRangeIndex() {
        val d = ContinuousPressDetector(4)
        assertFalse(d.isPress(-1, 5, 1000))
        assertFalse(d.isPress(4, 5, 1000))
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