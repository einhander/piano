#include "../realtime/SynthCmdQueue.h"
#include "../synth/ReverbParameters.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <mutex>
#include <thread>

int main() {
    static_assert(std::is_trivially_copyable<SynthCmd>::value, "queue payload must be trivial");
    SynthCmdQueue q(2);
    assert(piano::reverb::validParameters(0.0f, 0.0f, 0.0f, 0.0f));
    assert(piano::reverb::validParameters(1.0f, 1.0f, 100.0f, 1.0f));
    assert(!piano::reverb::validParameters(-0.01f, 0.5f, 50.0f, 0.5f));
    assert(!piano::reverb::validParameters(0.5f, NAN, 50.0f, 0.5f));
    assert(!piano::reverb::validParameters(0.5f, 0.5f, 100.1f, 0.5f));
    assert(!piano::reverb::validParameters(0.5f, 0.5f, 50.0f, INFINITY));
    bool enabledValue = false;
    assert(piano::reverb::parseEnabledValue(0.0f, enabledValue) && !enabledValue);
    assert(piano::reverb::parseEnabledValue(1.0f, enabledValue) && enabledValue);
    assert(!piano::reverb::parseEnabledValue(0.5f, enabledValue));
    assert(!piano::reverb::parseEnabledValue(NAN, enabledValue));
    const auto config = piano::reverb::makeCommand({0.25f, 0.5f, 75.0f, 0.8f});
    SynthCmd cmd = config;
    piano::reverb::Parameters desired{0.5f, 0.2f, 0.8f, 0.7f};
    assert(piano::reverb::enqueueParametersLocked(q, desired, {0.25f, 0.5f, 75.0f, 0.8f}));
    assert(q.push(cmd));
    assert(!q.push(cmd));
    assert(q.droppedCount() == 1);
    // Queue overflow cannot publish the rejected desired tuple.
    assert(!piano::reverb::enqueueParametersLocked(q, desired, {0.9f, 0.9f, 90.0f, 0.9f}));
    assert(desired.roomSize == 0.25f && desired.damping == 0.5f && desired.width == 75.0f && desired.level == 0.8f);
    SynthCmd out{};
    assert(q.pop(out));
    assert(out.type == SynthCmdType::SET_REVERB_PARAMS);
    assert(std::fabs(out.reverbRoomSize - 0.25f) < 1e-6f);
    assert(std::fabs(out.reverbDamping - 0.5f) < 1e-6f);
    assert(std::fabs(out.reverbWidth - 75.0f) < 1e-6f);
    assert(std::fabs(out.reverbLevel - 0.8f) < 1e-6f);
    assert(q.pop(out));
    assert(!q.pop(out));

    SynthCmd reset{};
    assert(makeResetChannelRangeCommand(16, 16, 128, reset));
    assert(reset.type == SynthCmdType::RESET_CHANNEL_RANGE && reset.a == 16 && reset.b == 16);
    assert(!makeResetChannelRangeCommand(112, 17, 128, reset));

    // Exercise same lock + update/queue/commit transaction used by engine
    // compatibility setters; distinct concurrent fields must not overwrite.
    SynthCmdQueue concurrentQ(256);
    piano::reverb::Parameters concurrentDesired{0.5f, 0.2f, 0.8f, 0.7f};
    std::mutex configMutex;
    auto update = [&](int id, float value) {
        for (int i = 0; i < 100; ++i) {
            std::lock_guard<std::mutex> lock(configMutex);
            piano::reverb::Parameters next{};
            assert(piano::reverb::updateParameter(concurrentDesired, id, value, next));
            assert(piano::reverb::enqueueParametersLocked(concurrentQ, concurrentDesired, next));
        }
    };
    std::thread room(update, 1, 0.25f);
    std::thread damping(update, 2, 0.75f);
    room.join();
    damping.join();
    assert(concurrentDesired.roomSize == 0.25f && concurrentDesired.damping == 0.75f);
    assert(concurrentDesired.width == 0.8f && concurrentDesired.level == 0.7f);

    // Model slot preparation: worker enqueues its restored snapshot while
    // holding the same producer mutex through activation; a setter follows it.
    SynthCmdQueue flipQ(4);
    piano::reverb::Parameters flipDesired{0.5f, 0.2f, 0.8f, 0.7f};
    std::mutex flipMutex;
    std::atomic<bool> restored{false}, releaseFlip{false};
    std::thread preparer([&] {
        std::lock_guard<std::mutex> lock(flipMutex);
        assert(piano::reverb::enqueueParametersLocked(flipQ, flipDesired, flipDesired));
        restored.store(true, std::memory_order_release);
        while (!releaseFlip.load(std::memory_order_acquire)) std::this_thread::yield();
    });
    std::thread postFlipSetter([&] {
        while (!restored.load(std::memory_order_acquire)) std::this_thread::yield();
        std::lock_guard<std::mutex> lock(flipMutex);
        piano::reverb::Parameters next{};
        assert(piano::reverb::updateParameter(flipDesired, 1, 0.3f, next));
        assert(piano::reverb::enqueueParametersLocked(flipQ, flipDesired, next));
    });
    while (!restored.load(std::memory_order_acquire)) std::this_thread::yield();
    releaseFlip.store(true, std::memory_order_release);
    preparer.join();
    postFlipSetter.join();
    assert(flipQ.pop(out) && out.reverbRoomSize == 0.5f);
    assert(flipQ.pop(out) && out.reverbRoomSize == 0.3f);

    std::atomic<int> enabled{1};
    assert(q.push(cmd));
    assert(q.push(cmd));
    assert(!piano::reverb::enqueueEnabledLocked(q, enabled, false));
    assert(enabled.load() == 1);
    std::cout << "Reverb command payload/overflow checks passed\n";
}
