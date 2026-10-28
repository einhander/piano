#pragma once

#include "AudioEffect.h"

#include <cstdint>

namespace piano {

// Forward declaration so the descriptor getter below can be declared here
// without pulling the whole EffectChain/AudioEffect dependency graph into
// every TU that includes LspEffectIds.h.
struct EffectParameterDescriptor;

namespace lsp {

// ── Stable effect ids (Piano-owned, independent of LADSPA UniqueID) ──
//
// The master chain is fixed: slot 0 = EQ, slot 1 = Compressor, slot 2 = Limiter.
inline constexpr int kMasterEffectCount = 3;

inline constexpr int kSlotEq         = 0;
inline constexpr int kSlotCompressor = 1;
inline constexpr int kSlotLimiter    = 2;
inline constexpr int kSlotChorus = 3;
inline constexpr int kSlotClipper = 4;
inline constexpr int kSlotFilter = 5;
inline constexpr int kSlotPhaser = 6;
inline constexpr int kSlotFlanger = 7;
inline constexpr int kInstrumentEffectCount = 8;
inline constexpr int kInstrumentSlotChorus = 3;
inline constexpr int kInstrumentSlotClipper = 4;
inline constexpr int kInstrumentSlotFilter = 5;
inline constexpr int kInstrumentSlotPhaser = 6;
inline constexpr int kInstrumentSlotFlanger = 7;

// Stable effect ids (strings also used as the AudioEffect::stableId()).
inline constexpr const char* kIdParametricEq = "lsp.parametric_eq";
inline constexpr const char* kIdCompressor   = "lsp.compressor";
inline constexpr const char* kIdLimiter      = "lsp.limiter";
inline constexpr const char* kIdChorus = "lsp.chorus";
inline constexpr const char* kIdClipper = "lsp.clipper";
inline constexpr const char* kIdFilter = "lsp.filter";
inline constexpr const char* kIdPhaser = "lsp.phaser";
inline constexpr const char* kIdFlanger = "lsp.flanger";

// ── LADSPA binding metadata ──
//
// The LADSPA Label is a full URI (the upstream string used in
// LSP_LADSPA_URI(...)); the LadspaRegistry locates the descriptor by Label.
// UniqueID is recorded for diagnostics only — Label is the lookup key.
struct LadspaBinding {
    const char* stableId;
    const char* label;       // LADSPA Label (URI)
    unsigned long uniqueId;  // LADSPA UniqueID (diagnostic)
    int audioInL;
    int audioInR;
    int audioOutL;
    int audioOutR;
    int bypassPort;     // -1 if the plugin has no bypass port
    int latencyPort;    // -1 if none
};

// This table is a stable master-chain API: keep exactly three entries.
inline constexpr LadspaBinding kBindings[] = {
    {kIdParametricEq,
     "http://lsp-plug.in/plugins/ladspa/para_equalizer_x16_stereo", 5002076u,
     0, 1, 2, 3, 4, 205},
    {kIdCompressor,
     "http://lsp-plug.in/plugins/ladspa/compressor_stereo", 5002091u,
     0, 1, 2, 3, 8, 65},
    {kIdLimiter,
     "http://lsp-plug.in/plugins/ladspa/limiter_stereo", 5002123u,
     0, 1, 2, 3, 8, 45},
};
inline constexpr LadspaBinding kInstrumentBindings[] = {
    {kIdChorus, "http://lsp-plug.in/plugins/ladspa/chorus_stereo", 5002315u, 0, 1, 2, 3, 4, 181},
    {kIdClipper, "http://lsp-plug.in/plugins/ladspa/clipper_stereo", 5002285u, 0, 1, 2, 3, 4, 57},
    {kIdFilter, "http://lsp-plug.in/plugins/ladspa/filter_stereo", 5002255u, 0, 1, 2, 3, 4, 28},
    {kIdPhaser, "http://lsp-plug.in/plugins/ladspa/phaser_stereo", 5002325u, 0, 1, 2, 3, 4, 95},
    {kIdFlanger, "http://lsp-plug.in/plugins/ladspa/flanger_stereo", 5002235u, 0, 1, 2, 3, 4, 44},
};

// ── Stable parameter ids ──
//
// These are small dense ids scoped per effect. They are passed across JNI as
// the parameterId of AudioEffect::setParameter. The adapter maps them to the
// underlying LADSPA port index via the per-effect port table below.
enum ParamId : uint32_t {
    // Common to all three effects.
    kParamBypass       = 0,
    kParamInputGain    = 1,
    kParamOutputGain   = 2,

    // Compressor.
    kParamCompMode        = 10,
    kParamCompThreshold   = 11,
    kParamCompAttackMs    = 12,
    kParamCompReleaseMs   = 13,
    kParamCompRatio       = 14,
    kParamCompKnee        = 15,
    kParamCompMakeup      = 16,
    kParamCompWet         = 17,
    kParamCompDryWet      = 18,   // Dry/Wet balance (%) — port 41; MUST default to 100
    kParamCompScPreamp    = 19,   // Sidechain preamp (gain) — port 23; MUST default to 1
    kParamCompScMode      = 110,  // Sidechain mode (0=Peak,1=RMS,2=LPF,3=Uniform) — port 18
    kParamCompScReactMs   = 111,  // Sidechain reactivity (ms) — port 22

    // Limiter.
    kParamLimThreshold    = 20,
    kParamLimLookaheadMs  = 21,
    kParamLimAttackMs     = 22,
    kParamLimReleaseMs    = 23,
    kParamLimKneeDb       = 24,

