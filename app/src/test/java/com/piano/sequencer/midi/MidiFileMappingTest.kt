package com.piano.sequencer.midi

import org.junit.Assert.assertEquals
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

    /** A minimal SharedPreferences implementation backed by a HashMap. */
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

    private fun makeAssignment(note: Int, path: String, loop: Boolean = true, tempo: Double = 120.0): MidiFileAssignment {
        return MidiFileAssignment(note, path, loop, tempo)
    }

    // ── Store tests ──

    @Test
    fun emptyStoreLoadsAsEmpty() {
        val store = createStore()
        assertTrue(store.all().isEmpty())
        assertNull(store.get(60))
    }

    @Test
    fun roundTripSaveAndReload() {
        val store = createStore()
        val a1 = makeAssignment(36, "/path/to/file1.mid", loop = true, tempo = 100.0)
        val a2 = makeAssignment(60, "/path/to/file2.mid", loop = false, tempo = 140.0)

        store.set(a1)
        store.set(a2)

        // Reload from same store (simulates init re-read)
        val all = store.all()
        assertEquals(2, all.size)
        assertEquals(a1, all[36])
        assertEquals(a2, all[60])
    }

    @Test
    fun roundTripAcrossStores() {
        // Simulate: one store saves, another store loads (process death scenario)
        // Both must share the same underlying data
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
        val a = makeAssignment(48, "/music/test.mid", loop = true, tempo = 90.0)
        writer.set(a)

        // Read back from a new store sharing the same prefs
        val reader = MidiFileMappingStore(sharedPrefs)
        val loaded = reader.get(48)!!
        assertEquals(a.note, loaded.note)
        assertEquals(a.filePath, loaded.filePath)
        assertEquals(a.loop, loaded.loop)
        assertEquals(a.tempo, loaded.tempo, 0.001)
    }

    @Test
    fun conflictSameNoteReplacesOld() {
        val store = createStore()
        val a1 = makeAssignment(60, "/old/path.mid", loop = true, tempo = 100.0)
        val a2 = makeAssignment(60, "/new/path.mid", loop = false, tempo = 140.0)

        store.set(a1)
        store.set(a2)

        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(a2, all[60])
    }

    @Test
    fun deleteRemoves() {
        val store = createStore()
        store.set(makeAssignment(36, "/path.mid"))
        assertEquals(1, store.all().size)

        store.remove(36)
        assertTrue(store.all().isEmpty())
        assertNull(store.get(36))
    }

    @Test
    fun clearRemovesAll() {
        val store = createStore()
        store.set(makeAssignment(36, "/a.mid"))
        store.set(makeAssignment(60, "/b.mid"))
        store.set(makeAssignment(84, "/c.mid"))
        assertEquals(3, store.all().size)

        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun differentNotesCoexist() {
        val store = createStore()
        store.set(makeAssignment(36, "/c3.mid"))
        store.set(makeAssignment(48, "/c4.mid"))
        store.set(makeAssignment(60, "/c5.mid"))

        val all = store.all()
        assertEquals(3, all.size)
        assertNotNull(all[36])
        assertNotNull(all[48])
        assertNotNull(all[60])
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