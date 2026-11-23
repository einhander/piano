#include "GuitarRigEffect.h"

#include "fx_engine.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>

namespace piano {
namespace guitar {

namespace {

// Synthetic cabinet presets, transcribed from the pinned cab_ir.c
// bundled_presets[] table (all mic positions ON_AXIS):
//   [0] FX_CAB_1X12_OPEN     speaker_fs=75  brightness=0.8  resonance=0.6
//   [1] FX_CAB_2X12_CLOSED   speaker_fs=80  brightness=0.5  resonance=0.5
//   [2] FX_CAB_4X12_STRAIGHT speaker_fs=70  brightness=0.5  resonance=0.6
//   [3] FX_CAB_4X12_SLANT    speaker_fs=70  brightness=0.65 resonance=0.5
struct CabPreset {
    fx_cab_type_t type;
    float mic; // fx_mic_pos_t value
    float speakerFs;
    float brightness;
    float resonance;
};
const CabPreset kCabPresets[4] = {
    {FX_CAB_1X12_OPEN, FX_MIC_ON_AXIS, 75.0f, 0.8f, 0.6f},
    {FX_CAB_2X12_CLOSED, FX_MIC_ON_AXIS, 80.0f, 0.5f, 0.5f},
    {FX_CAB_4X12_STRAIGHT, FX_MIC_ON_AXIS, 70.0f, 0.5f, 0.6f},
    {FX_CAB_4X12_SLANT, FX_MIC_ON_AXIS, 70.0f, 0.65f, 0.5f},
};

// Piano model index (0..2) → the real upstream enum names read from the
// pinned fx_engine.h.
fx_amp_type_t upstreamAmpModel(int modelIndex) {
    switch (modelIndex) {
        case kAmpModelRegent800: return FX_AMP_REGENT_800;
        case kAmpModelMeridianHighGain: return FX_AMP_MERIDIAN_HIGH_GAIN;
        case kAmpModelBritishCrunch:
        default: return FX_AMP_BRIT_CRUNCH;
    }
}

const fx_pedal_type_t kDrivePedals[3] = {
    FX_PEDAL_JADE_DRIVE, FX_PEDAL_RODENT, FX_PEDAL_MAMMOTH_FUZZ,
};

} // namespace

GuitarRigEffect::GuitarRigEffect() {
    int count = 0;
    const EffectParameterDescriptor* d = guitarRigParamDescriptors(count);
    for (int i = 0; i < count; ++i)
        mParams[static_cast<uint32_t>(i)].store(d[i].defaultValue, std::memory_order_relaxed);
}

GuitarRigEffect::~GuitarRigEffect() {
    releaseEngines();
    std::free(mDryL);
    std::free(mDryR);
    mDryL = nullptr;
    mDryR = nullptr;
}

void GuitarRigEffect::releaseEngines() noexcept {
    mPrepared.store(false, std::memory_order_release);
    mConfigPending.store(false, std::memory_order_release);
    mWetApplied = 0.0f;
    if (mEngineL) { fx_engine_destroy(mEngineL); mEngineL = nullptr; }
    if (mEngineR) { fx_engine_destroy(mEngineR); mEngineR = nullptr; }
    for (int i = 0; i < 3; ++i) { mPedalL[i] = -1; mPedalR[i] = -1; }
}

bool GuitarRigEffect::createEngines(double sampleRate) {
    mEngineL = fx_engine_create(static_cast<float>(sampleRate));
    if (!mEngineL) return false;
    mEngineR = fx_engine_create(static_cast<float>(sampleRate));
    if (!mEngineR) { fx_engine_destroy(mEngineL); mEngineL = nullptr; return false; }

    struct fx_engine* engines[2] = {mEngineL, mEngineR};
    int32_t* ids[2] = {mPedalL, mPedalR};
    for (int side = 0; side < 2; ++side) {
        for (int j = 0; j < 3; ++j) {
            // add_pedal calloc's the pedal state → worker thread only.
            const fx_pedal_id id = fx_chain_add_pedal(engines[side], kDrivePedals[j], FX_CHAIN_POS_PRE);
            if (id < 0) return false;
            ids[side][j] = id;
            // Start fully bypassed; the drive-type param picks one.
            fx_pedal_set_bypass(engines[side], id, true);
        }
    }
    return true;
}

bool GuitarRigEffect::prepare(double sampleRate, int maxFrames) {
    if (sampleRate <= 0.0 || !std::isfinite(sampleRate) || maxFrames <= 0) return false;
    releaseEngines();
    std::free(mDryL);
    std::free(mDryR);
    mDryL = nullptr;
    mDryR = nullptr;
    mMaxFrames = 0;
    mChunkFrames = 0;

    const int chunk = std::min(maxFrames, kMaxEngineBlock);
    mDryL = static_cast<float*>(std::malloc(sizeof(float) * static_cast<size_t>(chunk)));
    mDryR = static_cast<float*>(std::malloc(sizeof(float) * static_cast<size_t>(chunk)));
    if (!mDryL || !mDryR) {
        std::free(mDryL); std::free(mDryR);
        mDryL = nullptr; mDryR = nullptr;
        return false;
    }
    if (!createEngines(sampleRate)) {
        releaseEngines();
        std::free(mDryL); std::free(mDryR);
        mDryL = nullptr; mDryR = nullptr;
        return false;
    }
    mSampleRate = sampleRate;
    mMaxFrames = maxFrames;
    mChunkFrames = chunk;
    // Applies amp model + cab IR from the current atomics. A prepared rig
    // always reflects the stored config; later discrete changes go through
    // the pending flag (see class comment).
    if (!applyConfigNow()) {
        releaseEngines();
        std::free(mDryL); std::free(mDryR);
        mDryL = nullptr; mDryR = nullptr;
        return false;
    }
    mWetApplied = 0.0f;
    mPrepared.store(true, std::memory_order_release);
    return true;
}

int GuitarRigEffect::driveType() const noexcept {
    const float v = mParams[kParamDriveType].load(std::memory_order_relaxed);
    int t = static_cast<int>(v + 0.5f);
    return t < 0 ? 0 : (t > 3 ? 3 : t);
}

int GuitarRigEffect::ampModelIndex() const noexcept {
    const float v = mParams[kParamAmpModel].load(std::memory_order_relaxed);
    int t = static_cast<int>(v + 0.5f);
    return t < 0 ? 0 : (t > 2 ? 2 : t);
}

int GuitarRigEffect::cabTypeIndex() const noexcept {
    const float v = mParams[kParamCabType].load(std::memory_order_relaxed);
    int t = static_cast<int>(v + 0.5f);
    return t < 0 ? 0 : (t > 3 ? 3 : t);
}

bool GuitarRigEffect::applyConfigNow() noexcept {
    if (!mEngineL || !mEngineR) return false;
    const int model = ampModelIndex();
    const CabPreset& cab = kCabPresets[cabTypeIndex()];
    fx_cab_params_t cabParams;
    cabParams.cab_type = cab.type;
    cabParams.mic_pos = static_cast<fx_mic_pos_t>(cab.mic);
    cabParams.speaker_fs = cab.speakerFs;
    cabParams.brightness = cab.brightness;
    cabParams.resonance = cab.resonance;

    struct fx_engine* engines[2] = {mEngineL, mEngineR};
    for (struct fx_engine* eng : engines) {
        fx_amp_set_model(eng, FX_CHAIN_DEFAULT, upstreamAmpModel(model));
        // generate_ir mallocs + re-plans the FFT → only legal off the audio
        // thread (prepare / quiescence). Returns false on allocation or
        // planning failure.
        if (!fx_cab_generate_ir(eng, FX_CHAIN_DEFAULT, &cabParams)) return false;
    }
    mConfigPending.store(false, std::memory_order_release);
    return true;
}

void GuitarRigEffect::applyLiveParams() noexcept {
    const int drive = driveType();
    const float driveGain = mParams[kParamDriveGain].load(std::memory_order_relaxed);
    const float driveTone = mParams[kParamDriveTone].load(std::memory_order_relaxed);
    const float ampGain = mParams[kParamAmpGain].load(std::memory_order_relaxed);
    const float bass = mParams[kParamBass].load(std::memory_order_relaxed);
    const float mid = mParams[kParamMid].load(std::memory_order_relaxed);
    const float treble = mParams[kParamTreble].load(std::memory_order_relaxed);
    const bool cabBypass = mParams[kParamCabEnabled].load(std::memory_order_relaxed) < 0.5f;

    struct fx_engine* engines[2] = {mEngineL, mEngineR};
    const int32_t* ids[2] = {mPedalL, mPedalR};
    for (int side = 0; side < 2; ++side) {
        struct fx_engine* eng = engines[side];
        for (int j = 0; j < 3; ++j) {
            const int32_t id = ids[side][j];
            // flag-only bypass flip (engine.c:342 / pedals.c:2614) + plain
            // param writes (engine.c:329); no add/remove, no realloc.
            fx_pedal_set_bypass(eng, id, drive != j + 1);
            fx_pedal_set_param(eng, id, 0, driveGain);
            fx_pedal_set_param(eng, id, 1, driveTone);
        }
        fx_amp_set_param(eng, FX_CHAIN_DEFAULT, FX_AMP_PARAM_GAIN, ampGain);
        fx_amp_set_param(eng, FX_CHAIN_DEFAULT, FX_AMP_PARAM_BASS, bass);
        fx_amp_set_param(eng, FX_CHAIN_DEFAULT, FX_AMP_PARAM_MID, mid);
        fx_amp_set_param(eng, FX_CHAIN_DEFAULT, FX_AMP_PARAM_TREBLE, treble);
        fx_cab_set_bypass(eng, FX_CHAIN_DEFAULT, cabBypass);
    }
}

void GuitarRigEffect::process(float* left, float* right, int numFrames) noexcept {
    if (!mPrepared.load(std::memory_order_acquire)) return;
    if (!left || !right || numFrames <= 0 || numFrames > mMaxFrames) return;
    const bool bypassed = mBypassed.load(std::memory_order_acquire);
    const float target = bypassed ? 0.0f
                                  : mParams[kParamMix].load(std::memory_order_relaxed);
    // Fully dry: leave the buffers bit-identical and pay no DSP/FFT at all.
    if (mWetApplied == 0.0f && target == 0.0f) return;

    applyLiveParams();

    // External output trim: outGain = 2*kOutput (unity at 0.5). Engine
    // master volume and amp MASTER/VOLUME stay at upstream defaults — see
    // the class comment for why they are NOT repurposed.
    const float outGain = 2.0f * mParams[kParamOutput].load(std::memory_order_relaxed);
    // 5 ms linear crossfade ramp (rack convention).
    const float rampStep = static_cast<float>(1.0 / (mSampleRate * 0.005));
    const int maxChunk = mChunkFrames > 0 ? mChunkFrames : kMaxEngineBlock;

    int off = 0;
    while (off < numFrames) {
        const int n = std::min(numFrames - off, maxChunk);
        std::memcpy(mDryL, left + off, sizeof(float) * static_cast<size_t>(n));
        std::memcpy(mDryR, right + off, sizeof(float) * static_cast<size_t>(n));
        // In-place is safe: fx_engine_process copies input→scratch first
        // (engine.c:96) and writes output last (engine.c:211).
        fx_engine_process(mEngineL, left + off, left + off, n);
        fx_engine_process(mEngineR, right + off, right + off, n);
        for (int i = 0; i < n; ++i) {
            if (mWetApplied < target) mWetApplied = std::min(target, mWetApplied + rampStep);
            else if (mWetApplied > target) mWetApplied = std::max(target, mWetApplied - rampStep);
            const float mix = mWetApplied;
            const float dryL = mDryL[i], dryR = mDryR[i];
            // Level-normalized crossfade toward the trimmed wet signal:
            //   out = dry + (outGain*wet − dry) * mix
            // (mix==0 → dry exactly; mix==1 → outGain*wet; the 0..1
            // interpolation keeps mix=0.5 free of a double-level bump).
            const float wetL = outGain * left[off + i];
            const float wetR = outGain * right[off + i];
            float outL = dryL + (wetL - dryL) * mix;
            float outR = dryR + (wetR - dryR) * mix;
            // NaN/Inf sanitization on OUTPUT (inputs may be poisoned
            // upstream; the engine only sanitizes its internal bus).
            if (!std::isfinite(outL)) outL = 0.0f;
            if (!std::isfinite(outR)) outR = 0.0f;
            left[off + i] = outL;
            right[off + i] = outR;
        }
        off += n;
    }
}

void GuitarRigEffect::setParameter(uint32_t parameterId, float value) noexcept {
    if (parameterId >= kParamCount) return;
    float v = value;
    if (!guitarRigClampParam(parameterId, v)) return; // non-finite ignored
    const float old = mParams[parameterId].exchange(v, std::memory_order_acq_rel);
    if (old == v) return;
    // Interim policy: model/cab changes on a live prepared engine are NOT
    // applied at audio rate; mark reconfigure pending for the manager.
    if (mPrepared.load(std::memory_order_acquire) &&
        (parameterId == kParamAmpModel || parameterId == kParamCabType)) {
        mConfigPending.store(true, std::memory_order_release);
    }
}

float GuitarRigEffect::getParameter(uint32_t parameterId) const noexcept {
    if (parameterId >= kParamCount) return 0.0f;
    return mParams[parameterId].load(std::memory_order_relaxed);
}

void GuitarRigEffect::setBypassed(bool bypassed) noexcept {
    mBypassed.store(bypassed, std::memory_order_release);
}

bool GuitarRigEffect::isBypassed() const noexcept {
    return mBypassed.load(std::memory_order_acquire);
}

bool GuitarRigEffect::isAvailable() const noexcept {
    return mPrepared.load(std::memory_order_acquire);
}

} // namespace guitar
} // namespace piano
