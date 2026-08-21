#include "LspEffectIds.h"

namespace piano {
namespace lsp {

// ── Parametric EQ (band 0 only) ──
static const ParamPort kEqPorts[] = {
    {kParamBypass,       4,   0.0f,    1.0f,        0.0f, false, false, true},
    {kParamInputGain,    5,   0.0f,    10.0f,       1.0f, true,  false, false},
    {kParamOutputGain,   6,   0.0f,    10.0f,       1.0f, true,  false, false},
    {kParamEqBand0Type,  28,  0.0f,    11.0f,       0.0f, false, true,  false},
    {kParamEqBand0Mode,  29,  0.0f,    6.0f,        0.0f, false, true,  false},
    {kParamEqBand0Mute,  32,  0.0f,    1.0f,        0.0f, false, false, true},
    {kParamEqBand0Freq,  33,  10.0f,   24000.0f,    1000.0f, true,  false, false},
    {kParamEqBand0Gain,  35,  0.01585f, 63.0957f,   1.0f, true,  false, false},
    {kParamEqBand0Q,     36,  0.0f,    100.0f,      1.0f, false, false, false},
};

// ── Compressor ──
static const ParamPort kCompPorts[] = {
    {kParamBypass,          8,   0.0f,    1.0f,       0.0f, false, false, true},
    {kParamInputGain,       9,   0.0f,    1000.0f,    1.0f, true,  false, false},
    {kParamOutputGain,      10,  0.0f,    1000.0f,    1.0f, true,  false, false},
    {kParamCompMode,        28,  0.0f,    2.0f,       0.0f, false, true,  false},
    {kParamCompThreshold,   29,  0.001f,  1.0f,       1.0f, false, false, false},
    {kParamCompAttackMs,    30,  0.0f,    2000.0f,    20.0f, false, false, false},
    {kParamCompReleaseMs,   32,  0.0f,    5000.0f,    300.0f, true, false, false},
    {kParamCompRatio,       34,  1.0f,    100.0f,     1.0f, false, false, false},
    {kParamCompKnee,        35,  0.0631f, 1.0f,       0.0f, false, false, false},
    {kParamCompMakeup,      38,  0.001f,  1000.0f,    1.0f, true,  false, false},
    {kParamCompWet,         40,  0.0f,    10.0f,      1.0f, true,  false, false},
};

// ── Limiter ──
static const ParamPort kLimPorts[] = {
    {kParamBypass,          8,   0.0f,    1.0f,       0.0f, false, false, true},
    {kParamInputGain,       9,   0.0f,    1000.0f,    1.0f, true,  false, false},
    {kParamOutputGain,      10,  0.0f,    1000.0f,    1.0f, true,  false, false},
    {kParamLimThreshold,    16,  0.00398107f, 1.0f,   1.0f, true,  false, false},
    {kParamLimLookaheadMs,  19,  0.1f,    20.0f,      3.0f, false, false, false},
    {kParamLimAttackMs,     20,  0.25f,   20.0f,      10.0f, false, false, false},
    {kParamLimReleaseMs,    21,  0.25f,   20.0f,      100.0f, false, false, false},
    {kParamLimKneeDb,       44,  -48.0f,  0.0f,       0.0f, false, false, false},
};

const ParamPort* paramTable(int slot, int& count) {
    switch (slot) {
        case kSlotEq:
            count = static_cast<int>(sizeof(kEqPorts) / sizeof(kEqPorts[0]));
            return kEqPorts;
        case kSlotCompressor:
            count = static_cast<int>(sizeof(kCompPorts) / sizeof(kCompPorts[0]));
            return kCompPorts;
        case kSlotLimiter:
            count = static_cast<int>(sizeof(kLimPorts) / sizeof(kLimPorts[0]));
            return kLimPorts;
        default:
            count = 0;
            return nullptr;
    }
}

const ParamPort* paramPortFor(int slot, uint32_t paramId) {
    int count = 0;
    const ParamPort* table = paramTable(slot, count);
    for (int i = 0; i < count; ++i) {
        if (table[i].paramId == paramId) {
            return &table[i];
        }
    }
    return nullptr;
}

} // namespace lsp
} // namespace piano
