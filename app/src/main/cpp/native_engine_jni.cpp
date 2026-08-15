#include <jni.h>
#include <string>
#include "audio/OboeOutput.h"
#include "engine/NativeEngine.h"
#include "model/TransportState.h"
#include "engine/MidiRecorder.h"

extern "C" {

// Create the singleton instances. Must be called once before any other JNI function.
JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeInit(JNIEnv* env, jclass) {
    try {
        new OboeOutput();
        new NativeEngine();
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
    return state == oboe::StreamState::Open
        || state == oboe::StreamState::Starting
        || state == oboe::StreamState::Started;
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

// MIDI export
JNIEXPORT jboolean JNICALL
Java_com_piano_sequencer_NativeEngineBridge_nativeWriteMidiFile(
    JNIEnv* env, jclass, jstring filePath, jbyteArray events,
    jint eventCount, jint ppq, jint tempo) {
    NativeEngine* inst = NativeEngine::getInstance();
    if (inst == nullptr) return false;

    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return false;

    jbyte* evtData = env->GetByteArrayElements(events, nullptr);
    if (!evtData && eventCount > 0) {
        env->ReleaseStringUTFChars(filePath, path);
        return false;
    }

    // Convert jbyteArray to std::vector<RecordedMidiEvent>
    std::vector<RecordedMidiEvent> recordedEvents;
    if (eventCount > 0 && evtData) {
        RecordedMidiEvent* eventsPtr = reinterpret_cast<RecordedMidiEvent*>(evtData);
        recordedEvents.assign(eventsPtr, eventsPtr + eventCount);
    }

    jboolean result = inst->writeMidiFile(path, recordedEvents, static_cast<int>(ppq), static_cast<uint32_t>(tempo));

    if (evtData) {
        env->ReleaseByteArrayElements(events, evtData, JNI_ABORT);
    }
    env->ReleaseStringUTFChars(filePath, path);

    return result;
}

} // extern "C"