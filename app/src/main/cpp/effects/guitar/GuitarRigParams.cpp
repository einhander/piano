#include "GuitarRigParams.h"

#include <cmath>

namespace piano {
namespace guitar {

// Table order MUST match GuitarRigParam ids (0..kParamCount-1).
// Cab default is 4x12 Straight (spec): the pinned cab_ir.c bundled_presets[2]
// synthetic IR {FX_CAB_4X12_STRAIGHT, FX_MIC_ON_AXIS, speaker_fs=70,
// brightness=0.5, resonance=0.6}.
static const EffectParameterDescriptor kDescriptors[kParamCount] = {
    {kParamDriveType, "drive_type", "Drive Type",
     0.0f, 3.0f, 0.0f, false, true, false},
    {kParamDriveGain, "drive_gain", "Drive",
     0.0f, 1.0f, 0.35f, false, false, false},
    {kParamDriveTone, "drive_tone", "Drive Tone",
     0.0f, 1.0f, 0.5f, false, false, false},
    {kParamAmpModel, "amp_model", "Amp Model",
     0.0f, 2.0f, 0.0f, false, true, false},
    {kParamAmpGain, "amp_gain", "Gain",
     0.0f, 1.0f, 0.4f, false, false, false},
    {kParamBass, "bass", "Bass",
     0.0f, 1.0f, 0.5f, false, false, false},
    {kParamMid, "mid", "Mid",
     0.0f, 1.0f, 0.5f, false, false, false},
    {kParamTreble, "treble", "Treble",
     0.0f, 1.0f, 0.5f, false, false, false},
    {kParamCabType, "cab_type", "Cabinet",
     0.0f, 3.0f, 2.0f, false, true, false},
    {kParamCabEnabled, "cab_enabled", "Cabinet Engaged",
     0.0f, 1.0f, 1.0f, false, false, true},
    {kParamOutput, "output", "Output",
     0.0f, 1.0f, 0.5f, false, false, false},
    {kParamMix, "mix", "Mix",
     0.0f, 1.0f, 1.0f, false, false, false},
};

const EffectParameterDescriptor* guitarRigParamDescriptors(int& count) {
    count = kParamCount;
    return kDescriptors;
}

bool guitarRigClampParam(uint32_t id, float& value) noexcept {
    if (id >= kParamCount) return false;
    if (!std::isfinite(value)) return false;
    const EffectParameterDescriptor& d = kDescriptors[id];
    float v = value < d.minValue ? d.minValue : (value > d.maxValue ? d.maxValue : value);
    if (d.integer) v = std::nearbyintf(v);
    value = v;
    return true;
}

} // namespace guitar
} // namespace piano
