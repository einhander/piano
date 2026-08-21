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

// Stable effect ids (strings also used as the AudioEffect::stableId()).
inline constexpr const char* kIdParametricEq = "lsp.parametric_eq";
inline constexpr const char* kIdCompressor   = "lsp.compressor";
inline constexpr const char* kIdLimiter      = "lsp.limiter";

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

// Bindings for the three selected plugins (see LADSPA_DESCRIPTORS.md).
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

    // Limiter.
    kParamLimThreshold    = 20,
    kParamLimLookaheadMs  = 21,
    kParamLimAttackMs     = 22,
    kParamLimReleaseMs    = 23,
    kParamLimKneeDb       = 24,

    // Parametric EQ (band 0 only in the first iteration).
    kParamEqBand0Type     = 30,
    kParamEqBand0Mode     = 31,
    kParamEqBand0Mute     = 32,
    kParamEqBand0Freq     = 33,
    kParamEqBand0Gain     = 34,
    kParamEqBand0Q        = 35,
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

} // namespace lsp
} // namespace piano
