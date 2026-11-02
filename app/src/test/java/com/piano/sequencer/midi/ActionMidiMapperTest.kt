package com.piano.sequencer.midi

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ActionMidiMapperTest {
    private class Prefs : SharedPreferences {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String, defValue: String?) = data[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { if (value == null) data.remove(key) else data[key] = value }
            override fun apply() {}
            override fun commit() = true
            override fun clear() = this
            override fun remove(key: String) = this
            override fun putStringSet(key: String, value: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
        }
        override fun getAll(): MutableMap<String, *> = data
        override fun contains(key: String) = data.containsKey(key)
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test fun captureConflictPersistenceAndCcEdge() {
        val prefs = Prefs(); val mapper = ActionMidiMapper(prefs)
        assertTrue(mapper.startLearn(MidiAction.RECORD))
        assertTrue(mapper.event(MidiTriggerType.NOTE_ON, 2, 60, 100).consumed)
        assertEquals(ActionMidiMapping(MidiTriggerType.NOTE_ON, 2, 60), ActionMidiMapper(prefs).mapping(MidiAction.RECORD))
        assertTrue(mapper.startLearn(MidiAction.PANIC))
        val conflict = mapper.event(MidiTriggerType.NOTE_ON, 2, 60, 1)
        assertEquals(MidiAction.RECORD, conflict.conflict)
        assertFalse(mapper.isLearning())
        assertTrue(mapper.startLearn(MidiAction.PANIC))
        assertEquals(MidiAction.PANIC, mapper.event(MidiTriggerType.CC, 1, 7, 64).captured)
        val initiallyHigh = mapper.event(MidiTriggerType.CC, 1, 7, 100)
        assertTrue(initiallyHigh.consumed)
        assertNull(initiallyHigh.action)
        val release = mapper.event(MidiTriggerType.CC, 1, 7, 10)
        assertTrue(release.consumed)
        assertNull(release.action)
        val press = mapper.event(MidiTriggerType.CC, 1, 7, 64)
        assertTrue(press.consumed)
        assertEquals(MidiAction.PANIC, press.action)
        val repeat = mapper.event(MidiTriggerType.CC, 1, 7, 64)
        assertTrue(repeat.consumed)
        assertNull(repeat.action)
    }

    @Test fun noteAndProgramTriggersMatchChannelAndConsumeInput() {
        val mapper = ActionMidiMapper(Prefs())
        assertTrue(mapper.startLearn(MidiAction.RECORD))
        mapper.event(MidiTriggerType.NOTE_ON, 2, 60, 100)
        assertEquals(MidiAction.RECORD, mapper.event(MidiTriggerType.NOTE_ON, 2, 60, 1).action)
        assertTrue(mapper.event(MidiTriggerType.NOTE_ON, 2, 60, 1).consumed)
        assertNull(mapper.event(MidiTriggerType.NOTE_ON, 3, 60, 100).action)
        assertTrue(mapper.consumesNoteOff(2, 60))
        assertFalse(mapper.consumesNoteOff(3, 60))

        assertTrue(mapper.startLearn(MidiAction.PANIC))
        mapper.event(MidiTriggerType.PROGRAM, 4, 12, 0)
        val trigger = mapper.event(MidiTriggerType.PROGRAM, 4, 12, 0)
        assertTrue(trigger.consumed)
        assertEquals(MidiAction.PANIC, trigger.action)
        assertNull(mapper.event(MidiTriggerType.PROGRAM, 4, 13, 0).action)
    }
}
