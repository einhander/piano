#pragma once
#include <atomic>
#include <cstdint>
enum class ClipSlotState : uint32_t { Free, Building, Published, Active, RetireRequested, Retired };
struct ClipSlotStateCell { std::atomic<uint32_t> state{static_cast<uint32_t>(ClipSlotState::Free)}; };
static_assert(std::atomic<uint32_t>::is_always_lock_free, "clip slot state must be lock-free");
