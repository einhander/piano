#include "engine/TimedMidiEvent.h"
#include <cassert>
int main() {
    TimedMidiEvent a{}, b{}; a.targetFrame=b.targetFrame=4; a.phase=TimedMidiEvent::Cleanup; b.phase=TimedMidiEvent::Scheduled;
    assert(timedMidiEventLess(a,b));
    a.phase=b.phase; a.sourceSlot=1; b.sourceSlot=2; assert(timedMidiEventLess(a,b));
    a.sourceSlot=b.sourceSlot; a.order=1; b.order=2; assert(timedMidiEventLess(a,b));
    a.targetFrame=3; b.targetFrame=4; assert(timedMidiEventLess(a,b));
    b.targetFrame=3; assert(!timedMidiEventLess(a,b));
    a.targetFrame=b.targetFrame=7; a.sourceSlot=0; b.sourceSlot=1; assert(timedMidiEventLess(a,b));
    return 0;
}
