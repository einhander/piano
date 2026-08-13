package com.piano.sequencer.midi

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver

class MidiDeviceManager(
    context: Context,
    private val inputCallback: MidiReceiver
) {
    private val midiManager = context.applicationContext.getSystemService(MidiManager::class.java)
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
        // TODO: Fix openInputPort - Kotlin can't resolve MidiManager method
        // val port = midiManager.openInputPort(deviceInfo, 0, inputCallback) ?: run {
        val port: MidiInputPort? = null
        if (port == null) {
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