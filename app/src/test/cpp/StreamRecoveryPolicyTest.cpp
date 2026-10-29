#include "../../main/cpp/audio/StreamRecoveryPolicy.h"

#include <cassert>

int main() {
    using piano::audio::requiresFreshStream;
    assert(requiresFreshStream(oboe::StreamState::Closed));
    assert(requiresFreshStream(oboe::StreamState::Disconnected));
    assert(!requiresFreshStream(oboe::StreamState::Started));
    assert(!requiresFreshStream(oboe::StreamState::Open));
    assert(!requiresFreshStream(oboe::StreamState::Stopped));
}
