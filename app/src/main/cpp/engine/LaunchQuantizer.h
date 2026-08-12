#pragma once

#include <cstdint>
#include <atomic>

struct TransportState;

enum class QuantizationGrid : int32_t {
    Immediate = 0,
    Beat1,     // Next beat (1/1)
    Beat2,     // Next half note (1/2)
    Beat4,     // Next quarter note (1/4)
    Bar1,      // Next bar
    Bar2,      // Next 2 bars
    Bar4       // Next 4 bars
};

class LaunchQuantizer {
public:
    LaunchQuantizer();
    ~LaunchQuantizer();

    // Initialize with transport reference
    void init(TransportState* transport);

    // Schedule a launch command to fire at the next quantization boundary.
    // Returns the target frame when the command will fire.
    // If Immediate, returns current frame.
    int64_t scheduleLaunch(QuantizationGrid grid, int64_t currentFrame);

    // Get the current quantization grid setting
    QuantizationGrid getGrid() const { return mGrid.load(std::memory_order_acquire); }

    // Set the quantization grid (atomic, safe for UI thread)
    void setGrid(QuantizationGrid grid);

    // Check if a scheduled launch is pending (for UI feedback)
    bool isLaunchPending() const { return mPending.load(std::memory_order_acquire); }

    // Mark launch as acknowledged (clears pending state)
    void acknowledgeLaunch() { mPending.store(false, std::memory_order_release); }

private:
    TransportState* mTransport = nullptr;
    std::atomic<QuantizationGrid> mGrid{QuantizationGrid::Immediate};
    std::atomic<bool> mPending{false};
};