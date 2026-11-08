package com.piano.sequencer.midi

import android.content.Context
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDeviceInfo
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import com.piano.sequencer.AppLogger

/** Passive bounded inventory. Never opens, claims, or transfers USB data. */
internal class UsbAudioDiagnostics(context: Context, private val handler: Handler) {
    private val appContext = context.applicationContext
    private val usb = appContext.getSystemService(UsbManager::class.java)
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val lifecycle = UsbDiagnosticLifecycle()
    @Volatile private var closed = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = enqueue("audio-added")
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = enqueue("audio-removed")
    }

    init {
        audio.registerAudioDeviceCallback(callback, handler)
        handler.post { if (!closed) snapshot("startup", null, lifecycle.snapshot(SystemClock.elapsedRealtime()), null) }
    }

    fun transition(stage: String, midi: MidiDeviceInfo?) {
        if (closed) return
        handler.post {
            if (closed) return@post
            val event = lifecycle.transition(stage, midi, SystemClock.elapsedRealtime()) ?: return@post
            snapshot(event.stage, event.midi as? MidiDeviceInfo, event, event.followupGeneration)
            event.followupGeneration?.let(::scheduleFollowups)
        }
    }

    private fun scheduleFollowups(generation: Long) {
        listOf(1_000L, 3_000L).forEach { delay ->
            handler.postDelayed({
            if (!closed && lifecycle.isCurrent(generation)) {
                    snapshot("followup-${delay}ms", lifecycle.currentMidi() as? MidiDeviceInfo, lifecycle.snapshot(SystemClock.elapsedRealtime()), generation)
                }
            }, delay)
        }
    }

    private fun enqueue(stage: String) {
        if (!closed) handler.post {
            if (!closed) snapshot(stage, lifecycle.currentMidi() as? MidiDeviceInfo, lifecycle.snapshot(SystemClock.elapsedRealtime()), null)
        }
    }

    private fun snapshot(stage: String, midi: MidiDeviceInfo?, event: UsbDiagnosticLifecycle.Event, snapshotGeneration: Long?) {
        val seq = lifecycle.nextSnapshotSequence()
        val now = SystemClock.elapsedRealtime()
        val id = "seq=$seq phase=$stage elapsedMs=$now"
        val usbValues = usb.deviceList.values.asSequence()
        val usbList = UsbDiagnosticFormat.boundedList(usbValues, MAX_DEVICES, "usb-devices-truncated") { d ->
            val cfgs = UsbDiagnosticFormat.boundedList((0 until d.configurationCount).asSequence().map(d::getConfiguration), MAX_CONFIGS, "cfg-truncated") { cfg ->
                val ifaces = UsbDiagnosticFormat.boundedList((0 until cfg.interfaceCount).asSequence().map(cfg::getInterface), MAX_INTERFACES, "if-truncated") { intf ->
                    val eps = UsbDiagnosticFormat.boundedList((0 until intf.endpointCount).asSequence().map(intf::getEndpoint), MAX_ENDPOINTS, "ep-truncated") { ep ->
                        "${ep.endpointNumber}/${ep.direction}/${ep.type}/${ep.maxPacketSize}"
                    }
                    "if${intf.id}/alt${intf.alternateSetting}/class${intf.interfaceClass}.${intf.interfaceSubclass}.${intf.interfaceProtocol}[$eps]"
                }
                "cfg${cfg.id}[$ifaces]"
            }
            "vid=${d.vendorId}:pid=${d.productId}:device=${safe(d.deviceName)}[$cfgs]"
        }
        val ports = midi?.ports?.take(MAX_PORTS)?.joinToString(";") { "${it.type}:${it.portNumber}:${safe(it.name)}" } ?: "none"
        val midiLine = midi?.let { "id=${it.id} type=${it.type} name=${safe(it.properties.getString("name"))} outputs=${it.outputPortCount} ports=[$ports]" } ?: "none"

        val audioDevices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val outputs = UsbDiagnosticFormat.boundedList(audioDevices.asSequence(), MAX_DEVICES, "audio-outputs-truncated") { device ->
            "type=${device.type}:product=${safe(device.productName?.toString())}:rates=${ints(device.sampleRates)}:enc=${ints(device.encodings)}:channels=${ints(device.channelCounts)}:masks=${ints(device.channelMasks)}"
        }
        val required = listOf(
            "$id phase=$stage generation=${snapshotGeneration ?: "none"} build=${buildLine()} focus=unknown route=unknown outputMeaning=advertised-capabilities-not-active-route",
            "$id selectedMidi=[$midiLine] status=${event.status} transitionMs=${event.durationMs ?: "unknown"}"
        )
        val records = sequence {
            usbList.lines.forEach { yield("$id usb=[$it]") }
            outputs.lines.forEach { yield("$id output=[$it]") }
            if (usbList.truncated) yield("$id truncation=usb-device-limit")
            if (outputs.truncated || audioDevices.size > MAX_DEVICES) yield("$id truncation=audio-output-limit")
            if ((midi?.ports?.size ?: 0) > MAX_PORTS) yield("$id truncation=midi-ports")
        }
        UsbDiagnosticFormat.boundedRecords(records, required, MAX_SNAPSHOT_LINES).forEach { AppLogger.info(TAG, it) }
    }

    fun close(onClosed: () -> Unit) {
        handler.post {
            try {
                if (!closed) {
                    val final = lifecycle.close(SystemClock.elapsedRealtime())
                    if (final != null) snapshot("post-close", final.midi as? MidiDeviceInfo, final, null)
                }
            } finally {
                closed = true
                try { audio.unregisterAudioDeviceCallback(callback) }
                finally { onClosed() }
            }
        }
    }

    private fun ints(values: IntArray?) = values?.take(MAX_VALUES)?.joinToString(",") +
        if ((values?.size ?: 0) > MAX_VALUES) "+truncated" else ""
    private fun safe(value: String?): String = UsbDiagnosticFormat.sanitize(value, MAX_TEXT)
    private fun buildLine(): String = "build app=${safe(versionName())}(${versionCode()}) android=${Build.VERSION.SDK_INT} release=${safe(Build.VERSION.RELEASE)} model=${safe(Build.MODEL)} device=${safe(Build.DEVICE)} display=${safe(Build.DISPLAY)}"
    private fun versionName(): String = try { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown" } catch (_: Exception) { "unknown" }
    private fun versionCode(): Long = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: Exception) { -1L }

    private companion object {
        const val TAG = "UsbAudioDiagnostics"
        const val MAX_DEVICES = 8
        const val MAX_CONFIGS = 4
        const val MAX_INTERFACES = 8
        const val MAX_ENDPOINTS = 8
        const val MAX_PORTS = 8
        const val MAX_VALUES = 16
        const val MAX_TEXT = 48
        const val MAX_SNAPSHOT_LINES = 32
    }
}
