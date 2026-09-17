#include "model/TransportState.h"
#include "realtime/TransportCmdQueue.h"
#include <cassert>
#include <cmath>

int main() {
    TransportState state;
    state.initializeAudio(48000, 120000000);
    assert(std::abs(state.tpf - 0.04) < 1e-12);
    assert(std::abs(state.frameToTickAudio(24000) - 960.0) < 1e-9);
    const double phase = state.frameToTickAudio(1234);
    state.applyTempo(60000000, 1234);
    assert(std::abs(state.frameToTickAudio(1234) - phase) < 1e-9);
    state.applyRate(44100, 1234);
    assert(std::abs(state.frameToTickAudio(1234) - phase) < 1e-9);

    TransportCmdQueue queue;
    for (uint32_t i = 0; i < TransportCmdQueue::Capacity; ++i)
        assert(queue.push({TransportCmdType::SetTempo, i + 1, 40000 + static_cast<int32_t>(i)}));
    assert(!queue.push({TransportCmdType::SetSampleRate, 99, 96000}));
    assert(queue.droppedCount() == 1);
    TransportCmd command{};
    for (uint32_t i = 0; i < TransportCmdQueue::Capacity; ++i) {
        assert(queue.pop(command));
        assert(command.bpmMicros == i + 1);
        assert(command.sampleRate == 40000 + static_cast<int32_t>(i));
    }
    assert(!queue.pop(command));
    return 0;
}
