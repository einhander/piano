#include <jni.h>
#include <string>
#include "audio/OboeOutput.h"
#include "engine/NativeEngine.h"
#include "model/TransportState.h"

extern "C" {

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
        || state == oboe::StreamState::Started
        || state == oboe::StreamState::Running;
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

} // extern "C"