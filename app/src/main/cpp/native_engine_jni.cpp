#include <jni.h>
#include <cstdio>
#include <string>
#include <vector>
#include "audio/OboeOutput.h"
#include "engine/NativeEngine.h"
#include "model/TransportState.h"
#include "engine/MidiRecorder.h"
#include "synth/FluidSynthEngine.h"
#include "diagnostics/CrashHandler.h"

extern "C" {

// Install the native crash handler (writes a backtrace to <path>/native_crash.log).
// Called from Kotlin before any risky native library load so a crash there is
// captured even though the in-memory AppLogger is lost on process death.
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeInitCrashHandler(JNIEnv* env, jclass, jstring path) {
    const char* p = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    std::string full;
    if (p) {
        full = p;
        if (!full.empty() && full.back() != '/') full += '/';
        full += "native_crash.log";
        env->ReleaseStringUTFChars(path, p);
    }
    crash::install(full.c_str());
}

// Create the singleton instances. Must be called once before any other JNI function.
JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeInit(JNIEnv* env, jclass) {
    try {
        // Idempotent: the singletons are process-level and must survive activity
        // recreation (AGENTS.md). Re-creating them on every service bind leaked
        // the old Oboe stream + NativeEngine (and its MIDI thread) on every
        // back/forward navigation.
        if (OboeOutput::getInstance() == nullptr) {
            new OboeOutput();
        }
        if (NativeEngine::getInstance() == nullptr) {
            new NativeEngine();
        }
        return true;
    } catch (...) {
        return false;
    }
}

// Destroy the singleton instances. Call on app exit.
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeShutdown(JNIEnv* env, jclass) {
    NativeEngine* engine = NativeEngine::getInstance();
    if (engine) {
        delete engine;
    }
    OboeOutput* output = OboeOutput::getInstance();
    if (output) {
        delete output;
    }
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeOpenAudio(JNIEnv* env, jclass) {
    OboeOutput* inst = OboeOutput::getInstance();
    if (inst == nullptr) return -1;
    return static_cast<jint>(inst->open());
}

JNIEXPORT jstring JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("0.0.0");
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeStartAudio(JNIEnv* env, jclass) {
    OboeOutput* inst = OboeOutput::getInstance();
    if (inst == nullptr) {
        return -1;
    }
    return static_cast<jint>(inst->start());
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeStopAudio(JNIEnv* env, jclass) {
    OboeOutput* inst = OboeOutput::getInstance();
    if (inst == nullptr) {
        return -1;
    }
    return static_cast<jint>(inst->stop());
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeIsAudioPlaying(JNIEnv* env, jclass) {
    OboeOutput* inst = OboeOutput::getInstance();
    if (inst == nullptr) {
        return false;
    }
    oboe::StreamState state = inst->getState();
    // Only "Started" counts as playing — an open-but-not-started stream must
    // not be reported as playing (matches NativeEngine::isAudioPlaying).
    return state == oboe::StreamState::Started;
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeIsEngineInitialized(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) {
        return false;
    }
    return inst->isEngineInitialized();
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetUnderrunCount(JNIEnv* env, jclass) {
    OboeOutput* inst = OboeOutput::getInstance();
    if (inst == nullptr) {
        return 0;
    }
    return static_cast<jint>(inst->getUnderrunCount());
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeInitEngine(JNIEnv* env, jclass, jint sampleRate, jint bufferSize) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) {
        return false;
    }
    return inst->init(static_cast<int>(sampleRate), static_cast<int>(bufferSize));
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeLoadSoundFont(JNIEnv* env, jclass, jstring filePath) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) {
        return -1;
    }
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    jint result = -1;
    if (path != nullptr) {
        result = inst->loadSoundFont(path);
        env->ReleaseStringUTFChars(filePath, path);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeNoteOn(JNIEnv* env, jclass, jint channel, jint note, jint velocity) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->noteOn(static_cast<int>(channel), static_cast<int>(note), static_cast<int>(velocity));
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeNoteOff(JNIEnv* env, jclass, jint channel, jint note) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->noteOff(static_cast<int>(channel), static_cast<int>(note));
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativePanic(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->panic();
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetMasterGain(JNIEnv* env, jclass, jfloat gain) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setMasterGain(static_cast<float>(gain));
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetPolyphony(JNIEnv* env, jclass, jint polyphony) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->setPolyphony(static_cast<int>(polyphony));
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetPolyphony(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getPolyphony());
    return 0;
}

JNIEXPORT jfloat JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMasterGain(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jfloat>(inst->getMasterGain());
    return 0.0f;
}

// ── Reverb / Chorus / Interpolation (Fix #10-12) ──
// All enqueue to the lock-free command queue; applied by the audio thread.

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetReverb(JNIEnv* env, jclass, jboolean on) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->setReverb(on);
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetChorus(JNIEnv* env, jclass, jboolean on) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->setChorus(on);
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetInterps(JNIEnv* env, jclass, jint method) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->setInterps(static_cast<int>(method));
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetReverb(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getReverb());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetChorus(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getChorus());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetInterps(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getInterps());
    return 4;
}

// ── Sample-rate coordination (Fix #3) ──
// nativeGetSampleRate returns the ACTUAL Oboe stream rate (the device rate),
// so Kotlin can pass it to nativeInitEngine / nativeUpdateSampleRate instead
// of the hardcoded 48000.

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetSampleRate(JNIEnv* env, jclass) {
    OboeOutput* inst = OboeOutput::getInstance();
    if (inst) return static_cast<jint>(inst->getSampleRate());
    return 48000;
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeUpdateSampleRate(JNIEnv* env, jclass, jint sampleRate) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->updateSampleRate(static_cast<int>(sampleRate));
}

// ── Oboe buffer size control (Fix #4) ──

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetAutoTune(JNIEnv* env, jclass, jboolean autoTune) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->setAutoTune(autoTune);
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeIsAutoTune(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->isAutoTune();
    return true;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetBufferSizeInFrames(JNIEnv* env, jclass, jint frames) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->setBufferSizeInFrames(static_cast<int>(frames)));
    return -1;
}

// ── Diagnostics (Part A) ──
// All worker-thread reads of atomics / benign ints. Never called from the
// audio callback.

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetActiveVoices(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getActiveVoices());
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetProcessedFrames(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->getProcessedFrames();
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetCallbackCount(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->getCallbackCount();
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMidiQueueDrops(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getMidiQueueDrops());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetSynthCmdQueueDrops(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getSynthCmdQueueDrops());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMidiQueueDepth(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getMidiQueueDepth());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetLiveMidiQueueDepth(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getLiveMidiQueueDepth());
    return 0;
}

// [perf]: number of clips currently in the clip scheduler (1 Hz line).
JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetActiveClipCount(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getActiveClipCount());
    return 0;
}

// [perf]: duration (ms) of the most recent SF2 load (one-time dump).
JNIEXPORT jlong JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetSf2LoadMs(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->getSf2LoadMs();
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetBufferSizeInFrames(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getBufferSizeInFrames());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetBufferCapacityInFrames(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getBufferCapacityInFrames());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetLatencyMillis(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getLatencyMillis());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetSharingMode(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getSharingMode());
    return 1;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetPerformanceMode(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getPerformanceMode());
    return 0;
}

// [perf]: frames per Oboe burst (one-time dump; buffer = N×burst).
JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetFramesPerBurst(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getFramesPerBurst());
    return 0;
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeUnloadSoundFonts(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->unloadSoundFonts();
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetSoundFontCount(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getSoundFontCount());
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetSoundFontPath(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return env->NewStringUTF("");
    std::string path = inst->getSoundFontPath();
    jstring result = env->NewStringUTF(path.c_str());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return env->NewStringUTF("");
    }
    return result;
}

// JSON-escape a string for embedding in a JSON string literal
static std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

// Returns JSON array: [{"name":"...","bank":0,"program":0}, ...]
// Empty array "[]" if no SoundFont is loaded.
JNIEXPORT jstring JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetInstruments(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return env->NewStringUTF("[]");
    std::vector<InstrumentInfo> instruments = inst->getInstruments();
    std::string json = "[";
    for (size_t i = 0; i < instruments.size(); i++) {
        if (i > 0) json += ",";
        json += "{\"name\":\"";
        json += jsonEscape(instruments[i].name);
        json += "\",\"bank\":";
        json += std::to_string(instruments[i].bank);
        json += ",\"program\":";
        json += std::to_string(instruments[i].program);
        json += "}";
    }
    json += "]";
    jstring result = env->NewStringUTF(json.c_str());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return env->NewStringUTF("[]");
    }
    return result;
}

// Returns true if the program was applied, false if not (no engine, invalid channel).
JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetChannelProgram(
    JNIEnv* env, jclass, jint channel, jint bank, jint program) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return JNI_FALSE;
    return inst->setChannelProgram(static_cast<int>(channel),
                                   static_cast<int>(bank),
                                   static_cast<int>(program)) ? JNI_TRUE : JNI_FALSE;
}

// Returns (bank << 8) | program, or -1 if unavailable.
JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetChannelProgram(
    JNIEnv* env, jclass, jint channel) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return -1;
    int bank = 0, program = 0;
    if (!inst->getChannelProgram(static_cast<int>(channel), bank, program)) {
        return -1;
    }
    return static_cast<jint>((bank << 8) | program);
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSendMidiMessage(
    JNIEnv* env, jclass, jint status, jint data1, jint data2) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return;
    inst->enqueueMidiMessage(
        static_cast<uint8_t>(status & 0xFF),
        static_cast<uint8_t>(data1 & 0xFF),
        static_cast<uint8_t>(data2 & 0xFF),
        0
    );
}

