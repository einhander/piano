package com.piano.sequencer.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.piano.sequencer.AppLogger

class MidiDeviceManager(
    context: Context,
    private val inputCallback: MidiReceiver
) {
    private val midiManager = context.applicationContext.getSystemService(MidiManager::class.java)
    private data class ConnectionSnapshot(
        val device: MidiDeviceInfo?,
        val connected: Boolean,
        val connecting: Boolean,
        val token: Long
    )

    @Volatile
    private var snapshot = ConnectionSnapshot(null, false, false, 0L)
    private var activeDevice: MidiDeviceInfo? = null
    private var activeMidiDevice: MidiDevice? = null
    private var activeOutputPort: MidiOutputPort? = null
    @Volatile private var closed = false
    @Volatile private var connectedSnapshot: MidiDeviceInfo? = null

    // HandlerThread to drive the async openDevice callback off the UI thread.
    private val midiHandlerThread = HandlerThread("MidiInput").apply { start() }
    private val midiHandler = Handler(midiHandlerThread.looper)
    // Never quit: Android may deliver an open callback after lifecycle close.
    private val openCallbackHandler = Handler(Looper.getMainLooper())

    // Guard against async openDevice races.
    @Volatile
    private var connectGeneration: Long = 0L

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

    fun getCurrentDevice(): MidiDeviceInfo? = snapshot.device

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
        if (closed) return
        midiHandler.post {
            val current = snapshot
            if (current.device != null && stableKey(current.device) == stableKey(deviceInfo)) return@post
            closeActiveOnHandler(notify = true)
            val gen = ++connectGeneration
            activeDevice = deviceInfo
            snapshot = ConnectionSnapshot(deviceInfo, false, true, gen)
            midiManager.openDevice(deviceInfo, object : MidiManager.OnDeviceOpenedListener {
            override fun onDeviceOpened(device: MidiDevice?) {
                // Stale-callback guard
                if (closed || gen != connectGeneration) {
                    device?.let { try { it.close() } catch (e: Exception) {} }
                    return
                }
                if (device == null) {
                    midiHandler.post {
                        if (gen != connectGeneration || closed) return@post
                        activeDevice = null
                        snapshot = ConnectionSnapshot(null, false, false, gen)
                        AppLogger.warn("MidiDeviceManager", "Failed to open device ${deviceInfo.id}")
                        listener?.onDeviceDisconnected()
                    }
                    return
                }
                midiHandler.post { installOpenedDevice(gen, deviceInfo, device) }
            }

            }, openCallbackHandler)
        }
    }

    private fun installOpenedDevice(gen: Long, deviceInfo: MidiDeviceInfo, device: MidiDevice) {
                if (closed || gen != connectGeneration) {
                    try { device.close() } catch (_: Exception) {}
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
                    snapshot = ConnectionSnapshot(null, false, false, gen)
                    AppLogger.warn("MidiDeviceManager", "Failed to open output port for device ${deviceInfo.id}")
                    listener?.onDeviceDisconnected()
                    return
                }
                try {
                    port.connect(inputCallback)
                } catch (e: Exception) {
                    AppLogger.warn("MidiDeviceManager", "connect receiver failed: ${e.message}")
                    try { port.close() } catch (_: Exception) {}
                    try { device.close() } catch (_: Exception) {}
                    activeDevice = null
                    snapshot = ConnectionSnapshot(null, false, false, gen)
                    listener?.onDeviceDisconnected()
                    return
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
                connectedSnapshot = deviceInfo
                snapshot = ConnectionSnapshot(deviceInfo, true, false, gen)
                AppLogger.info("MidiDeviceManager", "Connected: device ${deviceInfo.id}")
                listener?.onDeviceConnected(deviceInfo)
    }

    fun disconnect() {
        midiHandler.post { closeActiveOnHandler(notify = true) }
    }

    private fun closeActiveOnHandler(notify: Boolean) {
        val wasActive = activeDevice != null || activeOutputPort != null || activeMidiDevice != null
        ++connectGeneration // invalidate pending open callback before closing resources
        val deviceId = activeDevice?.id ?: -1
        snapshot = ConnectionSnapshot(null, false, false, connectGeneration)
        val port = activeOutputPort
        val device = activeMidiDevice
        activeDevice = null
        activeOutputPort = null
        connectedSnapshot = null
        activeMidiDevice = null
        if (wasActive) {
            if (notify) AppLogger.info("MidiDeviceManager", "Disconnected: device $deviceId")
            try {
                port?.disconnect(inputCallback)
                port?.close()
                device?.close()
            } catch (e: Exception) {
                AppLogger.warn("MidiDeviceManager", "Error closing MIDI: ${e.message}")
            }
        }
        if (wasActive && notify) listener?.onDeviceDisconnected()
    }

    /** True while connected or while an open is in flight; prevents duplicate reconnects. */
    fun isConnected(): Boolean = snapshot.connected || snapshot.connecting

    fun close() {
        closed = true
        midiHandler.post {
            closeActiveOnHandler(notify = true)
            // Keep delivery handler alive: Android may deliver an already
            // queued open callback after close. Its generation/closed guards
            // must run so returned device resources are closed.
            midiHandlerThread.quitSafely()
        }
    }
}
