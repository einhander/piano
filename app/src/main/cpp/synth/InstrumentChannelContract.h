#pragma once

namespace piano {
inline constexpr int kInstrumentChannelCount = 128;
inline constexpr bool isInstrumentChannel(int channel) noexcept {
    return channel >= 0 && channel < kInstrumentChannelCount;
}
} // namespace piano
