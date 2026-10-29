#include "effects/lsp/LspEffectIds.h"

#include <cstdio>

int main() {
    const char* effectIds[] = {
        piano::lsp::kIdParametricEq,
        piano::lsp::kIdCompressor,
        piano::lsp::kIdLimiter,
        piano::lsp::kIdChorus,
        piano::lsp::kIdClipper,
        piano::lsp::kIdFilter,
        piano::lsp::kIdPhaser,
        piano::lsp::kIdFlanger,
    };
    for (int slot = 0; slot < piano::lsp::kInstrumentEffectCount; ++slot) {
        int count = 0;
        const auto* descriptors = piano::lsp::paramDescriptors(slot, count);
        if (descriptors == nullptr) return 1;
        for (int index = 0; index < count; ++index) {
            const auto& item = descriptors[index];
            std::printf("%s\t%u\t%s\t%.9g\t%.9g\t%.9g\n", effectIds[slot], item.id,
                        item.stableName, item.minValue, item.maxValue, item.defaultValue);
        }
    }
    return 0;
}
