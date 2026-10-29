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
    assert(t.take(t.count) == 0);
}
