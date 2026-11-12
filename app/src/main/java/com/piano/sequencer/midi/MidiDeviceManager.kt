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
    private val inputCallback: MidiReceiver,
    initialPortMode: DiagnosticPortMode = DiagnosticPortMode.ALL
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
    @Volatile private var diagnosticPortMode = initialPortMode
    @Volatile private var diagnosticState = MidiConnectionDiagnosticState(null, null, initialPortMode, false, false, emptyList())
    private var portModeCompletion: ((MidiConnectionDiagnosticState) -> Unit)? = null
    @Volatile private var closed = false
    @Volatile private var connectedSnapshot: MidiDeviceInfo? = null

    // HandlerThread to drive the async openDevice callback off the UI thread.
    private val midiHandlerThread = HandlerThread("MidiInput").apply { start() }
    private val midiHandler = Handler(midiHandlerThread.looper)
    private val usbAudioDiagnostics = UsbAudioDiagnostics(context.applicationContext, midiHandler)
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

    private fun outputPortMetadata(info: MidiDeviceInfo): String =
        info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
            .joinToString(prefix = "[", postfix = "]") { "${it.portNumber}:${it.name}" }

    fun getCurrentDevice(): MidiDeviceInfo? = snapshot.device

    fun diagnosticSnapshot(): MidiConnectionDiagnosticState = diagnosticState.copy(ports = diagnosticState.ports.toList())

    /** Reconfigure on the MIDI owner thread; callback is a completion barrier after ports/device settle. */
    fun setDiagnosticPortMode(mode: DiagnosticPortMode, callback: (MidiConnectionDiagnosticState) -> Unit = {}) {
        midiHandler.post {
            diagnosticPortMode = mode
            val current = snapshot.device ?: activeDevice
            if (current != null && !closed) {
                closeActiveOnHandler(notify = false)
                portModeCompletion = callback
                connectOnHandler(current)
            } else {
                callback(diagnosticSnapshot())
            }
        }
    }

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

    fun connect(deviceInfo: MidiDeviceInfo, completion: ((MidiConnectionDiagnosticState) -> Unit)? = null) {
        if (closed) { completion?.invoke(diagnosticSnapshot()); return }
        usbAudioDiagnostics.transition("connect-request", deviceInfo)
        AppLogger.info("MidiDeviceManager", "Selected MIDI device id=${deviceInfo.id} name=${deviceName(deviceInfo)} type=${deviceInfo.type} outputPorts=${outputPortMetadata(deviceInfo)}")
        midiHandler.post {
            val current = snapshot
            if (current.device != null && stableKey(current.device) == stableKey(deviceInfo) && !current.connecting) { completion?.invoke(diagnosticSnapshot()); return@post }
            val previousCompletion = portModeCompletion
            portModeCompletion = null
            closeActiveOnHandler(notify = true)
            previousCompletion?.invoke(diagnosticSnapshot())
            portModeCompletion = completion
            connectOnHandler(deviceInfo)
        }
    }

    private fun connectOnHandler(deviceInfo: MidiDeviceInfo) {
            if (closed) return
            val gen = ++connectGeneration
            activeDevice = deviceInfo
            snapshot = ConnectionSnapshot(deviceInfo, false, true, gen)
            diagnosticState = MidiConnectionDiagnosticState(stableKey(deviceInfo), deviceName(deviceInfo), diagnosticPortMode, true, false,
                deviceInfo.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.map { p ->
                    MidiPortDiagnosticState(p.portNumber, p.name, diagnosticPortMode.selects(p.portNumber), false, false,
                        if (diagnosticPortMode.selects(p.portNumber)) "pending" else "not selected", 0L)
                })
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
                        diagnosticState = MidiConnectionDiagnosticState(stableKey(deviceInfo), deviceName(deviceInfo), diagnosticPortMode, false, false,
                            diagnosticState.ports.map { it.copy(outcome = "device open failed", opened = false, connected = false) })
                        AppLogger.warn("MidiDeviceManager", "Failed to open device ${deviceInfo.id}")
                        usbAudioDiagnostics.transition("open-failed", deviceInfo)
                        listener?.onDeviceDisconnected()
                        completePortModeChange()
                    }
                    return
                }
                midiHandler.post { installOpenedDevice(gen, deviceInfo, device) }
            }

            }, openCallbackHandler)
    }

    private fun installOpenedDevice(gen: Long, deviceInfo: MidiDeviceInfo, device: MidiDevice) {
        if (closed || gen != connectGeneration) {
            try { device.close() } catch (_: Exception) {}
            return
        }
        val outputs = mutableListOf<ActiveOutput>()
        val ports = deviceInfo.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
        val portStates = mutableListOf<MidiPortDiagnosticState>()
        for (info in ports) {
            val index = info.portNumber
            if (!diagnosticPortMode.selects(index)) {
                portStates += MidiPortDiagnosticState(index, info.name, false, false, false, "not selected", 0L)
                continue
            }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            AppLogger.info("MidiDeviceManager", "Opening output port $index/${deviceInfo.outputPortCount} for device ${deviceInfo.id} name=${deviceName(deviceInfo)} type=${deviceInfo.type} ports=${outputPortMetadata(deviceInfo)}")
            val port = try {
                device.openOutputPort(index)
            } catch (e: Exception) {
                AppLogger.warn("MidiDeviceManager", "openOutputPort failed index=$index: ${e.message}")
                null
            }
            if (port == null) {
                AppLogger.warn("MidiDeviceManager", "Failed to open output port index=$index for device ${deviceInfo.id}")
                portStates += MidiPortDiagnosticState(index, info.name, true, false, false, "open failed", android.os.SystemClock.elapsedRealtime() - startedAt)
                continue
            }
            var portReceiver: MidiInputReceiver? = null
            try {
                val receiver = inputCallback as? MidiInputReceiver
                    ?: throw IllegalStateException("MIDI input callback must be MidiInputReceiver")
                val newReceiver = receiver.createPortReceiver(index, stableKey(deviceInfo), isSmk37ProUsb(deviceInfo))
                portReceiver = newReceiver
                port.connect(newReceiver)
                outputs += ActiveOutput(index, port, newReceiver)
                portStates += MidiPortDiagnosticState(index, info.name, true, true, true, "connected", android.os.SystemClock.elapsedRealtime() - startedAt)
                AppLogger.info("MidiDeviceManager", "Connected output port index=$index name=${deviceInfo.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT && it.portNumber == index }?.name} for device ${deviceInfo.id}")
            } catch (e: Exception) {
                AppLogger.warn("MidiDeviceManager", "connect receiver failed index=$index: ${e.message}")
                portReceiver?.deactivate()
                try { port.close() } catch (closeError: Exception) {
                    AppLogger.warn("MidiDeviceManager", "close failed index=$index: ${closeError.message}")
                }
                portStates += MidiPortDiagnosticState(index, info.name, true, true, false, "connect failed: ${e.message ?: "unknown"}", android.os.SystemClock.elapsedRealtime() - startedAt)
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
            activeMidiDevice = device
            connectedSnapshot = deviceInfo
            diagnosticState = MidiConnectionDiagnosticState(stableKey(deviceInfo), deviceName(deviceInfo), diagnosticPortMode, true, false, portStates.toList())
            snapshot = ConnectionSnapshot(deviceInfo, false, false, gen)
            completePortModeChange()
            return
        }
        activeMidiDevice = device
        activeOutputs += outputs
        connectedSnapshot = deviceInfo
        snapshot = ConnectionSnapshot(deviceInfo, true, false, gen)
        diagnosticState = MidiConnectionDiagnosticState(stableKey(deviceInfo), deviceName(deviceInfo), diagnosticPortMode, true, true, portStates.toList())
        AppLogger.info("MidiDeviceManager", "Connected: device ${deviceInfo.id}")
        usbAudioDiagnostics.transition("connected", deviceInfo)
        listener?.onDeviceConnected(deviceInfo)
        completePortModeChange()
    }

    private fun completePortModeChange() {
        val completion = portModeCompletion ?: return
        portModeCompletion = null
        completion(diagnosticSnapshot())
    }

    private fun isSmk37ProUsb(info: MidiDeviceInfo): Boolean {
        val props = info.properties
        val name = listOfNotNull(props.getString("name"), props.getString("product"), props.getString("manufacturer"))
            .joinToString(" ")
        return info.type == MidiDeviceInfo.TYPE_USB && name.contains("SMK-37 Pro", ignoreCase = true)
    }

    fun disconnect() {
        usbAudioDiagnostics.transition("disconnect-request", snapshot.device)
        midiHandler.post { closeActiveOnHandler(notify = true) }
    }

    private fun closeActiveOnHandler(notify: Boolean) {
        val canceledCompletion = portModeCompletion
        portModeCompletion = null
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
        diagnosticState = MidiConnectionDiagnosticState(null, null, diagnosticPortMode, false, false, emptyList())
        if (wasActive) {
            if (notify) AppLogger.info("MidiDeviceManager", "Disconnected: device $deviceId")
            outputs.forEach { output ->
                AppLogger.info("MidiDeviceManager", "Port totals index=${output.portIndex}: ${output.receiver.diagnosticTotals()}")
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
            usbAudioDiagnostics.transition("disconnected", null)
        }
        if (wasActive && notify) listener?.onDeviceDisconnected()
        canceledCompletion?.invoke(diagnosticSnapshot())
    }

    /** True while connected or while an open is in flight; prevents duplicate reconnects. */
    fun isConnected(): Boolean = snapshot.connected || snapshot.connecting

    fun close() {
        close { }
    }

    /** Handler barrier: callback runs after active ports/device have been closed. */
    fun close(onClosed: () -> Unit) {
        closed = true
        midiHandler.post {
            closeActiveOnHandler(notify = true)
            usbAudioDiagnostics.close {
                // Complete diagnostics before Exit may terminate the process.
                // The separate open callback handler remains alive for late callbacks.
                midiHandlerThread.quitSafely()
                onClosed()
            }
        }
    }
}
