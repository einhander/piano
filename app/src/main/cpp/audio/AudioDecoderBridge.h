#pragma once

#include <cstdint>

// Android MediaCodec decode bridge
// Decodes MP3/AAC/FLAC/OGG to PCM on worker thread
// MVP: placeholder — only WAV supported
class AudioDecoderBridge {
public:
    AudioDecoderBridge();
    ~AudioDecoderBridge();

    // Decode audio file to PCM float buffer
    // Returns true on success
    bool decode(const char* filePath, float** outData, int32_t* outFrames);

    // Decode WAV file (simple PCM)
    bool decodeWav(const char* filePath, float** outData, int32_t* outFrames);
};