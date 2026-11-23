#pragma once

#include "effects/AudioEffect.h"

#include <cstdint>

namespace piano {
namespace guitar {

// Stable effect id routed through the generic instrument-effect JNI surface
// (native_engine_jni.cpp dispatches this id to the GuitarRigManager instead
// of the LSP slot table).
inline constexpr const char* kGuitarRigStableId = "guitar.rig";

// Piano-owned stable parameter ids. These are the UI/persistence contract;
// upstream 0xFX indices are never exposed. Order equals the numeric value.
enum GuitarRigParam : uint32_t {
    kParamDriveType = 0,   // 0=Off, 1=Jade Drive, 2=Rodent, 3=Mammoth Fuzz
    kParamDriveGain = 1,   // 0..1
    kParamDriveTone = 2,   // 0..1
    kParamAmpModel = 3,    // 0=British Crunch, 1=Regent 800, 2=Meridian High Gain
    kParamAmpGain = 4,     // 0..1
    kParamBass = 5,        // 0..1
    kParamMid = 6,         // 0..1
    kParamTreble = 7,      // 0..1
    kParamCabType = 8,     // 0=1x12 Open, 1=2x12 Closed, 2=4x12 Straight, 3=4x12 Slant
    kParamCabEnabled = 9,  // toggled 0/1
    kParamOutput = 10,     // 0..1
    kParamMix = 11,        // 0..1 (dry/wet)
    kParamCount = 12
};

// Enum value tables for the discrete parameters. The values index
// Piano-owned lists; GuitarRigEffect maps them to the real upstream enums
// (fx_amp_type_t / fx_cab_type_t / fx_pedal_type_t) in one switch each.
inline constexpr int kDriveTypeOff = 0;
inline constexpr int kDriveTypeJade = 1;       // FX_PEDAL_JADE_DRIVE
inline constexpr int kDriveTypeRodent = 2;     // FX_PEDAL_RODENT
inline constexpr int kDriveTypeMammoth = 3;    // FX_PEDAL_MAMMOTH_FUZZ

inline constexpr int kAmpModelBritishCrunch = 0; // FX_AMP_BRIT_CRUNCH
inline constexpr int kAmpModelRegent800 = 1;     // FX_AMP_REGENT_800
inline constexpr int kAmpModelMeridianHighGain = 2; // FX_AMP_MERIDIAN_HIGH_GAIN

inline constexpr int kCabType1x12Open = 0;        // FX_CAB_1X12_OPEN
inline constexpr int kCabType2x12Closed = 1;      // FX_CAB_2X12_CLOSED
inline constexpr int kCabType4x12Straight = 2;    // FX_CAB_4X12_STRAIGHT
inline constexpr int kCabType4x12Slant = 3;       // FX_CAB_4X12_SLANT

// Descriptor table for the generic paramInfo/paramName JNI branch, in
// kParamCount index order. logarithmic=false for all (every range is linear
// 0..1 or a small integer enum); integer=true for the three discrete model
// selectors; toggled=true for the cabinet engage switch.
const EffectParameterDescriptor* guitarRigParamDescriptors(int& count);

// Clamp + quantize a raw value for the given parameter id (returns the
// clamped value; non-finite input returns fallback unchanged-false path is
// handled by callers). Shared by the effect and the manager.
bool guitarRigClampParam(uint32_t id, float& value) noexcept;

} // namespace guitar
} // namespace piano
