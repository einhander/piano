#pragma once

#include "AudioEffect.h"
#include "ladspa/LadspaEffect.h"

namespace piano {
namespace lsp {

// Builds the fixed master effect chain (EQ, Compressor, Limiter) from the
// LADSPA registry. The registry must be opened (bundle dlopen'd) first.
// Effects whose descriptor cannot be found are returned as unavailable
// (isAvailable()==false); the chain will bypass them.
class LspEffectFactory {
public:
    // Create one effect for the given slot. Caller owns the pointer. May
    // return an unavailable effect (non-null but isAvailable()==false) if the
    // descriptor was not found — never returns null.
    static AudioEffect* create(int slot);
};

} // namespace lsp
} // namespace piano
