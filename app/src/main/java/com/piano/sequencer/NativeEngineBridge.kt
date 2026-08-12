package com.piano.sequencer

object NativeEngineBridge {
    init {
        System.loadLibrary("native-lib")
    }

    external fun nativeGetVersion(): String
    external fun nativeStartAudio(): Int
    external fun nativeStopAudio(): Int
    external fun nativeIsAudioPlaying(): Boolean
    external fun nativeGetUnderrunCount(): Int
    external fun nativeOpenAudio(): Int

    // NativeEngine
    external fun nativeInitEngine(sampleRate: Int, bufferSize: Int): Boolean
    external fun nativeLoadSoundFont(filePath: String): Int
    external fun nativeNoteOn(channel: Int, note: Int, velocity: Int)
    external fun nativeNoteOff(channel: Int, note: Int)
    external fun nativePanic()
    external fun nativeSetMasterGain(gain: Float)
    external fun nativeSendMidiMessage(status: Int, data1: Int, data2: Int)

    // Transport control
    external fun nativeSetBPM(bpm: Double)
    external fun nativeSetTransportState(state: Int)
    external fun nativeGetCurrentTick(): Double
    external fun nativeGetFramePosition(): Long

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

    // MIDI export
    external fun nativeWriteMidiFile(
        filePath: String,
        events: ByteArray,
        eventCount: Int,
        ppq: Int,
        tempo: Int
    ): Boolean
}