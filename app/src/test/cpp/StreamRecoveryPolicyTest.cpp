#include "audio/StreamRecoveryPolicy.h"

#include <cassert>

int main() {
    using piano::audio::requiresFreshStream;
    assert(requiresFreshStream(oboe::StreamState::Closed));
    assert(requiresFreshStream(oboe::StreamState::Disconnected));
    assert(!requiresFreshStream(oboe::StreamState::Started));
    assert(!requiresFreshStream(oboe::StreamState::Open));
    assert(!requiresFreshStream(oboe::StreamState::Stopped));
    using piano::audio::toOboeApi;
    using piano::audio::requestedRate;
    assert(toOboeApi(piano::audio::ApiChoice::AAudio) == oboe::AudioApi::AAudio);
    assert(toOboeApi(piano::audio::ApiChoice::OboeDefault) == oboe::AudioApi::Unspecified);
    assert(toOboeApi(piano::audio::ApiChoice::OpenSLES) == oboe::AudioApi::OpenSLES);
    assert(toOboeApi(piano::audio::ApiChoice::OboeDefault) == oboe::AudioApi::Unspecified);
    assert(requestedRate(piano::audio::RateChoice::Hz48000) == 48000);
    assert(requestedRate(piano::audio::RateChoice::Hz44100) == 44100);
    assert(requestedRate(piano::audio::RateChoice::System) == 0);
    assert(piano::audio::rateAttemptCount(piano::audio::RateChoice::System) == 1);
    assert(piano::audio::rateAttemptCount(piano::audio::RateChoice::Hz44100) == 2);
}