// Transport control
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetBPM(JNIEnv* env, jclass, jdouble bpm) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setBPM(bpm);
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetTransportState(JNIEnv* env, jclass, jint state) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setTransportState(static_cast<int>(state));
    }
}

JNIEXPORT jdouble JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetCurrentTick(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->getCurrentTick();
    return 0.0;
}

JNIEXPORT jlong JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetFramePosition(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->getFramePosition();
    return 0;
}

JNIEXPORT jdouble JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetBPM(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->getBPM();
    return 120.0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetPpq(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getPpq());
    return 960;
}

// Project loading
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeLoadProject(JNIEnv* env, jclass, jstring json) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return;
    const char* jStr = env->GetStringUTFChars(json, nullptr);
    if (jStr) {
        inst->loadProject(jStr);
        env->ReleaseStringUTFChars(json, jStr);
    }
}

// Scene management
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSwitchScene(JNIEnv* env, jclass, jint sceneId) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->switchScene(static_cast<int32_t>(sceneId));
    }
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeCurrentSceneId(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->currentSceneId());
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeHasSceneChanged(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->hasSceneChanged();
    return false;
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeAcknowledgeSceneChange(JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->acknowledgeSceneChange();
    }
}

// Mixer controls
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetTrackVolume(
    JNIEnv* env, jclass, jint trackId, jfloat volume) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setTrackVolume(static_cast<int>(trackId), static_cast<float>(volume));
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetTrackPan(
    JNIEnv* env, jclass, jint trackId, jfloat pan) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setTrackPan(static_cast<int>(trackId), static_cast<float>(pan));
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetTrackMute(
    JNIEnv* env, jclass, jint trackId, jboolean mute) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setTrackMute(static_cast<int>(trackId), mute != 0);
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetTrackSolo(
    JNIEnv* env, jclass, jint trackId, jboolean solo) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setTrackSolo(static_cast<int>(trackId), solo != 0);
    }
}

