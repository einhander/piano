#include "../../main/cpp/audio/CallbackTiming.h"
#include <cassert>

int main() {
    CallbackTiming t;
    t.enter(1000000);
    t.finish(500, 64, 48000);
    t.enter(2000000);
    t.finish(1400, 64, 48000);
    uint32_t count, frames, wall, gap, over;
    count=t.take(t.count); frames=t.take(t.frames); wall=t.take(t.maxWallUs);
    gap=t.take(t.maxGapUs); over=t.take(t.overBudget);
    assert(count == 2 && frames == 128 && wall == 1400 && gap == 1000 && over == 1);
    assert(t.take(t.minFrames) == 64 && t.take(t.maxFrames) == 64);
    assert(t.take(t.count) == 0);
    t.finishStage(23, 47);
    t.finishStage(31, 41);
    assert(t.take(t.stageCount) == 2);
    assert(t.take(t.synthWallUs) == 31);
    assert(t.take(t.engineCpuUs) == 47);
    t.callbackEnterTotal.fetch_add(1, std::memory_order_relaxed);
    t.callbackStage.store(3, std::memory_order_relaxed);
    assert(t.callbackEnterTotal.load(std::memory_order_relaxed) == 1);
    assert(t.callbackExitTotal.load(std::memory_order_relaxed) == 0);
    assert(t.callbackStage.load(std::memory_order_relaxed) == 3);
    const std::array<uint32_t, 7> details{11, 12, 13, 14, 15, 16, 17};
    t.finishDetailedStage(10, 60, details);
    assert(t.engineCpuUs.load(std::memory_order_relaxed) == 60);
    assert(t.detailSynthCpuUs.load(std::memory_order_relaxed) == 11);
    assert(t.detailOtherCpuUs.load(std::memory_order_relaxed) == 17);
}
