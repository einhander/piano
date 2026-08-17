#include "Mixer.h"
#include <cmath>
#include <cstring>
#include <algorithm>

Mixer::Mixer() {}
Mixer::~Mixer() {
    // Free pre-allocated buffers (destructor, not audio callback — safe to use delete[])
    for (int i = 0; i < kMaxTracks; i++) {
        delete[] mTracks[i].buffer;
        mTracks[i].buffer = nullptr;
    }
    delete[] mOutputBuffer;
    mOutputBuffer = nullptr;
}

void Mixer::init(int trackCount, int maxFramesPerBuffer) {
    if (trackCount < 1 || trackCount > kMaxTracks) {
        trackCount = kMaxTracks;
    }
    if (maxFramesPerBuffer < 1) {
        maxFramesPerBuffer = 512;
    }

    mTrackCount = trackCount;
    mMaxFrames = maxFramesPerBuffer;

    // Allocate stereo buffers (interleaved L/R) for each track
    for (int i = 0; i < kMaxTracks; i++) {
        mTracks[i].buffer = new float[maxFramesPerBuffer * 2];
        std::memset(mTracks[i].buffer, 0, maxFramesPerBuffer * 2 * sizeof(float));
    }

    // Allocate stereo output buffer
    mOutputBuffer = new float[maxFramesPerBuffer * 2];
    std::memset(mOutputBuffer, 0, maxFramesPerBuffer * 2 * sizeof(float));
}

void Mixer::setVolume(int trackId, float volume) {
    if (trackId >= 0 && trackId < kMaxTracks) {
        // Clamp to [0.0, 1.0]
        if (volume < 0.0f) volume = 0.0f;
        if (volume > 1.0f) volume = 1.0f;
        mTracks[trackId].volume.store(volume);
    }
}

void Mixer::setPan(int trackId, float pan) {
    if (trackId >= 0 && trackId < kMaxTracks) {
        // Clamp to [-1.0, 1.0]
        if (pan < -1.0f) pan = -1.0f;
        if (pan > 1.0f) pan = 1.0f;
        mTracks[trackId].pan.store(pan);
    }
}

void Mixer::setMute(int trackId, bool mute) {
    if (trackId >= 0 && trackId < kMaxTracks) {
        mTracks[trackId].mute.store(mute);
    }
}

void Mixer::setSolo(int trackId, bool solo) {
    if (trackId >= 0 && trackId < kMaxTracks) {
        mTracks[trackId].solo.store(solo);
    }
}

void Mixer::mix(float* output, int numFrames) {
    if (numFrames > mMaxFrames) {
        numFrames = mMaxFrames;
    }
    if (numFrames <= 0) return;

    // Check if any track is soloed
    bool anySolo = false;
    for (int i = 0; i < mTrackCount; i++) {
        if (mTracks[i].solo.load()) {
            anySolo = true;
            break;
        }
    }

    // Clear output buffer
    std::memset(output, 0, numFrames * 2 * sizeof(float));

    // Mix each track
    for (int t = 0; t < mTrackCount; t++) {
        float vol = mTracks[t].volume.load();
        float pan = mTracks[t].pan.load();
        bool muted = mTracks[t].mute.load();
        bool soloed = mTracks[t].solo.load();

        // Skip muted tracks
        if (muted) {
            // Reset peak meter for skipped tracks
            mTracks[t].peakMeter.store(0.0f);
            continue;
        }

        // Solo logic: if any solo, skip non-soloed tracks
        if (anySolo && !soloed) {
            mTracks[t].peakMeter.store(0.0f);
            continue;
        }

        // Equal-power panning gains (pan in [-1,1], 0=center)
        float leftGain = std::cos((pan + 1.0f) * 3.14159265f / 4.0f);
        float rightGain = std::sin((pan + 1.0f) * 3.14159265f / 4.0f);

        // Accumulate track into output with panning
        float* trackBuf = mTracks[t].buffer;
        float trackRms = 0.0f;

        for (int i = 0; i < numFrames; i++) {
            float sampleL = trackBuf[i * 2] * vol;
            float sampleR = trackBuf[i * 2 + 1] * vol;

            // RMS calculation (left channel)
            trackRms += sampleL * sampleL;

            // Channel-matched stereo panning: L→L, R→R
            output[i * 2]     += sampleL * leftGain;   // Left
            output[i * 2 + 1] += sampleR * rightGain;  // Right
        }

        // Store peak RMS (sqrt already applied by caller, or store squared)
        trackRms = std::sqrt(trackRms / numFrames);
        // Update peak: only increase, never decrease
        float currentPeak = mTracks[t].peakMeter.load();
        if (trackRms > currentPeak) {
            mTracks[t].peakMeter.store(trackRms);
        }
    }
}

float Mixer::getPeakMeter(int trackId) const {
    if (trackId >= 0 && trackId < kMaxTracks) {
        return mTracks[trackId].peakMeter.load();
    }
    return 0.0f;
}

float* Mixer::getTrackBuffer(int trackId) const {
    if (trackId >= 0 && trackId < kMaxTracks) {
        return mTracks[trackId].buffer;
    }
    return nullptr;
}

void Mixer::resetMeters() {
    for (int i = 0; i < mTrackCount; i++) {
        mTracks[i].peakMeter.store(0.0f);
    }
}