JNIEXPORT jfloat JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetTrackPeakMeter(
    JNIEnv* env, jclass, jint trackId) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jfloat>(inst->getTrackPeakMeter(static_cast<int>(trackId)));
    return 0.0f;
}

// Master bus controls
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetMasterVolume(
    JNIEnv* env, jclass, jfloat volume) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setMasterVolume(static_cast<float>(volume));
    }
}

JNIEXPORT jfloat JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMasterPeakMeter(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jfloat>(inst->getMasterPeakMeter());
    return 0.0f;
}

// ── Master effect chain (LSP) ──
// Worker-thread only (touches the LADSPA bundle / dlopen).
JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeLoadMasterEffectBundle(
    JNIEnv* env, jclass, jstring soPath) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) {
        return 0;
    }
    jint result = 0;
    if (soPath != nullptr) {
        const char* path = env->GetStringUTFChars(soPath, nullptr);
        if (path != nullptr) {
            result = inst->loadMasterEffectBundle(path);
            env->ReleaseStringUTFChars(soPath, path);
        }
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeIsMasterEffectChainAvailable(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jboolean>(inst->isMasterEffectChainAvailable());
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMasterEffectLoadError(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    const char* err = (inst != nullptr) ? inst->getMasterEffectLoadError() : "";
    if (err == nullptr) err = "";
    return env->NewStringUTF(err);
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMasterEffectCount(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getMasterEffectCount());
    return 0;
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetMasterEffectEnabled(
    JNIEnv* env, jclass, jint slot, jboolean enabled) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setMasterEffectEnabled(static_cast<int>(slot),
                                     static_cast<bool>(enabled));
    }
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeIsMasterEffectEnabled(
    JNIEnv* env, jclass, jint slot) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jboolean>(inst->isMasterEffectEnabled(static_cast<int>(slot)));
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetMasterEffectParameter(
    JNIEnv* env, jclass, jint slot, jint parameterId, jfloat value) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setMasterEffectParameter(static_cast<int>(slot),
                                       static_cast<int>(parameterId),
                                       static_cast<float>(value));
    }
}

