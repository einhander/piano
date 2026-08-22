package com.piano.sequencer

object NativeEngineBridge {
    init {
        System.loadLibrary("native-lib")
    }

    /**
     * Pre-load the LSP LADSPA bundle so the Android linker resolves its NEEDED
     * deps (libc++_shared.so, already mapped via native-lib) and registers the
     * soname in the app namespace. A later dlopen() by LadspaRegistry::open()
     * then finds it. Must run on a worker thread (it can throw on failure);
     * call from MainActivity during engine init so the result is logged.
     *
     * Returns true on success; on failure logs the exact reason to AppLogger
     * and logcat and returns false.
     */
    fun preloadLspBundle(context: android.content.Context): String? {
        val libDir = context.applicationInfo.nativeLibraryDir
        // 1) Load by library name (preferred; uses the linker search path).
        try {
            System.loadLibrary("lsp-plugins-ladspa")
            AppLogger.info("NativeEngineBridge", "loadLibrary(\"lsp-plugins-ladspa\") OK")
            // Loaded by soname; the native dlopen-by-soname fallback resolves it.
            return null
        } catch (e: Throwable) {
            AppLogger.error(
                "NativeEngineBridge",
                "loadLibrary(\"lsp-plugins-ladspa\") failed: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
        // 2) Fallback: load by absolute path from nativeLibraryDir. Captures a
        //    different (often more specific) error than the name-based load.
        val soPath = "$libDir/liblsp-plugins-ladspa.so"
        val exists = java.io.File(soPath).exists()
        AppLogger.info("NativeEngineBridge", "fallback System.load(\"$soPath\") exists=$exists")
        if (exists) {
            try {
                System.load(soPath)
                AppLogger.info("NativeEngineBridge", "System.load(\"$soPath\") OK")
                return null
            } catch (e: Throwable) {
                AppLogger.error(
                    "NativeEngineBridge",
                    "System.load(\"$soPath\") failed: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        } else {
            AppLogger.warn(
                "NativeEngineBridge",
                "liblsp-plugins-ladspa.so NOT on disk at $soPath " +
                    "(extractNativeLibs=false on this device)"
            )
        }
        // 3) Definitive fallback: extract the .so straight out of the installed
        //    APK into the executable codeCacheDir and System.load it there.
        //    On Android 11+/sdk 30+ (notably sdk 36) the platform does not
        //    extract prebuilt jniLibs to nativeLibraryDir nor register them in
        //    the loadable namespace, so neither System.loadLibrary nor dlopen
        //    by path/soname can find them. Reading the entry from our own APK
        //    and writing it to a location we control bypasses all of that.
        //    Returns the absolute path the native dlopen should use.
        return extractAndLoadFromApk(context)
    }

    /**
     * Copy lib/<abi>/liblsp-plugins-ladspa.so from the installed APK (sourceDir)
     * into getCodeCacheDir() and System.load it. Picks the first ABI whose entry
     * exists in the APK (the prebuilt is arm64-v8a only in v1).
     */
    private fun extractAndLoadFromApk(context: android.content.Context): String? {
        val apkPath = context.applicationInfo.sourceDir
        val outDir = context.codeCacheDir
        val soname = "liblsp-plugins-ladspa.so"
        val outFile = java.io.File(outDir, soname)
        AppLogger.info("NativeEngineBridge", "extracting $soname from APK ($apkPath) → $outFile")
        try {
            val zip = java.util.zip.ZipFile(apkPath)
            zip.use { z ->
                // Find the first supported ABI whose lib entry exists in the APK.
                val entryName = android.os.Build.SUPPORTED_ABIS
                    .map { "lib/$it/$soname" }
                    .firstOrNull { z.getEntry(it) != null }
                if (entryName == null) {
                    AppLogger.error(
                        "NativeEngineBridge",
                        "no lib/<abi>/$soname entry found in APK for any of " +
                            android.os.Build.SUPPORTED_ABIS.joinToString()
                    )
                    return null
                }
                AppLogger.info("NativeEngineBridge", "APK entry: $entryName")
                z.getInputStream(z.getEntry(entryName)).use { input ->
                    java.io.FileOutputStream(outFile).use { out ->
                        input.copyTo(out)
                    }
                }
            }
            // codeCacheDir is on a filesystem that permits exec; ensure perms.
            outFile.setExecutable(true, true)
            AppLogger.info(
                "NativeEngineBridge",
                "extracted ${outFile.length()} bytes, exec=${outFile.canExecute()}"
            )
            System.load(outFile.absolutePath)
            AppLogger.info("NativeEngineBridge", "System.load(\"${outFile.absolutePath}\") OK")
            return outFile.absolutePath
        } catch (e: Throwable) {
            AppLogger.error(
                "NativeEngineBridge",
                "extractAndLoadFromApk failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            return null
        }
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

    // ── Master effect chain (LSP) — worker thread only ──
    // Loads the LSP LADSPA bundle and prepares the fixed 3-effect chain
    // (EQ → Compressor → Limiter). Returns the number of effects available
    // (0..3). If the bundle cannot be opened, returns 0 and the chain stays
    // bypassed (the engine keeps running). Call from a worker thread.
    external fun nativeLoadMasterEffectBundle(soPath: String): Int
    external fun nativeIsMasterEffectChainAvailable(): Boolean
    external fun nativeGetMasterEffectCount(): Int
    external fun nativeSetMasterEffectEnabled(slot: Int, enabled: Boolean)
    external fun nativeIsMasterEffectEnabled(slot: Int): Boolean
    external fun nativeSetMasterEffectParameter(slot: Int, parameterId: Int, value: Float)
    external fun nativeGetMasterEffectParameter(slot: Int, parameterId: Int): Float
    external fun nativeGetMasterEffectStableId(slot: Int): String
    // Human-readable reason for the last loadMasterEffectBundle failure
    // (empty on success). Safe from any thread.
    external fun nativeGetMasterEffectLoadError(): String
    // Static parameter metadata for the UI (safe from any thread).
    // nativeGetMasterEffectParamInfo returns null for an out-of-range index,
    // else a FloatArray of 7: [paramId, min, max, def, log, integer, toggled]
    // (the last three are 0.0/1.0 flags).
    external fun nativeGetMasterEffectParamCount(slot: Int): Int
    external fun nativeGetMasterEffectParamInfo(slot: Int, index: Int): FloatArray?
    external fun nativeGetMasterEffectParamName(slot: Int, index: Int): String

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