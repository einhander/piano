#pragma once

#include <oboe/Definitions.h>
#include <oboe/AudioStreamBuilder.h>

namespace piano::audio {

enum class ApiChoice : int { OboeDefault = 0, AAudio = 1, OpenSLES = 2 };
enum class RateChoice : int { Hz48000 = 0, Hz44100 = 1, System = 2 };

constexpr oboe::AudioApi toOboeApi(ApiChoice choice) noexcept {
    switch (choice) {
        case ApiChoice::AAudio: return oboe::AudioApi::AAudio;
        case ApiChoice::OpenSLES: return oboe::AudioApi::OpenSLES;
        default: return oboe::AudioApi::Unspecified;
    }
}

constexpr int requestedRate(RateChoice choice) noexcept {
    switch (choice) {
        case RateChoice::Hz44100: return 44100;
        case RateChoice::System: return 0;
        default: return 48000;
    }
}

constexpr int rateAttemptCount(RateChoice choice) noexcept {
    return choice == RateChoice::System ? 1 : 2;
}

// Only terminal backend states authorize worker-side close/reopen. A cached
// Started state or a stalled callback alone never triggers forced close.
constexpr bool requiresFreshStream(oboe::StreamState state) noexcept {
    return state == oboe::StreamState::Closed || state == oboe::StreamState::Disconnected;
}

} // namespace piano::audio