JNIEXPORT jfloat JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMasterEffectParameter(
    JNIEnv* env, jclass, jint slot, jint parameterId) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jfloat>(inst->getMasterEffectParameter(
        static_cast<int>(slot), static_cast<int>(parameterId)));
    return 0.0f;
}

JNIEXPORT jstring JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMasterEffectStableId(
    JNIEnv* env, jclass, jint slot) {
    NativeEngine* inst = NativeEngine::getInstance();
    const char* id = (inst) ? inst->getMasterEffectStableId(static_cast<int>(slot)) : "";
    return env->NewStringUTF(id ? id : "");
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMasterEffectParamCount(
    JNIEnv* env, jclass, jint slot) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getMasterEffectParamCount(static_cast<int>(slot)));
    return 0;
}

JNIEXPORT jfloatArray JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMasterEffectParamInfo(
    JNIEnv* env, jclass, jint slot, jint index) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return nullptr;
    uint32_t paramId = 0;
    float minValue = 0, maxValue = 0, defaultValue = 0;
    bool logarithmic = false, integer = false, toggled = false;
    if (!inst->getMasterEffectParamInfo(static_cast<int>(slot), static_cast<int>(index),
                                       paramId, minValue, maxValue, defaultValue,
                                       logarithmic, integer, toggled)) {
        return nullptr;
    }
    // [paramId, min, max, def, log, integer, toggled]
    jfloat info[7] = {
        static_cast<jfloat>(paramId),
        minValue, maxValue, defaultValue,
        logarithmic ? 1.0f : 0.0f,
        integer ? 1.0f : 0.0f,
        toggled ? 1.0f : 0.0f,
    };
    jfloatArray arr = env->NewFloatArray(7);
    if (arr != nullptr) env->SetFloatArrayRegion(arr, 0, 7, info);
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMasterEffectParamName(
    JNIEnv* env, jclass, jint slot, jint index) {
    NativeEngine* inst = NativeEngine::getInstance();
    const char* name = (inst) ? inst->getMasterEffectParamName(static_cast<int>(slot),
                                                              static_cast<int>(index)) : "";
    return env->NewStringUTF(name ? name : "");
}

// Launch quantization
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetQuantizationGrid(
    JNIEnv* env, jclass, jint grid) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setQuantizationGrid(static_cast<int32_t>(grid));
    }
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetQuantizationGrid(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getQuantizationGrid());
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeIsLaunchPending(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->isLaunchPending();
    return false;
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeAcknowledgeLaunch(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->acknowledgeLaunch();
    }
}

JNIEXPORT jlong JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeScheduleLaunch(
    JNIEnv* env, jclass, jint sceneId, jint grid, jlong currentFrame) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jlong>(
        inst->scheduleLaunch(static_cast<int32_t>(sceneId),
                             static_cast<int32_t>(grid),
                             static_cast<int64_t>(currentFrame)));
    return 0;
}

// Scene navigation
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeRegisterScene(
    JNIEnv* env, jclass, jint sceneId, jstring name) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return;
    const char* jStr = env->GetStringUTFChars(name, nullptr);
    if (jStr) {
        inst->registerScene(static_cast<int32_t>(sceneId), jStr);
        env->ReleaseStringUTFChars(name, jStr);
    } else {
        inst->registerScene(static_cast<int32_t>(sceneId), nullptr);
    }
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeNextScene(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->nextScene());
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativePreviousScene(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->previousScene());
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetSceneCount(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getSceneCount());
    return 0;
}

// Launch queue
JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeQueueSceneLaunch(
    JNIEnv* env, jclass, jint sceneId, jlong targetFrame) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->queueSceneLaunch(
        static_cast<int32_t>(sceneId),
        static_cast<int64_t>(targetFrame));
    return false;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetLaunchQueueDepth(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jint>(inst->getLaunchQueueDepth());
    return 0;
}

// Clip transport sync
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetClipTransportSync(
    JNIEnv* env, jclass, jint clipId, jboolean enabled) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setClipTransportSync(static_cast<int32_t>(clipId), enabled != 0);
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetClipStartTick(
    JNIEnv* env, jclass, jint clipId, jlong startTick) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setClipStartTick(static_cast<int32_t>(clipId),
                               static_cast<int64_t>(startTick));
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetClipEndTick(
    JNIEnv* env, jclass, jint clipId, jlong endTick) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setClipEndTick(static_cast<int32_t>(clipId),
                             static_cast<int64_t>(endTick));
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetClipLoop(
    JNIEnv* env, jclass, jint clipId, jboolean loop) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setClipLoop(static_cast<int32_t>(clipId), loop != 0);
    }
}

