#include "synth/InstrumentChannelContract.h"
#include <cassert>
#include <initializer_list>

int main() {
    constexpr int count = piano::kInstrumentChannelCount;
    static_assert(count == 128, "runtime channel range must remain 0..127");
    // Boundary values in range include Main and first/last file channel.
    for (int channel : {0, 15, 16, 127}) assert(piano::isInstrumentChannel(channel));
    for (int channel : {128, 255}) assert(!piano::isInstrumentChannel(channel));
    return 0;
}
