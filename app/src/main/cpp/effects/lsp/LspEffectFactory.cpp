#include "LspEffectFactory.h"
#include "ladspa/LadspaRegistry.h"

namespace piano {
namespace lsp {

AudioEffect* LspEffectFactory::create(int slot) {
    const LadspaBinding* b = bindingForSlot(slot);
    if (!b) {
        // Unknown slot — return an unavailable placeholder so the chain slot
        // count stays fixed. (Should not happen for the 3 fixed slots.)
        return new ladspa::LadspaEffect(slot, nullptr);
    }
    const LADSPA_Descriptor* d = ladspa::LadspaRegistry::instance().findByLabel(b->label);
    // Even if the descriptor is missing (bundle not loaded / label not found),
    // return an effect object; it will report isAvailable()==false and the
    // chain bypasses it. This keeps the engine running without the LSP bundle.
    return new ladspa::LadspaEffect(slot, d);
}

} // namespace lsp
} // namespace piano