// Count-in metronome
JNIEXPORT jlong JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeStartCountIn(
    JNIEnv* env, jclass, jint beats) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jlong>(inst->startCountIn(static_cast<int>(beats)));
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeIsCountingIn(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->isCountingIn();
    return false;
}

JNIEXPORT jlong JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetCountInEndFrame(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return static_cast<jlong>(inst->getCountInEndFrame());
    return 0;
}

// Recording control
JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeStartRecording(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->startRecording();
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeStopRecording(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->stopRecording();
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetRecordArmed(
    JNIEnv* env, jclass, jint trackId, jboolean armed) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->setRecordArm(static_cast<int>(trackId), armed != 0);
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeIsRecording(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) return inst->isRecording();
    return false;
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetOverdub(
    JNIEnv* env, jclass, jboolean overdub) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) inst->setOverdub(overdub != 0);
}

// ── MIDI file slot playback ──
// Worker-thread functions: call from a worker thread, never the main thread.
// loadMidiFileSlot does blocking file I/O + parse (tens of ms).

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeLoadMidiFileSlot(
    JNIEnv* env, jclass, jint slot, jstring filePath, jdouble tempo, jboolean loop, jint channel, jboolean startAfterLoad) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return -1;

    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return -1;

    jint result = inst->loadMidiFileSlot(
        static_cast<int>(slot),
        path,
        static_cast<float>(tempo),
        loop != 0,
        static_cast<int>(channel),
        startAfterLoad != 0
    );

    env->ReleaseStringUTFChars(filePath, path);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativePreloadMidiFile(
    JNIEnv* env, jclass, jstring filePath) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return -1;

    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return -1;

    jint result = inst->preloadMidiFile(path);

    env->ReleaseStringUTFChars(filePath, path);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeStartMidiFileSlot(
    JNIEnv* env, jclass, jint slot) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return -1;
    inst->startMidiFileSlot(static_cast<int>(slot));
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeStopMidiFileSlot(
    JNIEnv* env, jclass, jint slot) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return -1;
    inst->stopMidiFileSlot(static_cast<int>(slot));
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeIsMidiFileSlotPlaying(
    JNIEnv* env, jclass, jint slot) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return JNI_FALSE;
    return inst->isMidiFileSlotPlaying(static_cast<int>(slot)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetMidiFileSlotLoop(
    JNIEnv* env, jclass, jint slot, jboolean loop) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setMidiFileSlotLoop(static_cast<int>(slot), loop != 0);
    }
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeSetMidiFileSlotTempo(
    JNIEnv* env, jclass, jint slot, jdouble bpm) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->setMidiFileSlotTempo(static_cast<int>(slot), static_cast<float>(bpm));
    }
}

JNIEXPORT jstring JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMidiFileSlotInfo(
    JNIEnv* env, jclass, jint slot) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return env->NewStringUTF("{}");

    MidiFilePlayer::SlotInfo info = inst->getMidiFileSlotInfo(static_cast<int>(slot));

    char buf[256];
    snprintf(buf, sizeof(buf),
        "{\"eventCount\":%d,\"lengthTicks\":%lld,\"ppq\":%d,\"initialTempo\":%.1f}",
        info.eventCount,
        (long long)info.lengthTicks,
        info.ppq,
        info.initialTempo);

    jstring result = env->NewStringUTF(buf);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return env->NewStringUTF("{}");
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeFreeMidiFileSlot(
    JNIEnv* env, jclass, jint slot) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst) {
        inst->freeMidiFileSlot(static_cast<int>(slot));
    }
}

// ── Timing trace: slot LOAD/START frame positions ──

JNIEXPORT jlong JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMidiFileSlotLoadFrame(
    JNIEnv* env, jclass, jint slot) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return -1;
    return static_cast<jlong>(inst->getMidiFileSlotLoadFrame(static_cast<int>(slot)));
}

JNIEXPORT jlong JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetMidiFileSlotStartFrame(
    JNIEnv* env, jclass, jint slot) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return -1;
    return static_cast<jlong>(inst->getMidiFileSlotStartFrame(static_cast<int>(slot)));
}

// ── Recorded MIDI export ──

JNIEXPORT jint JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeGetRecordedEventCount(
    JNIEnv* env, jclass) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return 0;
    return static_cast<jint>(inst->getRecordedEventCount());
}

JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeWriteRecordedMidiFile(
    JNIEnv* env, jclass, jstring filePath, jint ppq, jint tempo) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return JNI_FALSE;

    jboolean result = inst->writeRecordedMidiFile(
        path, static_cast<int>(ppq), static_cast<uint32_t>(tempo));

    env->ReleaseStringUTFChars(filePath, path);
    return result;
}

} // extern "C"