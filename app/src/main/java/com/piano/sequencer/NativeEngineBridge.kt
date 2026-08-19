package com.piano.sequencer

object NativeEngineBridge {
    init {
        System.loadLibrary("native-lib")
    }

    external fun nativeInit(): Boolean
    external fun nativeShutdown()
    external fun nativeGetVersion(): String
    external fun nativeStartAudio(): Int
    external fun nativeStopAudio(): Int
    external fun nativeIsAudioPlaying(): Boolean
    external fun nativeIsEngineInitialized(): Boolean
    external fun nativeGetUnderrunCount(): Int
    external fun nativeOpenAudio(): Int

    // NativeEngine
    external fun nativeInitEngine(sampleRate: Int, bufferSize: Int): Boolean
    external fun nativeLoadSoundFont(filePath: String): Int
    external fun nativeNoteOn(channel: Int, note: Int, velocity: Int)
    external fun nativeNoteOff(channel: Int, note: Int)
    external fun nativePanic()
    external fun nativeSetMasterGain(gain: Float)
    external fun nativeSetPolyphony(polyphony: Int)
    external fun nativeGetPolyphony(): Int
    external fun nativeGetMasterGain(): Float

    // Reverb / Chorus / Interpolation (Fix #10-12). Worker thread only.
    external fun nativeSetReverb(on: Boolean)
    external fun nativeSetChorus(on: Boolean)
    external fun nativeSetInterps(method: Int)
    external fun nativeGetReverb(): Int
    external fun nativeGetChorus(): Int
    external fun nativeGetInterps(): Int

    // Sample-rate coordination (Fix #3). nativeGetSampleRate returns the ACTUAL
    // Oboe stream rate (device rate) so it can be passed to nativeInitEngine /
    // nativeUpdateSampleRate instead of the hardcoded 48000.
    external fun nativeGetSampleRate(): Int
    external fun nativeUpdateSampleRate(sampleRate: Int)

    // Oboe buffer size control (Fix #4). Worker thread only.
    external fun nativeSetAutoTune(autoTune: Boolean)
    external fun nativeIsAutoTune(): Boolean
    external fun nativeSetBufferSizeInFrames(frames: Int): Int

    // Diagnostics (Part A). Worker thread only — never the audio callback.
    external fun nativeGetActiveVoices(): Int
    external fun nativeGetProcessedFrames(): Long
    external fun nativeGetCallbackCount(): Long
    external fun nativeGetMidiQueueDrops(): Int
    external fun nativeGetSynthCmdQueueDrops(): Int
    external fun nativeGetMidiQueueDepth(): Int
    external fun nativeGetLiveMidiQueueDepth(): Int
    // [perf]: number of clips currently in the clip scheduler (1 Hz line).
    external fun nativeGetActiveClipCount(): Int
    // [perf]: duration (ms) of the most recent SF2 load (one-time dump).
    external fun nativeGetSf2LoadMs(): Long
    external fun nativeGetBufferSizeInFrames(): Int
    external fun nativeGetBufferCapacityInFrames(): Int
    external fun nativeGetLatencyMillis(): Int
    external fun nativeGetSharingMode(): Int
    external fun nativeGetPerformanceMode(): Int
    // [perf]: frames per Oboe burst (one-time dump; buffer = N×burst).
    external fun nativeGetFramesPerBurst(): Int

    external fun nativeUnloadSoundFonts()
    external fun nativeGetSoundFontCount(): Int
    external fun nativeGetSoundFontPath(): String

    // Instruments
    external fun nativeGetInstruments(): String
    external fun nativeSetChannelProgram(channel: Int, bank: Int, program: Int): Boolean
    external fun nativeGetChannelProgram(channel: Int): Int
    external fun nativeSendMidiMessage(status: Int, data1: Int, data2: Int)

    // Transport control
    external fun nativeSetBPM(bpm: Double)
    external fun nativeSetTransportState(state: Int)
    external fun nativeGetCurrentTick(): Double
    external fun nativeGetFramePosition(): Long
    external fun nativeGetBPM(): Double
    external fun nativeGetPpq(): Int

