#pragma once

namespace piano {
inline constexpr int kInstrumentChannelCount = 128;
inline constexpr int kAudioBusCount = 16;
inline constexpr bool isInstrumentChannel(int channel) noexcept {
    return channel >= 0 && channel < kInstrumentChannelCount;
}
inline constexpr bool isAudioBusChannel(int channel) noexcept {
    return channel >= 0 && channel < kAudioBusCount;
}
} // namespace piano
