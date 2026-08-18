package com.piano.sequencer.midi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.piano.sequencer.AppLogger
import com.piano.sequencer.service.PlaybackService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

/**
 * Process-level singleton that owns the pad→MIDI-file trigger state.
 *
 * D5: must survive activity recreation and work regardless of which
 * activity is in front (performance buttons live on the main screen).
 *
 * Holds: noteSlotMap, loadedFilePerSlot (filePath+channel), nextSlotIndex,
 * NoteToggleStateMachine, test-play state (slot 15, generation counter, 3s auto-stop).
 * Never holds a strong Activity reference — uses applicationContext for Toasts.
 */
class MidiFileTriggerController private constructor(appContext: Context) {

    companion object {
        @Volatile
        private var instance: MidiFileTriggerController? = null

        fun get(context: Context): MidiFileTriggerController {
            instance ?: run {
                synchronized(MidiFileTriggerController::class) {
                    instance ?: run {
                        instance = MidiFileTriggerController(
                            context.applicationContext
                        )
                    }
                }
            }
            return instance!!
        }
    }

    private val appContext: Context = appContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Single-threaded executor for ALL slot JNI ops — prevents races between
     *  triggerSlot, testPlay, onSettingChanged, freeSlotForNote, stopSlotForNote.
     *  The -4 retry loops run here too (bounded 150ms total). */
    private val slotExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MidiFileSlotWorker").apply { isDaemon = true }
    }

    // ── Mutable state ──

    private val noteSlotMap = ConcurrentHashMap<Int, Int>()
    private val nextSlotIndex = AtomicInteger(0)

    /** slot → (filePath, channel). Used by m8 skip-load: reload when EITHER changes. */
    private val loadedFilePerSlot = ConcurrentHashMap<Int, Pair<String, Int>>()

    private val noteStateMachine = NoteToggleStateMachine()

    private var service: PlaybackService? = null
    private var store: MidiFileMappingStore? = null

    // Test-play state (slot 15, generation counter, 3s auto-stop)
    @Volatile
    private var testPlayGeneration = 0
    @Volatile
    private var testPlayPlaying = false
    private val testPlaySlot = 15
    private var testPlayRunnable: Runnable? = null

    /** Main-thread callback for test-play UI updates (set by SequencerActivity, cleared in onDestroy). */
    var onTestPlayStateChanged: ((Boolean) -> Unit)? = null

    // ── Binding ──

    /** Called from each activity's onServiceConnected. */
    fun bind(activity: android.app.Activity, service: PlaybackService) {
        this.service = service
        // Lazily resolve store from the singleton accessor
        this.store ?: run {
            this.store = MidiFileMappingStore.get(activity.applicationContext)
        }
    }

    // ── MIDI callback delegation ──

    /**
     * Handle a note-on from the MIDI callback.
     * Returns false while recording (file triggering is paused; notes must reach engine).
     * Returns true if the event was consumed (mapped note) — caller must NOT forward to engine.
     */
    fun onNoteOn(channel: Int, note: Int, velocity: Int): Boolean {
        // While recording, all notes must reach the engine (recorded + synthesized);
        // file triggering is paused for the duration of the recording.
        if (service?.isRecording() == true) return false
        // Learn state active → capture
        if (MidiFileLearnState.getState() == MidiFileLearnState.State.LEARNING) {
            MidiFileLearnState.captureNote(note)
            return true
        }
        val s = store ?: return false
        val cell = s.findByNote(note)
        if (cell == null) return false // unmapped → caller forwards

        val result = noteStateMachine.noteOn(note)
        when (result) {
            NoteToggleStateMachine.Result.TOGGLE_ON -> triggerSlot(cell, SystemClock.uptimeMillis())
            NoteToggleStateMachine.Result.TOGGLE_OFF -> stopSlotForNote(note)
            NoteToggleStateMachine.Result.IGNORED -> {} // key repeat
        }
        return true
    }

    /**
     * Handle a note-off from the MIDI callback.
     * Returns false while recording (file triggering is paused; notes must reach engine).
     * Returns true if consumed (mapped note).
     */
    fun onNoteOff(channel: Int, note: Int, velocity: Int): Boolean {
        // While recording, all notes must reach the engine (recorded + synthesized);
        // file triggering is paused for the duration of the recording.
        if (service?.isRecording() == true) return false
        noteStateMachine.noteOff(note)
        val s = store ?: return false
        return s.findByNote(note) != null // consumed if mapped
    }

    // ── Trigger logic ──

    /**
     * Trigger a slot: load if not loaded (m8 skip), then start/stop.
     * Worker thread. Handles -4 (busy) with up to 3 retries @50ms.
     * Public for panel callbacks (SequencerActivity).
     */
    fun triggerSlot(cell: SequencerCell, t0: Long = SystemClock.uptimeMillis()) {
        slotExecutor.execute {
            val svc = service ?: return@execute
            val slot = allocateSlot(cell.note)
            val t1 = SystemClock.uptimeMillis()
            val isPlaying = svc.isMidiFileSlotPlaying(slot)

            AppLogger.info("TRIG", "press note=${cell.note} slot=$slot qwait=${t1 - t0}ms")

            if (!isPlaying) {
                val loaded = loadedFilePerSlot[slot]
                val alreadyLoaded = loaded != null &&
                    loaded.first == cell.filePath &&
                    loaded.second == cell.channel
                if (!alreadyLoaded) {
                    val t2 = SystemClock.uptimeMillis()
                    var loadResult = svc.loadMidiFileSlot(
                        slot, cell.filePath, cell.tempo, cell.loop,
                        cell.channel
                    )
                    var retries = 0
                    while (loadResult == -4 && retries < 3) {
                        Thread.sleep(50)
                        loadResult = svc.loadMidiFileSlot(
                            slot, cell.filePath, cell.tempo, cell.loop,
                            cell.channel
                        )
                        retries++
                    }
                    val t3 = SystemClock.uptimeMillis()
                    if (loadResult != 0) {
                        val msg = when (loadResult) {
                            -1 -> "Invalid file or engine"
                            -2 -> "File too long (>8192 events)"
                            -3 -> "Command queue full"
                            -4 -> "Slot busy (after retries)"
                            else -> "Error $loadResult"
                        }
                        AppLogger.info("TRIG", "load slot=$slot result=$loadResult ${t3 - t2}ms (t0+${t3 - t0}ms)")
                        showToast(msg)
                        return@execute
                    }
                    loadedFilePerSlot[slot] = cell.filePath to cell.channel
                    AppLogger.info("TRIG", "load slot=$slot result=$loadResult ${t3 - t2}ms (t0+${t3 - t0}ms)")
                }
            }

            if (svc.isMidiFileSlotPlaying(slot)) {
                svc.stopMidiFileSlot(slot)
                noteStateMachine.stopPlaying(cell.note)
            } else {
                val t4 = SystemClock.uptimeMillis()
                svc.startMidiFileSlot(slot)
                AppLogger.info("TRIG", "start slot=$slot total=${t4 - t0}ms")

                // Tail: read the frame markers ~100ms later. The command queue is
                // drained every audio callback (~1.3ms), so the command is consumed
                // with wide margin. Start path only — on the stop path the markers
                // would be stale from a previous trigger.
                mainHandler.postDelayed({
                    val s = service ?: return@postDelayed
                    val lf = s.getMidiFileSlotLoadFrame(slot)
                    val sf = s.getMidiFileSlotStartFrame(slot)
                    val nf = s.getFramePosition()
                    AppLogger.info("TRIG", "tail slot=$slot loadFrame=$lf startFrame=$sf nowFrame=$nf")
                }, 100)
            }
        }
    }

    /** Free a slot when a mapping is removed or file is deleted. */
    fun freeSlotForNote(note: Int) {
        val slot = noteSlotMap.remove(note)
        if (slot != null) {
            noteStateMachine.stopPlaying(note)
            slotExecutor.execute {
                val svc = service
                if (svc != null) svc.freeMidiFileSlot(slot)
                loadedFilePerSlot.remove(slot)
            }
        }
    }

    /** Stop a slot without freeing it (keeps mapping + loaded file). */
    fun stopSlotForNote(note: Int) {
        val slot = noteSlotMap[note] ?: return
        slotExecutor.execute {
            val svc = service
            if (svc != null) svc.stopMidiFileSlot(slot)
            noteStateMachine.stopPlaying(note)
        }
    }

    /** Test-play: slot 15, generation counter, 3s auto-stop. */
    fun testPlay(filePath: String, loop: Boolean, tempo: Double, channel: Int) {
        slotExecutor.execute {
            val svc = service ?: return@execute
            val gen = testPlayGeneration + 1
            testPlayGeneration = gen
            if (testPlayPlaying) {
                svc.stopMidiFileSlot(testPlaySlot)
                testPlayPlaying = false
                mainHandler.post { onTestPlayStateChanged?.invoke(false) }
                cancelTestPlayAutoStop()
                return@execute
            }
            var loadResult = svc.loadMidiFileSlot(testPlaySlot, filePath, tempo, loop, channel)
            var retries = 0
            while (loadResult == -4 && retries < 3) {
                Thread.sleep(50)
                loadResult = svc.loadMidiFileSlot(testPlaySlot, filePath, tempo, loop, channel)
                retries++
            }
            if (loadResult != 0) {
                val msg = when (loadResult) {
                    -1 -> "Invalid file or engine"
                    -2 -> "File too long (>8192 events)"
                    -3 -> "Command queue full"
                    -4 -> "Slot busy (after retries)"
                    else -> "Error $loadResult"
                }
                showToast(msg)
                return@execute
            }
            svc.startMidiFileSlot(testPlaySlot)
            testPlayPlaying = true
            mainHandler.post { onTestPlayStateChanged?.invoke(true) }
            cancelTestPlayAutoStop()
            testPlayRunnable = Runnable {
                if (testPlayGeneration == gen) {
                    slotExecutor.execute {
                        svc.stopMidiFileSlot(testPlaySlot)
                        testPlayPlaying = false
                        mainHandler.post { onTestPlayStateChanged?.invoke(false) }
                    }
                }
            }
            mainHandler.postDelayed(testPlayRunnable!!, 3000)
        }
    }

    private fun cancelTestPlayAutoStop() {
        mainHandler.removeCallbacks(testPlayRunnable ?: return)
        testPlayRunnable = null
    }

    /**
     * Stop all active file slots and test-play before a recording starts.
     * Worker thread (slotExecutor). Their pass-start events carry timestamp == 0
     * and would otherwise leak into the recording; also resets the toggle state
     * machines so the first press after the recording is TOGGLE_ON.
     */
    fun stopAllForRecording() {
        slotExecutor.execute {
            val svc = service ?: return@execute
            for (note in noteSlotMap.keys) {
                val slot = noteSlotMap[note] ?: continue
                if (svc.isMidiFileSlotPlaying(slot)) {
                    svc.stopMidiFileSlot(slot)
                }
                noteStateMachine.stopPlaying(note)
            }
            if (testPlayPlaying) {
                svc.stopMidiFileSlot(testPlaySlot)
                testPlayPlaying = false
                mainHandler.post { onTestPlayStateChanged?.invoke(false) }
            }
            cancelTestPlayAutoStop()
        }
    }

    /**
     * Handle setting changes (loop/tempo/channel).
     * loop/tempo: live update slot. channel: reload the slot (worker thread).
     */
    fun onSettingChanged(note: Int, loop: Boolean, tempo: Double, channel: Int) {
        slotExecutor.execute {
            val svc = service ?: return@execute
            val slot = noteSlotMap[note] ?: return@execute

            // Live loop/tempo
            svc.setMidiFileSlotLoop(slot, loop)
            svc.setMidiFileSlotTempo(slot, tempo)

            // Channel change → reload slot
            if (channel != loadedFilePerSlot[slot]?.second) {
                val wasPlaying = svc.isMidiFileSlotPlaying(slot)
                if (wasPlaying) svc.stopMidiFileSlot(slot)
                val loadResult = svc.loadMidiFileSlot(
                    slot,
                    loadedFilePerSlot[slot]?.first ?: return@execute,
                    tempo, loop, channel
                )
                if (loadResult == 0) {
                    val path = loadedFilePerSlot[slot]?.first ?: return@execute
                    loadedFilePerSlot[slot] = path to channel
                    if (wasPlaying) svc.startMidiFileSlot(slot)
                } else {
                    mainHandler.post {
                        AppLogger.warn("TriggerController",
                            "Channel reload failed for slot $slot: $loadResult")
                    }
                }
            }
        }
    }

    // ── Helpers ──

    /** D6: 16 slots; round-robin for 17th+ mapping. Reserve slot 15 for test-play. */
    private fun allocateSlot(note: Int): Int {
        return noteSlotMap.getOrPut(note) {
            val slot = nextSlotIndex.getAndIncrement() % 15
            slot
        }
    }

    /** Allocate a slot for a learned note (public for SequencerActivity). */
    fun allocateSlotForNote(note: Int): Int {
        return noteSlotMap.getOrPut(note) {
            val slot = nextSlotIndex.getAndIncrement() % 15
            slot
        }
    }

    /** Get the slot assigned to a note, or null. */
    fun getSlotForNote(note: Int): Int? = noteSlotMap[note]

    /** Clear loaded-file tracking for a slot. */
    fun clearLoadedFile(slot: Int) {
        loadedFilePerSlot.remove(slot)
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}