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
import org.json.JSONObject

/**
 * Process-level singleton that owns the pad→MIDI-file trigger state.
 *
 * D5: must survive activity recreation and work regardless of which
 * activity is in front (performance buttons live on the main screen).
 *
 * Holds: triggerSlotMap (encoded trigger key → slot), loadedFilePerSlot (filePath+channel),
 * nextSlotIndex, NoteToggleStateMachine (reused for CC/pitch-bend presses via press()),
 * test-play state (slot 15, generation counter, 3s auto-stop).
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

    /** 4-thread pool for per-slot concurrency. Per-slot workerBusy CAS in C++ guards
     *  same-slot load races; cross-slot ops are independent; STOP/START/FREE idempotent;
     *  START no-ops when !loaded. testPlay uses slot 15 vs %15 slots 0-14 — no overlap. */
    private val slotExecutor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "MidiFileSlotWorker").apply { isDaemon = true }
    }

    /** Per-slot mutex: prevents the 4-thread pool from interleaving tasks on the same slot.
     *  The C++ workerBusy CAS only guards load() (I/O + buffer copy); the Kotlin
     *  read→decide→JNI→write-map sequence is not atomic per slot. Without this lock,
     *  two pool tasks on the same slot could interleave → [START, LOAD] = silence +
     *  loadedFilePerSlot diverges from C++ slot state. */
    private val slotLocks = Array(16) { Any() }

    // ── Mutable state ──

    /** trigger key (encoded: NOTE 0-127, CC 128-255, PITCH_BEND 256) → slot. */
    private val triggerSlotMap = ConcurrentHashMap<Int, Int>()
    private val nextSlotIndex = AtomicInteger(0)

    /** slot → (filePath, channel). Used by m8 skip-load: reload when EITHER changes. */
    private val loadedFilePerSlot = ConcurrentHashMap<Int, Pair<String, Int>>()

    private val noteStateMachine = NoteToggleStateMachine()

    /** B4: last-seen value per CC number (press = value change; same value = repeat). */
    private val ccPressDetector = ContinuousPressDetector(128)

    /** B4: last-seen 14-bit pitch bend value (0..16383, center 8192). */
    private val pbPressDetector = ContinuousPressDetector(1)

    private var service: PlaybackService? = null
    private var store: MidiFileMappingStore? = null

    // Test-play state (slot 15, generation counter, 3s auto-stop)
    @Volatile
    private var testPlayGeneration = 0
    @Volatile
    private var testPlayPlaying = false
    private val testPlaySlot = 15
    @Volatile
    private var testPlayRunnable: Runnable? = null
    /** Cell id of the active test-play (null when stopped). */
    @Volatile
    private var testPlayCellId: Int? = null

    /** Main-thread callback for test-play UI updates (set by SequencerActivity, cleared in onDestroy).
     *  Emits (cellId, playing): cellId is the pressed cell while playing, null when stopped. */
    var onTestPlayStateChanged: ((Int?, Boolean) -> Unit)? = null

    /** Read the current test-play state (cell id while playing, null when stopped). */
    fun testPlayState(): Pair<Int?, Boolean> {
        val playing = testPlayPlaying
        return (if (playing) testPlayCellId else null) to playing
    }

    // ── Binding ──

    /** Called from each activity's onServiceConnected. */
    fun bind(activity: android.app.Activity, service: PlaybackService) {
        this.service = service
        // Lazily resolve store from the singleton accessor
        this.store ?: run {
            this.store = MidiFileMappingStore.get(activity.applicationContext)
        }
        // Preload files when cells are saved
        store?.onCellSaved = { cell -> if (cell.filePath.isNotEmpty()) preloadFile(cell.filePath) }
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

        val result = noteStateMachine.noteOn(note, cell.loop)
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

    /**
     * Handle a control change from the MIDI callback (B4).
     * Returns false while recording (file triggering is paused; CCs must reach engine).
     * Returns true if the event was consumed — caller must NOT forward to engine:
     *  - learn state active → the first CC of any learn session is captured;
     *  - a cell is mapped to this CC → a press (value change after the controller
     *    was stable, ≥ threshold since the last event) toggles the cell's file;
     *    a same-value retransmit or a change while still moving is a key repeat —
     *    IGNORED but still consumed (a mapped CC is never forwarded to the live
     *    synth, even on repeat).
     */
    fun onControlChange(channel: Int, ccNumber: Int, value: Int): Boolean {
        if (service?.isRecording() == true) return false
        // Learn state active → capture (first event of any type wins)
        if (MidiFileLearnState.getState() == MidiFileLearnState.State.LEARNING) {
            MidiFileLearnState.captureCC(ccNumber)
            return true
        }
        if (ccNumber !in 0..127) return false
        val s = store ?: return false
        val cell = s.findByCC(ccNumber)
        if (cell == null) return false // unmapped → caller forwards

        // Press = value change after a stable value (≥ threshold since last event);
        // same-value retransmit or a change while still moving = key repeat (consumed, ignored).
        if (!ccPressDetector.isPress(ccNumber, value, SystemClock.uptimeMillis())) return true
        val key = 128 + ccNumber
        val result = noteStateMachine.press(key, cell.loop)
        when (result) {
            NoteToggleStateMachine.Result.TOGGLE_ON -> triggerSlot(cell, SystemClock.uptimeMillis())
            NoteToggleStateMachine.Result.TOGGLE_OFF -> stopSlotForTrigger(key)
            NoteToggleStateMachine.Result.IGNORED -> {} // unreachable via press(); keep exhaustive
        }
        return true
    }

    /**
     * Handle a pitch bend from the MIDI callback (B4). [value] is the 14-bit
     * value 0..16383 (center 8192), as parsed by MidiMessageParser.
     * Same contract as [onControlChange]: false while recording; true when
     * consumed (learn capture or a mapped pitch-bend cell — including repeats).
     */
    fun onPitchBend(channel: Int, value: Int): Boolean {
        if (service?.isRecording() == true) return false
        // Learn state active → capture (first event of any type wins)
        if (MidiFileLearnState.getState() == MidiFileLearnState.State.LEARNING) {
            MidiFileLearnState.capturePitchBend()
            return true
        }
        val s = store ?: return false
        val cell = s.findByPitchBend()
        if (cell == null) return false // unmapped → caller forwards

        if (!pbPressDetector.isPress(0, value, SystemClock.uptimeMillis())) return true
        val key = 256
        val result = noteStateMachine.press(key, cell.loop)
        when (result) {
            NoteToggleStateMachine.Result.TOGGLE_ON -> triggerSlot(cell, SystemClock.uptimeMillis())
            NoteToggleStateMachine.Result.TOGGLE_OFF -> stopSlotForTrigger(key)
            NoteToggleStateMachine.Result.IGNORED -> {} // unreachable via press(); keep exhaustive
        }
        return true
    }

    // ── Trigger logic ──

    /**
     * Trigger a slot: load if not loaded (m8 skip), then start/stop.
     * Worker thread. Handles -4 (busy) with up to 5 retries @20ms.
     * startAfterLoad=true on the load path → merged LOAD|START (no separate start call).
     */
    fun triggerSlot(cell: SequencerCell, t0: Long = SystemClock.uptimeMillis()) {
        // MINOR-2: stale in-flight guard — re-check the store before allocating a slot.
        // The cell came from a store lookup at press time (MIDI thread or main thread);
        // by the time this runs the mapping may have been deleted or the file changed.
        val current = store?.findByTrigger(cell.triggerType, cell.triggerData())
        if (current == null || current.filePath != cell.filePath) return

        slotExecutor.execute {
            val svc = service ?: return@execute
            val key = cell.triggerKey()
            val slot = allocateSlot(key)
            synchronized(slotLocks[slot]) {
                val t1 = SystemClock.uptimeMillis()
                val isPlaying = svc.isMidiFileSlotPlaying(slot)

                AppLogger.info("TRIG", "press key=$key slot=$slot qwait=${t1 - t0}ms")

                if (isPlaying) {
                    svc.stopMidiFileSlot(slot)
                    if (cell.loop) {
                        // Loop mode: second press interrupts playback (toggle-off).
                        noteStateMachine.stopPlaying(key)
                        return@synchronized
                    }
                    // Non-loop mode: retrigger restarts from the beginning. Flush
                    // notes now; the C++ START handler resets tick/index when
                    // !playing, and the FIFO command queue guarantees this STOP is
                    // processed before the START below.
                }
                val loaded = loadedFilePerSlot[slot]
                val alreadyLoaded = loaded != null && loaded.first == cell.filePath && loaded.second == cell.channel
                if (alreadyLoaded) {
                    val t4 = SystemClock.uptimeMillis()
                    svc.startMidiFileSlot(slot)
                    AppLogger.info("TRIG", "start slot=$slot total=${t4 - t0}ms")
                } else {
                    val t2 = SystemClock.uptimeMillis()
                    var loadResult = svc.loadMidiFileSlot(
                        slot, cell.filePath, cell.tempo, cell.loop,
                        cell.channel, true
                    )
                    var retries = 0
                    while (loadResult == -4 && retries < 5) {
                        Thread.sleep(20)
                        loadResult = svc.loadMidiFileSlot(
                            slot, cell.filePath, cell.tempo, cell.loop,
                            cell.channel, true
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
                        return@synchronized
                    }
                    loadedFilePerSlot[slot] = cell.filePath to cell.channel
                    AppLogger.info("TRIG", "loadAndStart slot=$slot load=${t3 - t2}ms total=${t3 - t0}ms")
                }
                // Tail: read the frame markers ~100ms later (start path only — on the stop
                // path the markers would be stale from a previous trigger).
                // Only the postDelayed CALL is inside the lock; the lambda runs later on
                // the main thread (it is a separate closure — it does not hold the lock).
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

    /** Free a slot for an encoded trigger key (alias of [freeSlotForTrigger]; NOTE callers pass the raw note). */
    fun freeSlotForKey(key: Int) = freeSlotForTrigger(key)

    /**
     * Free a slot for an encoded trigger key (NOTE 0-127, CC 128-255, PITCH_BEND 256)
     * when a mapping is removed or a file is deleted. Also resets the press detector
     * so a re-learned trigger starts with a fresh "no stable value yet" state.
     */
    fun freeSlotForTrigger(key: Int) {
        val slot = triggerSlotMap.remove(key)
        if (slot != null) {
            noteStateMachine.stopPlaying(key)
            resetPressDetector(key)
            slotExecutor.execute {
                synchronized(slotLocks[slot]) {
                    val svc = service
                    if (svc != null) svc.freeMidiFileSlot(slot)
                    loadedFilePerSlot.remove(slot)
                }
            }
        }
    }

    /** Stop a slot without freeing it (keeps mapping + loaded file). */
    fun stopSlotForNote(note: Int) = stopSlotForTrigger(note)

    /** Stop a slot for an encoded trigger key without freeing it. */
    fun stopSlotForTrigger(key: Int) {
        val slot = triggerSlotMap[key] ?: return
        slotExecutor.execute {
            synchronized(slotLocks[slot]) {
                val svc = service
                if (svc != null) svc.stopMidiFileSlot(slot)
                noteStateMachine.stopPlaying(key)
            }
        }
    }

    /** Reset the press detector entry for a trigger key (CC 128+cc / pitch bend 256). */
    private fun resetPressDetector(key: Int) {
        when {
            key in 128..255 -> ccPressDetector.reset(key - 128)
            key == 256 -> pbPressDetector.reset(0)
        }
    }

    /** Test-play: slot 15, generation counter, 3s auto-stop. */
    fun testPlay(cellId: Int, filePath: String, loop: Boolean, tempo: Double, channel: Int) {
        slotExecutor.execute {
            synchronized(slotLocks[testPlaySlot]) {
                val svc = service ?: return@synchronized
                val gen = testPlayGeneration + 1
                testPlayGeneration = gen
                if (testPlayPlaying) {
                    svc.stopMidiFileSlot(testPlaySlot)
                    testPlayPlaying = false
                    testPlayCellId = null
                    mainHandler.post { onTestPlayStateChanged?.invoke(null, false) }
                    cancelTestPlayAutoStop()
                    return@synchronized
                }
                var loadResult = svc.loadMidiFileSlot(
                    testPlaySlot, filePath, tempo, loop, channel, false
                )
                var retries = 0
                while (loadResult == -4 && retries < 3) {
                    Thread.sleep(50)
                    loadResult = svc.loadMidiFileSlot(
                        testPlaySlot, filePath, tempo, loop, channel, false
                    )
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
                    return@synchronized
                }
                svc.startMidiFileSlot(testPlaySlot)
                testPlayPlaying = true
                testPlayCellId = cellId
                mainHandler.post { onTestPlayStateChanged?.invoke(cellId, true) }
                cancelTestPlayAutoStop()
                val durationMs = try {
                    val info = JSONObject(svc.getMidiFileSlotInfo(testPlaySlot))
                    val lengthTicks = info.optLong("lengthTicks", 0L)
                    val ppq = info.optInt("ppq", 0)
                    if (lengthTicks > 0L && ppq > 0 && tempo > 0.0) {
                        (lengthTicks.toDouble() * 60000.0 / (tempo * ppq)).toLong() + 200
                    } else {
                        3000L
                    }
                } catch (_: Exception) {
                    3000L
                }
                testPlayRunnable = Runnable {
                    if (testPlayGeneration == gen) {
                        slotExecutor.execute {
                            synchronized(slotLocks[testPlaySlot]) {
                                // Re-check under lock: a new test-play may have started
                                // after the outer check (which only avoids queueing no-ops).
                                if (testPlayGeneration == gen) {
                                    svc.stopMidiFileSlot(testPlaySlot)
                                    testPlayPlaying = false
                                    testPlayCellId = null
                                    mainHandler.post { onTestPlayStateChanged?.invoke(null, false) }
                                }
                            }
                        }
                    }
                }
                mainHandler.postDelayed(testPlayRunnable!!, durationMs)
            }
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
            for (key in triggerSlotMap.keys) {
                val slot = triggerSlotMap[key] ?: continue
                synchronized(slotLocks[slot]) {
                    if (svc.isMidiFileSlotPlaying(slot)) {
                        svc.stopMidiFileSlot(slot)
                    }
                    noteStateMachine.stopPlaying(key)
                }
            }
            synchronized(slotLocks[testPlaySlot]) {
                if (testPlayPlaying) {
                    svc.stopMidiFileSlot(testPlaySlot)
                    testPlayPlaying = false
                    testPlayCellId = null
                    mainHandler.post { onTestPlayStateChanged?.invoke(null, false) }
                }
            }
            cancelTestPlayAutoStop()
        }
    }

    /**
     * Handle setting changes (loop/tempo/channel) for a mapped trigger.
     * loop/tempo: live update slot. channel: reload the slot (worker thread).
     * [key] is the encoded trigger key (SequencerActivity passes the raw note;
     * only NOTE cells reach this path — CC/PITCH_BEND cells are note-gated out
     * in the panel/activity, so their setting changes persist but do not
     * live-update a running slot).
     */
    fun onSettingChanged(key: Int, loop: Boolean, tempo: Double, channel: Int) {
        slotExecutor.execute {
            val svc = service ?: return@execute
            val slot = triggerSlotMap[key] ?: return@execute
            synchronized(slotLocks[slot]) {
                // Live loop/tempo
                svc.setMidiFileSlotLoop(slot, loop)
                svc.setMidiFileSlotTempo(slot, tempo)

                // Any setting change resets the toggle state. The panel persists the new
                // cell (store.set) before this callback runs, so comparing against
                // the store would read the new value. Unconditional reset is safe:
                // clean state → no-op; stale isPlaying (e.g. after a natural
                // non-looped end) → first press after the change is TOGGLE_ON;
                // a playing loop slot + press → triggerSlot's loop branch stops
                // it (correct toggle-off).
                noteStateMachine.stopPlaying(key)

                // Channel change → reload slot
                if (channel != loadedFilePerSlot[slot]?.second) {
                    val wasPlaying = svc.isMidiFileSlotPlaying(slot)
                    if (wasPlaying) svc.stopMidiFileSlot(slot)
                    var loadResult = svc.loadMidiFileSlot(
                        slot,
                        loadedFilePerSlot[slot]?.first ?: return@synchronized,
                        tempo, loop, channel, wasPlaying
                    )
                    // 4-thread pool: a concurrent triggerSlot may hold the slot (workerBusy).
                    // Bounded retry, same as triggerSlot.
                    var retries = 0
                    while (loadResult == -4 && retries < 5) {
                        Thread.sleep(20)
                        loadResult = svc.loadMidiFileSlot(
                            slot,
                            loadedFilePerSlot[slot]?.first ?: return@synchronized,
                            tempo, loop, channel, wasPlaying
                        )
                        retries++
                    }
                    if (loadResult == 0) {
                        val path = loadedFilePerSlot[slot]?.first ?: return@synchronized
                        loadedFilePerSlot[slot] = path to channel
                    } else {
                        mainHandler.post {
                            AppLogger.warn("TriggerController",
                                "Channel reload failed for slot $slot: $loadResult")
                        }
                    }
                }
            }
        }
    }

    // ── Helpers ──

    /** D6: 16 slots; round-robin for 17th+ mapping. Reserve slot 15 for test-play. */
    private fun allocateSlot(key: Int): Int {
        return triggerSlotMap.getOrPut(key) {
            val slot = nextSlotIndex.getAndIncrement() % 15
            slot
        }
    }

    /**
     * Allocate a slot for a learned trigger (public for SequencerActivity).
     * [key] is the encoded trigger key — the panel passes the raw note for NOTE
     * cells and 128+cc / 256 for CC / PITCH_BEND cells (see SequencerCell.triggerKey).
     */
    fun allocateSlotForKey(key: Int): Int {
        return triggerSlotMap.getOrPut(key) {
            val slot = nextSlotIndex.getAndIncrement() % 15
            slot
        }
    }

    /** Get the slot assigned to an encoded trigger key, or null. */
    fun getSlotForKey(key: Int): Int? = triggerSlotMap[key]

    /** Clear loaded-file tracking for a slot. */
    fun clearLoadedFile(slot: Int) {
        loadedFilePerSlot.remove(slot)
    }

    /** Worker-thread: warm the parsed-event cache for a file (no slot involved). */
    fun preloadFile(filePath: String) {
        slotExecutor.execute {
            val svc = service ?: return@execute
            svc.preloadMidiFile(filePath)
        }
    }

    /** Warm the cache for all assigned cells (startup). */
    fun preloadAll() {
        val s = store ?: return
        for (cell in s.all()) {
            if (cell.filePath.isNotEmpty()) preloadFile(cell.filePath)
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
