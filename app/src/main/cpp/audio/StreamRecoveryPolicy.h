#pragma once

#include <oboe/Definitions.h>

namespace piano::audio {

// Only terminal backend states authorize worker-side close/reopen. A cached
// Started state or a stalled callback alone never triggers forced close.
constexpr bool requiresFreshStream(oboe::StreamState state) noexcept {
    return state == oboe::StreamState::Closed || state == oboe::StreamState::Disconnected;
}

} // namespace piano::audio
