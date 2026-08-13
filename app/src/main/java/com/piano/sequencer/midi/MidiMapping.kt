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
        learnState = LearnState.values().find { it.name == action } ?: LearnState.IDLE
    }

    fun stopLearn() {
        learnState = LearnState.IDLE
    }

    override fun onSend(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        if (learnState == LearnState.IDLE) return

        val end = offset + length
        var pos = offset
        while (pos < end) {
            val statusInt = data[pos].toInt() and 0xFF
            val type = statusInt and 0xF0
            val channel = statusInt and 0x0F
            pos++

            val mapping = when (type) {
                0x90 -> {
                    val data1 = if (pos < end) data[pos].toInt() and 0xFF else 0; if (pos < end) pos++ else 0
                    val data2 = if (pos < end) data[pos].toInt() and 0xFF else 0; if (pos < end) pos++ else 0
                    MidiMapping(
                        id = nextId++,
                        messageType = "NOTE_ON",
                        channel = channel,
                        data1 = data1,
                        data2 = data2,
                        action = learnState.name,
                        targetId = 0
                    )
                }
                0x80 -> {
                    val data1 = if (pos < end) data[pos].toInt() and 0xFF else 0; if (pos < end) pos++ else 0
                    val data2 = if (pos < end) data[pos].toInt() and 0xFF else 0; if (pos < end) pos++ else 0
                    MidiMapping(
                        id = nextId++,
                        messageType = "NOTE_OFF",
                        channel = channel,
                        data1 = data1,
                        data2 = data2,
                        action = learnState.name,
                        targetId = 0
                    )
                }
                0xB0 -> {
                    val data1 = if (pos < end) data[pos].toInt() and 0xFF else 0; if (pos < end) pos++ else 0
                    val data2 = if (pos < end) data[pos].toInt() and 0xFF else 0; if (pos < end) pos++ else 0
                    MidiMapping(
                        id = nextId++,
                        messageType = "CC",
                        channel = channel,
                        data1 = data1,
                        data2 = data2,
                        action = learnState.name,
                        targetId = 0
                    )
                }
                0xC0 -> {
                    val data1 = if (pos < end) data[pos].toInt() and 0xFF else 0
                    MidiMapping(
                        id = nextId++,
                        messageType = "PC",
                        channel = channel,
                        data1 = data1,
                        data2 = 0,
                        action = learnState.name,
                        targetId = 0
                    )
                }
                0xE0 -> {
                    val data1 = if (pos < end) data[pos].toInt() and 0xFF else 0; if (pos < end) pos++ else 0
                    val data2 = if (pos < end) data[pos].toInt() and 0xFF else 0; if (pos < end) pos++ else 0
                    MidiMapping(
                        id = nextId++,
                        messageType = "PITCH_BEND",
                        channel = channel,
                        data1 = data1,
                        data2 = data2,
                        action = learnState.name,
                        targetId = 0
                    )
                }
                else -> return
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
            return
        }
    }

    fun getMappings(): List<MidiMapping> = mappings.toList()
    fun removeMapping(id: Int) {
        mappings.removeAll { it.id == id }
    }
}