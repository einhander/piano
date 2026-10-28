#pragma once
#include <algorithm>
#include <cmath>
#include "../realtime/SynthCmdQueue.h"
#include <atomic>

namespace piano::reverb {
struct Parameters { float roomSize, damping, width, level; };
inline bool validParameters(float roomSize, float damping, float width, float level) noexcept {
    return std::isfinite(roomSize) && std::isfinite(damping) && std::isfinite(width) && std::isfinite(level)
        && roomSize >= 0.0f && roomSize <= 1.0f
        && damping >= 0.0f && damping <= 1.0f
        && width >= 0.0f && width <= 100.0f
        && level >= 0.0f && level <= 1.0f;
}
inline bool parseEnabledValue(float value, bool& enabled) noexcept {
    if (!std::isfinite(value) || (value != 0.0f && value != 1.0f)) return false;
    enabled = value == 1.0f;
    return true;
}
inline SynthCmd makeCommand(Parameters p) noexcept {
    SynthCmd cmd{};
    cmd.type = SynthCmdType::SET_REVERB_PARAMS;
    cmd.reverbRoomSize = p.roomSize;
    cmd.reverbDamping = p.damping;
    cmd.reverbWidth = p.width;
    cmd.reverbLevel = p.level;
    return cmd;
}
inline bool updateParameter(Parameters current, int id, float value, Parameters& next) noexcept {
    if (!std::isfinite(value)) return false;
    next = current;
    switch (id) {
        case 1: next.roomSize = value; break;
        case 2: next.damping = value; break;
        case 3: next.width = value; break;
        case 4: next.level = value; break;
        default: return false;
    }
    return validParameters(next.roomSize, next.damping, next.width, next.level);
}
// Caller serializes producers with its configuration mutex.
inline bool enqueueParametersLocked(SynthCmdQueue& queue, Parameters& desired, Parameters next) noexcept {
    if (!validParameters(next.roomSize, next.damping, next.width, next.level)) return false;
    const SynthCmd cmd = makeCommand(next);
    if (!queue.push(cmd)) return false;
    desired = next;
    return true;
}
inline bool enqueueEnabledLocked(SynthCmdQueue& queue, std::atomic<int>& desired, bool enabled) noexcept {
    SynthCmd cmd{};
    cmd.type = SynthCmdType::SET_REVERB;
    cmd.a = enabled ? 1 : 0;
    if (!queue.push(cmd)) return false;
    desired.store(cmd.a, std::memory_order_relaxed);
    return true;
}
}
