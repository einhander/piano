package com.piano.sequencer.midi

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager

class MidiDeviceManager(
    context: Context,
    private val receiver: android.media.midi.MidiReceiver
) {
    private val midiManager = context.applicationContext.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private var activeDevice: MidiDeviceInfo? = null
    private var activeInputPort: MidiInputPort? = null

    interface Listener {
        fun onDeviceConnected(device: MidiDeviceInfo)
        fun onDeviceDisconnected()
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun listDevices(): List<MidiDeviceInfo> {
        return midiManager.devices.filter { it.inputPortCount > 0 }.toList()
    }

    fun connect(deviceInfo: MidiDeviceInfo) {
        if (activeInputPort != null) {
            disconnect()
        }
        activeDevice = deviceInfo
        val port = midiManager.openInputPort(deviceInfo, "piano-seq-port") { /* receiver passed via constructor */ } ?: run {
            listener?.onDeviceDisconnected()
            return
        }
        activeInputPort = port
        listener?.onDeviceConnected(deviceInfo)
    }

    fun disconnect() {
        if (activeInputPort == null) return
        activeDevice = null
        activeInputPort?.close()
        activeInputPort = null
        listener?.onDeviceDisconnected()
    }

    fun isConnected(): Boolean = activeInputPort != null

    fun close() {
        disconnect()
    }
}