#include "engine/ClipSlotState.h"
#include <cassert>

int main() {
    ClipSlotStateCell slot;
    ClipSlotStateCell retireBeforeActivate;
    retireBeforeActivate.state.store(static_cast<uint32_t>(ClipSlotState::Published), std::memory_order_release);
    uint32_t retireExpected = static_cast<uint32_t>(ClipSlotState::Published);
    assert(retireBeforeActivate.state.compare_exchange_strong(retireExpected,
        static_cast<uint32_t>(ClipSlotState::RetireRequested)));
    retireExpected = static_cast<uint32_t>(ClipSlotState::Published);
    assert(!retireBeforeActivate.state.compare_exchange_strong(retireExpected,
        static_cast<uint32_t>(ClipSlotState::Active)));
    uint32_t expected = static_cast<uint32_t>(ClipSlotState::Free);
    assert(slot.state.compare_exchange_strong(expected,
        static_cast<uint32_t>(ClipSlotState::Building)));
    slot.state.store(static_cast<uint32_t>(ClipSlotState::Published), std::memory_order_release);
    expected = static_cast<uint32_t>(ClipSlotState::Free);
    assert(!slot.state.compare_exchange_strong(expected,
        static_cast<uint32_t>(ClipSlotState::Building)));
    expected = static_cast<uint32_t>(ClipSlotState::Published);
    assert(slot.state.compare_exchange_strong(expected,
        static_cast<uint32_t>(ClipSlotState::Active)));
    expected = static_cast<uint32_t>(ClipSlotState::Free);
    assert(!slot.state.compare_exchange_strong(expected,
        static_cast<uint32_t>(ClipSlotState::Building)));
    expected = static_cast<uint32_t>(ClipSlotState::Active);
    assert(slot.state.compare_exchange_strong(expected,
        static_cast<uint32_t>(ClipSlotState::RetireRequested)));
    expected = static_cast<uint32_t>(ClipSlotState::RetireRequested);
    assert(slot.state.compare_exchange_strong(expected,
        static_cast<uint32_t>(ClipSlotState::Retired)));
    expected = static_cast<uint32_t>(ClipSlotState::RetireRequested);
    assert(!slot.state.compare_exchange_strong(expected,
        static_cast<uint32_t>(ClipSlotState::Building)));
    expected = static_cast<uint32_t>(ClipSlotState::Retired);
    assert(slot.state.compare_exchange_strong(expected,
        static_cast<uint32_t>(ClipSlotState::Building)));
    return 0;
}
