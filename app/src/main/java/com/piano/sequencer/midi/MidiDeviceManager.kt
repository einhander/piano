package com.piano.sequencer.midi

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import com.piano.sequencer.AppLogger

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
        // Kotlin/Android SDK interop issue: openInputPort() not resolvable at compile time.
        // Use reflection to call MidiManager.openInputPort(deviceInfo, portNumber, receiver).
        val port = try {
            val method = MidiManager::class.java.getMethod(
                "openInputPort",
                MidiDeviceInfo::class.java,
                Int::class.javaPrimitiveType,
                MidiReceiver::class.java
            )
            method.invoke(midiManager, deviceInfo, 0, inputCallback) as? MidiInputPort
        } catch (e: Exception) {
            AppLogger.warn("MidiDeviceManager", "Reflection openInputPort failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (port == null) {
            activeDevice = null
            AppLogger.warn("MidiDeviceManager", "Failed to open input port for device ${deviceInfo.id}")
            listener?.onDeviceDisconnected()
            return
        }
        activeInputPort = port
        AppLogger.info("MidiDeviceManager", "Connected: device ${deviceInfo.id}")
        listener?.onDeviceConnected(deviceInfo)
    }

    fun disconnect() {
        if (activeInputPort == null) return
        AppLogger.info("MidiDeviceManager", "Disconnected: device ${activeDevice?.id ?: -1}")
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