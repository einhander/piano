#pragma once

#include "LspEffectIds.h"

namespace piano::lsp {

// Shared routing used by NativeEngine's instrument-effect API.
inline int instrumentEffectSlotForId(const char* stableId) {
    return effectSlotForId(stableId);
}

} // namespace piano::lsp
