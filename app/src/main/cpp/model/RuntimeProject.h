#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>

struct RuntimeProject {
    // Project metadata
    char name[128];
    double bpm;
    int32_t ppq;
    int16_t numerator;
    int16_t denominator;

    // Master controls
    float masterGain;
    int32_t polyphony;

    // Track count (max 16)
    int32_t trackCount;

    // Track configurations (immutable snapshot)
    struct TrackConfig {
        int32_t trackId;      // MIDI channel
        float volume;
        float pan;
        int32_t program;
        int32_t transpose;
        float velocityScale;
        int32_t clipCount;
    } tracks[16];

    // Clip configurations (immutable snapshot)
    struct ClipConfig {
        int32_t clipId;
        int32_t trackId;
        int64_t startTick;
        int64_t lengthTicks;
        int32_t eventCount;
    } clips[64];

    // SoundFont path
    char soundFontPath[512];

    // Initialize from JSON string (called from worker thread, NOT audio thread)
    static RuntimeProject* fromJson(const char* json);

    // Getters
    inline const char* getName() const { return name; }
    inline double getBpm() const { return bpm; }
    inline int32_t getPpq() const { return ppq; }
    inline float getMasterGain() const { return masterGain; }
    inline int32_t getPolyphony() const { return polyphony; }
    inline int32_t getTrackCount() const { return trackCount; }
    inline const char* getSoundFontPath() const { return soundFontPath; }
};