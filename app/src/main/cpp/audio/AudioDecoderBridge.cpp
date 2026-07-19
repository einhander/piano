#include "AudioDecoderBridge.h"

AudioDecoderBridge::AudioDecoderBridge() = default;
AudioDecoderBridge::~AudioDecoderBridge() = default;

bool AudioDecoderBridge::decode(const char* filePath, float** outData, int32_t* outFrames) {
    // MVP: placeholder — only WAV supported
    return false;
}

bool AudioDecoderBridge::decodeWav(const char* filePath, float** outData, int32_t* outFrames) {
    // MVP: placeholder
    // Will implement WAV parsing: read RIFF header, extract PCM data, convert to float
    return false;
}