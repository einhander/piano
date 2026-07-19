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
}