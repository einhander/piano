#include "engine/Sequencer.h"
#include <cassert>

int main() {
    Sequencer sequencer;
    DueSequencerEvent events[2];
    sequencer.scheduleEvent(5, 0x90, 9, 1);
    assert(sequencer.collectDueEvents(0, 10, events, 2) == 0);
    sequencer.start();
    assert(sequencer.collectDueEvents(0, 10, events, 2) == 1);
    assert(events[0].targetFrame == 5);
    sequencer.scheduleEvent(0, 0x90, 1, 1);
    assert(sequencer.collectDueEvents(0, 1, events, 2) == 1);
    assert(events[0].targetFrame == 0 && events[0].message.timestamp != 0);
    sequencer.scheduleEvent(10, 0x90, 60, 100);
    sequencer.scheduleEvent(20, 0x80, 60, 0);

    assert(sequencer.collectDueEvents(0, 20, events, 2) == 1);
    assert(events[0].targetFrame == 10);
    assert(sequencer.collectDueEvents(20, 30, events, 2) == 1);
    assert(events[0].targetFrame == 20);

    sequencer.scheduleEvent(40, 0x90, 2, 1);
    sequencer.scheduleEvent(40, 0x90, 3, 1);
    assert(sequencer.collectDueEvents(0, 41, events, 2) == 2);
    assert(events[0].message.data1 == 2 && events[1].message.data1 == 3);

    sequencer.scheduleEvent(-1, 0x90, 1, 1);
    assert(sequencer.collectDueEvents(0, 1, events, 2) == 0);
    return 0;
}
