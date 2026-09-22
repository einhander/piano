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
    private data class ActiveOutput(
        val portIndex: Int,
        val port: MidiOutputPort,
        val receiver: MidiInputReceiver
    )
    private val activeOutputs = mutableListOf<ActiveOutput>()
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
        val devices = midiManager.devices.filter { it.outputPortCount > 0 }.toList()
        AppLogger.info("MidiDeviceManager", "Eligible MIDI devices=${devices.size}")
        devices.forEach { device ->
            AppLogger.info("MidiDeviceManager", "Device id=${device.id} name=${deviceName(device)} outputPorts=${device.outputPortCount}")
        }
        return devices
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
        AppLogger.info("MidiDeviceManager", "Selected MIDI device id=${deviceInfo.id} name=${deviceName(deviceInfo)} outputPorts=${deviceInfo.outputPortCount}")
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
        val outputs = mutableListOf<ActiveOutput>()
        for (index in 0 until deviceInfo.outputPortCount) {
            AppLogger.info("MidiDeviceManager", "Opening output port $index/${deviceInfo.outputPortCount} for device ${deviceInfo.id}")
            val port = try {
                device.openOutputPort(index)
            } catch (e: Exception) {
                AppLogger.warn("MidiDeviceManager", "openOutputPort failed index=$index: ${e.message}")
                null
            }
            if (port == null) {
                AppLogger.warn("MidiDeviceManager", "Failed to open output port index=$index for device ${deviceInfo.id}")
                continue
            }
            var portReceiver: MidiInputReceiver? = null
            try {
                val receiver = inputCallback as? MidiInputReceiver
                    ?: throw IllegalStateException("MIDI input callback must be MidiInputReceiver")
                val newReceiver = receiver.createPortReceiver(index)
                portReceiver = newReceiver
                port.connect(newReceiver)
                outputs += ActiveOutput(index, port, newReceiver)
                AppLogger.info("MidiDeviceManager", "Connected output port index=$index for device ${deviceInfo.id}")
            } catch (e: Exception) {
                AppLogger.warn("MidiDeviceManager", "connect receiver failed index=$index: ${e.message}")
                portReceiver?.deactivate()
                try { port.close() } catch (closeError: Exception) {
                    AppLogger.warn("MidiDeviceManager", "close failed index=$index: ${closeError.message}")
                }
            }
        }
        if (closed || gen != connectGeneration) {
            outputs.forEach { output ->
                output.receiver.deactivate()
                try { output.port.disconnect(output.receiver) } catch (e: Exception) {
                    AppLogger.warn("MidiDeviceManager", "stale disconnect failed index=${output.portIndex}: ${e.message}")
                }
                try { output.port.close() } catch (e: Exception) {
                    AppLogger.warn("MidiDeviceManager", "stale close failed index=${output.portIndex}: ${e.message}")
                }
            }
            try { device.close() } catch (_: Exception) {}
            return
        }
        if (outputs.isEmpty()) {
            try { device.close() } catch (_: Exception) {}
            activeDevice = null
            snapshot = ConnectionSnapshot(null, false, false, gen)
            listener?.onDeviceDisconnected()
            return
        }
        activeMidiDevice = device
        activeOutputs += outputs
        connectedSnapshot = deviceInfo
        snapshot = ConnectionSnapshot(deviceInfo, true, false, gen)
        AppLogger.info("MidiDeviceManager", "Connected: device ${deviceInfo.id}")
        listener?.onDeviceConnected(deviceInfo)
    }

    fun disconnect() {
        midiHandler.post { closeActiveOnHandler(notify = true) }
    }

    private fun closeActiveOnHandler(notify: Boolean) {
        val wasActive = activeDevice != null || activeOutputs.isNotEmpty() || activeMidiDevice != null
        ++connectGeneration // invalidate pending open callback before closing resources
        val deviceId = activeDevice?.id ?: -1
        snapshot = ConnectionSnapshot(null, false, false, connectGeneration)
        val outputs = activeOutputs.toList()
        val device = activeMidiDevice
        activeDevice = null
        activeOutputs.clear()
        connectedSnapshot = null
        activeMidiDevice = null
        if (wasActive) {
            if (notify) AppLogger.info("MidiDeviceManager", "Disconnected: device $deviceId")
            outputs.forEach { output ->
                output.receiver.deactivate()
                try { output.port.disconnect(output.receiver) } catch (e: Exception) {
                    AppLogger.warn("MidiDeviceManager", "Error disconnecting MIDI port index=${output.portIndex}: ${e.message}")
                }
                try { output.port.close() } catch (e: Exception) {
                    AppLogger.warn("MidiDeviceManager", "Error closing MIDI port index=${output.portIndex}: ${e.message}")
                }
            }
            try { device?.close() } catch (e: Exception) {
                AppLogger.warn("MidiDeviceManager", "Error closing MIDI device: ${e.message}")
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
