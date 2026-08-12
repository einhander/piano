package com.piano.sequencer.midi

import android.media.midi.MidiReceiver

data class MidiMapping(
    val id: Int,
    val messageType: String,  // "NOTE_ON", "NOTE_OFF", "CC", "PC", "PITCH_BEND"
    val channel: Int,
    val data1: Int,
    val data2: Int,
    val action: String,  // "LAUNCH_SCENE", "PLAY", "STOP", "PANIC", "VOLUME", etc.
    val targetId: Int
)

class MidiLearnManager : MidiReceiver() {
    enum class LearnState {
        IDLE,
        LEARNING_SCENE,
        LEARNING_PLAY,
        LEARNING_STOP,
        LEARNING_PANIC,
        LEARNING_VOLUME,
        LEARNING_NEXT_SCENE,
        LEARNING_PREV_SCENE
    }

    private var learnState = LearnState.IDLE
    private val mappings = mutableListOf<MidiMapping>()
    private var nextId = 1

    interface Callback {
        fun onMappingLearned(mapping: MidiMapping)
        fun onMappingConflict(mapping: MidiMapping)
    }

    private var callback: Callback? = null

    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    fun startLearn(action: String) {
        learnState = LearnState.valueOf(action)
    }

    fun stopLearn() {
        learnState = LearnState.IDLE
    }

    override fun onReceive(status: Byte, data1: Byte, data2: Byte, timestamp: Long): Boolean {
        if (learnState == LearnState.IDLE) return true

        val statusInt = status.toInt() and 0xFF
        val type = statusInt and 0xF0
        val channel = statusInt and 0x0F

        val mapping = when (type) {
            0x90 -> MidiMapping(
                id = nextId++,
                messageType = "NOTE_ON",
                channel = channel,
                data1 = data1.toInt() and 0xFF,
                data2 = data2.toInt() and 0xFF,
                action = learnState.name,
                targetId = 0
            )
            0x80 -> MidiMapping(
                id = nextId++,
                messageType = "NOTE_OFF",
                channel = channel,
                data1 = data1.toInt() and 0xFF,
                data2 = data2.toInt() and 0xFF,
                action = learnState.name,
                targetId = 0
            )
            0xB0 -> MidiMapping(
                id = nextId++,
                messageType = "CC",
                channel = channel,
                data1 = data1.toInt() and 0xFF,
                data2 = data2.toInt() and 0xFF,
                action = learnState.name,
                targetId = 0
            )
            0xC0 -> MidiMapping(
                id = nextId++,
                messageType = "PC",
                channel = channel,
                data1 = data1.toInt() and 0xFF,
                data2 = data2.toInt() and 0xFF,
                action = learnState.name,
                targetId = 0
            )
            0xE0 -> MidiMapping(
                id = nextId++,
                messageType = "PITCH_BEND",
                channel = channel,
                data1 = data1.toInt() and 0xFF,
                data2 = data2.toInt() and 0xFF,
                action = learnState.name,
                targetId = 0
            )
            else -> return true
        }

        // Check for conflicts
        val conflict = mappings.find {
            it.messageType == mapping.messageType &&
            it.channel == mapping.channel &&
            it.data1 == mapping.data1 &&
            it.data2 == mapping.data2
        }

        if (conflict != null) {
            callback?.onMappingConflict(mapping)
        } else {
            mappings.add(mapping)
            callback?.onMappingLearned(mapping)
        }

        stopLearn()
        return true
    }

    fun getMappings(): List<MidiMapping> = mappings.toList()
    fun removeMapping(id: Int) {
        mappings.removeAll { it.id == id }
    }
}