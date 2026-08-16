package com.piano.sequencer.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import com.piano.sequencer.AppLogger

class MidiDeviceManager(
    context: Context,
    private val inputCallback: MidiReceiver
) {
    private val midiManager = context.applicationContext.getSystemService(MidiManager::class.java)
    private var activeDevice: MidiDeviceInfo? = null
    private var activeMidiDevice: MidiDevice? = null
    private var activeOutputPort: MidiOutputPort? = null

    // HandlerThread to drive the async openDevice callback off the UI thread.
    private val midiHandlerThread = HandlerThread("MidiInput").apply { start() }
    private val midiHandler = Handler(midiHandlerThread.looper)

    // Guard against async openDevice races.
    @Volatile
    private var connectGeneration: Int = 0

    interface Listener {
        fun onDeviceConnected(device: MidiDeviceInfo)
        fun onDeviceDisconnected()
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun listDevices(): List<MidiDeviceInfo> {
        return midiManager.devices.filter { it.outputPortCount > 0 }.toList()
    }

    fun deviceName(info: MidiDeviceInfo): String =
        info.properties.getString("name") ?: "MIDI device ${info.id}"

    fun getCurrentDevice(): MidiDeviceInfo? = activeDevice

    fun stableKey(info: MidiDeviceInfo): String {
        val p = info.properties
        val manufacturer = p.getString("manufacturer") ?: ""
        val product = p.getString("product") ?: ""
        val serial = p.getString("serial_number") ?: ""
        return if (serial.isNotEmpty()) "$manufacturer|$product|$serial"
               else "$manufacturer|$product|${p.getString("name") ?: ""}"
    }

    // Handler-based variant (not the API 33+ Executor one) because minSdk is 26.
    @Suppress("DEPRECATION")
    fun registerDeviceCallback(callback: MidiManager.DeviceCallback, handler: Handler) {
        midiManager.registerDeviceCallback(callback, handler)
    }

    fun unregisterDeviceCallback(callback: MidiManager.DeviceCallback) {
        midiManager.unregisterDeviceCallback(callback)
    }

    fun connect(deviceInfo: MidiDeviceInfo) {
        if (activeOutputPort != null || activeMidiDevice != null) {
            disconnect()
        }
        connectGeneration++
        val gen = connectGeneration
        activeDevice = deviceInfo

        midiManager.openDevice(deviceInfo, object : MidiManager.OnDeviceOpenedListener {
            override fun onDeviceOpened(device: MidiDevice?) {
                // Stale-callback guard
                if (gen != connectGeneration) {
                    device?.let { try { it.close() } catch (e: Exception) {} }
                    return
                }
                if (device == null) {
                    activeDevice = null
                    AppLogger.warn("MidiDeviceManager", "Failed to open device ${deviceInfo.id}")
                    listener?.onDeviceDisconnected()
                    return
                }
                val port = try {
                    device.openOutputPort(0)
                } catch (e: Exception) {
                    AppLogger.warn("MidiDeviceManager", "openOutputPort failed: ${e.message}")
                    null
                }
                if (port == null) {
                    try { device.close() } catch (e: Exception) {}
                    activeDevice = null
                    AppLogger.warn("MidiDeviceManager", "Failed to open output port for device ${deviceInfo.id}")
                    listener?.onDeviceDisconnected()
                    return
                }
                try {
                    port.connect(inputCallback)
                } catch (e: Exception) {
                    AppLogger.warn("MidiDeviceManager", "connect receiver failed: ${e.message}")
                }
                // Gate window: disconnect may have run between port.open and here.
                if (gen != connectGeneration) {
                    try { port.disconnect(inputCallback) } catch (e: Exception) {}
                    try { port.close() } catch (e: Exception) {}
                    try { device.close() } catch (e: Exception) {}
                    return
                }
                activeMidiDevice = device
                activeOutputPort = port
                AppLogger.info("MidiDeviceManager", "Connected: device ${deviceInfo.id}")
                listener?.onDeviceConnected(deviceInfo)
            }

            }, midiHandler)
    }

    fun disconnect() {
        connectGeneration++
        val wasConnected = activeOutputPort != null || activeMidiDevice != null
        if (wasConnected) {
            AppLogger.info("MidiDeviceManager", "Disconnected: device ${activeDevice?.id ?: -1}")
            try {
                activeOutputPort?.disconnect(inputCallback)
                activeOutputPort?.close()
                activeMidiDevice?.close()
            } catch (e: Exception) {
                AppLogger.warn("MidiDeviceManager", "Error closing MIDI: ${e.message}")
            }
        }
        activeDevice = null
        activeMidiDevice = null
        activeOutputPort = null
        if (wasConnected) listener?.onDeviceDisconnected()
    }

    fun isConnected(): Boolean = activeOutputPort != null || activeMidiDevice != null

    fun close() {
        disconnect()
        midiHandlerThread.quitSafely()
    }
}