    // Parametric EQ. Band 0 IDs are persisted and must remain unchanged.
    kParamEqBand0Type     = 30,
    kParamEqBand0Mode     = 31,
    kParamEqBand0Mute     = 32,
    kParamEqBand0Freq     = 33,
    kParamEqBand0Gain     = 34,
    kParamEqBand0Q        = 35,
    kParamEqBand1Type = 130, kParamEqBand1Mode, kParamEqBand1Mute, kParamEqBand1Freq, kParamEqBand1Gain, kParamEqBand1Q,
    kParamEqBand2Type = 140, kParamEqBand2Mode, kParamEqBand2Mute, kParamEqBand2Freq, kParamEqBand2Gain, kParamEqBand2Q,
    kParamEqBand3Type = 150, kParamEqBand3Mode, kParamEqBand3Mute, kParamEqBand3Freq, kParamEqBand3Gain, kParamEqBand3Q,
    kParamEqBand4Type = 160, kParamEqBand4Mode, kParamEqBand4Mute, kParamEqBand4Freq, kParamEqBand4Gain, kParamEqBand4Q,
    kParamEqBand5Type = 170, kParamEqBand5Mode, kParamEqBand5Mute, kParamEqBand5Freq, kParamEqBand5Gain, kParamEqBand5Q,
    kParamEqBand6Type = 180, kParamEqBand6Mode, kParamEqBand6Mute, kParamEqBand6Freq, kParamEqBand6Gain, kParamEqBand6Q,
    kParamEqBand7Type = 190, kParamEqBand7Mode, kParamEqBand7Mute, kParamEqBand7Freq, kParamEqBand7Gain, kParamEqBand7Q,
    kParamEqBand8Type = 200, kParamEqBand8Mode, kParamEqBand8Mute, kParamEqBand8Freq, kParamEqBand8Gain, kParamEqBand8Q,
    kParamEqBand9Type = 210, kParamEqBand9Mode, kParamEqBand9Mute, kParamEqBand9Freq, kParamEqBand9Gain, kParamEqBand9Q,
    kParamEqBand10Type = 220, kParamEqBand10Mode, kParamEqBand10Mute, kParamEqBand10Freq, kParamEqBand10Gain, kParamEqBand10Q,
    kParamEqBand11Type = 230, kParamEqBand11Mode, kParamEqBand11Mute, kParamEqBand11Freq, kParamEqBand11Gain, kParamEqBand11Q,
    kParamEqBand12Type = 240, kParamEqBand12Mode, kParamEqBand12Mute, kParamEqBand12Freq, kParamEqBand12Gain, kParamEqBand12Q,
    kParamEqBand13Type = 250, kParamEqBand13Mode, kParamEqBand13Mute, kParamEqBand13Freq, kParamEqBand13Gain, kParamEqBand13Q,
    kParamEqBand14Type = 260, kParamEqBand14Mode, kParamEqBand14Mute, kParamEqBand14Freq, kParamEqBand14Gain, kParamEqBand14Q,
    kParamEqBand15Type = 270, kParamEqBand15Mode, kParamEqBand15Mute, kParamEqBand15Freq, kParamEqBand15Gain, kParamEqBand15Q,
    kParamChorusRate = 300, kParamChorusVoices, kParamChorusDepth, kParamChorusFeedback, kParamChorusMix,
    kParamClipperDrive = 320, kParamClipperCurve, kParamClipperThreshold, kParamClipperOutputGain,
    kParamClipperLufsEnable = 330, kParamClipperBoostEnable, kParamClipperDither, kParamClipperOdpEnable, kParamClipperEnabled,
    kParamChorusFeedbackEnable = 340, kParamChorusDryAmount, kParamChorusWetAmount, kParamChorusOversampling,
    kParamFilterCutoff = 360, kParamFilterResonance, kParamFilterType,
    kParamPhaserRate = 380, kParamPhaserDepth, kParamPhaserStages, kParamPhaserFeedback, kParamPhaserMix,
    kParamFlangerRate = 400, kParamFlangerDepth, kParamFlangerFeedback, kParamFlangerMix,
};

// Per-effect LADSPA port index for a stable parameter id. Returns -1 if the
// id is not mapped for the given effect slot.
struct ParamPort {
    uint32_t paramId;
    int port;
    float min;
    float max;
    float def;
    bool logarithmic;
    bool integer;
    bool toggled;
};

const ParamPort* paramPortFor(int slot, uint32_t paramId);
const ParamPort* paramTable(int slot, int& count);
int effectSlotForId(const char* stableId);

// Descriptors for the UI/control layer. Returns a pointer to a static table
// of EffectParameterDescriptor entries (with stable + display names, range and
// flags) for the given slot, writing the entry count to `count`. The data is
// static metadata (independent of any loaded effect instance), safe to query
// from any thread.
const EffectParameterDescriptor* paramDescriptors(int slot, int& count);

// A short human-readable name for a stable parameter id (e.g. "Threshold"),
// or nullptr if the id is not known. Used by the UI as a fallback label.
const char* paramDisplayName(uint32_t paramId);

// Lookup the binding for a slot. Always returns a valid pointer (3 entries).
inline const LadspaBinding* bindingForSlot(int slot) {
    return (slot >= 0 && slot < kMasterEffectCount) ? &kBindings[slot] : nullptr;
}
inline const LadspaBinding* instrumentBindingForSlot(int slot) {
    return slot >= kInstrumentSlotChorus && slot < kInstrumentEffectCount
        ? &kInstrumentBindings[slot - kInstrumentSlotChorus] : nullptr;
}

} // namespace lsp
} // namespace piano