    // Project loading
    external fun nativeLoadProject(json: String)

    // Scene management
    external fun nativeSwitchScene(sceneId: Int)
    external fun nativeCurrentSceneId(): Int
    external fun nativeHasSceneChanged(): Boolean
    external fun nativeAcknowledgeSceneChange()

    // Launch quantization
    external fun nativeSetQuantizationGrid(grid: Int)
    external fun nativeGetQuantizationGrid(): Int
    external fun nativeIsLaunchPending(): Boolean
    external fun nativeAcknowledgeLaunch()
    external fun nativeScheduleLaunch(sceneId: Int, grid: Int, currentFrame: Long): Long

    // Scene navigation
    external fun nativeRegisterScene(sceneId: Int, name: String)
    external fun nativeNextScene(): Int
    external fun nativePreviousScene(): Int
    external fun nativeGetSceneCount(): Int

    // Launch queue
    external fun nativeQueueSceneLaunch(sceneId: Int, targetFrame: Long): Boolean
    external fun nativeGetLaunchQueueDepth(): Int

    // Clip transport sync
    external fun nativeSetClipTransportSync(clipId: Int, enabled: Boolean)
    external fun nativeSetClipStartTick(clipId: Int, startTick: Long)
    external fun nativeSetClipEndTick(clipId: Int, endTick: Long)
    external fun nativeSetClipLoop(clipId: Int, loop: Boolean)

    // Mixer controls
    external fun nativeSetTrackVolume(trackId: Int, volume: Float)
    external fun nativeSetTrackPan(trackId: Int, pan: Float)
    external fun nativeSetTrackMute(trackId: Int, mute: Boolean)
    external fun nativeSetTrackSolo(trackId: Int, solo: Boolean)
    external fun nativeGetTrackPeakMeter(trackId: Int): Float

    // Master bus controls
    external fun nativeSetMasterVolume(volume: Float)
    external fun nativeGetMasterPeakMeter(): Float

    // Count-in metronome
    external fun nativeStartCountIn(beats: Int): Long
    external fun nativeIsCountingIn(): Boolean
    external fun nativeGetCountInEndFrame(): Long

    // Recording control
    external fun nativeStartRecording()
    external fun nativeStopRecording()
    external fun nativeSetRecordArmed(trackId: Int, armed: Boolean)
    external fun nativeIsRecording(): Boolean
    external fun nativeSetOverdub(overdub: Boolean)

    // MIDI file slot playback
    // NOTE: call from a worker thread, never the main thread.
    // nativeLoadMidiFileSlot does blocking file I/O + parse (tens of ms).
    external fun nativeLoadMidiFileSlot(slot: Int, filePath: String, tempo: Double, loop: Boolean, channel: Int, startAfterLoad: Boolean): Int
    external fun nativePreloadMidiFile(filePath: String): Int
    external fun nativeStartMidiFileSlot(slot: Int): Int
    external fun nativeStopMidiFileSlot(slot: Int): Int
    external fun nativeIsMidiFileSlotPlaying(slot: Int): Boolean
    external fun nativeSetMidiFileSlotLoop(slot: Int, loop: Boolean)
    external fun nativeSetMidiFileSlotTempo(slot: Int, bpm: Double)
    external fun nativeGetMidiFileSlotInfo(slot: Int): String
    external fun nativeFreeMidiFileSlot(slot: Int)

    // Timing trace
    external fun nativeGetMidiFileSlotLoadFrame(slot: Int): Long
    external fun nativeGetMidiFileSlotStartFrame(slot: Int): Long

    // Recorded MIDI export
    // NOTE: call from a worker thread, never the main thread.
    // Recorded ticks follow the transport bpm/ppq; the export tempo param must match.
    external fun nativeGetRecordedEventCount(): Int
    external fun nativeWriteRecordedMidiFile(filePath: String, ppq: Int, tempo: Int): Boolean
}