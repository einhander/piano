#include "LaunchQuantizer.h"
#include "model/TransportState.h"

LaunchQuantizer::LaunchQuantizer() = default;
LaunchQuantizer::~LaunchQuantizer() = default;

void LaunchQuantizer::init(TransportState* transport) {
    mTransport = transport;
}

void LaunchQuantizer::setGrid(QuantizationGrid grid) {
    mGrid.store(grid, std::memory_order_release);
}

int64_t LaunchQuantizer::scheduleLaunch(QuantizationGrid grid, int64_t currentFrame) {
    if (!mTransport) {
        mPending.store(true, std::memory_order_release);
        return currentFrame;
    }

    QuantizationGrid effectiveGrid = grid != QuantizationGrid::Immediate
        ? grid
        : mGrid.load(std::memory_order_acquire);

    if (effectiveGrid == QuantizationGrid::Immediate) {
        mPending.store(true, std::memory_order_release);
        return currentFrame;
    }

    // Get current tick position
    int64_t currentTickFrame = mTransport->framePosition.load(std::memory_order_acquire);
    double currentTick = mTransport->frameToTick(currentTickFrame);

    int32_t ppq = mTransport->ppq;
    int16_t numerator = mTransport->numerator;

    // Calculate ticks per beat (ppq) and ticks per bar (ppq * numerator)
    // Grid determines the subdivision of the beat:
    //   Beat1  = 1 beat  (1/1)  → ppq ticks
    //   Beat2  = 2 beats  (1/2)  → 2 * ppq ticks
    //   Beat4  = 4 beats  (1/4)  → 4 * ppq ticks
    //   Bar1   = 1 bar           → ppq * numerator ticks
    //   Bar2   = 2 bars          → 2 * ppq * numerator ticks
    //   Bar4   = 4 bars          → 4 * ppq * numerator ticks

    int64_t ticksPerUnit = 0;
    switch (effectiveGrid) {
        case QuantizationGrid::Beat1:
            ticksPerUnit = ppq;
            break;
        case QuantizationGrid::Beat2:
            ticksPerUnit = ppq * 2;
            break;
        case QuantizationGrid::Beat4:
            ticksPerUnit = ppq * 4;
            break;
        case QuantizationGrid::Bar1:
            ticksPerUnit = ppq * numerator;
            break;
        case QuantizationGrid::Bar2:
            ticksPerUnit = ppq * numerator * 2;
            break;
        case QuantizationGrid::Bar4:
            ticksPerUnit = ppq * numerator * 4;
            break;
    }

    // Calculate next boundary tick
    int64_t nextBoundaryTick = (static_cast<int64_t>(currentTick) / ticksPerUnit + 1) * ticksPerUnit;

    // Convert to frame position
    int64_t targetFrame = mTransport->tickToFrame(static_cast<double>(nextBoundaryTick));

    // Ensure we don't schedule in the past
    if (targetFrame <= currentFrame) {
        targetFrame = currentFrame;
    }

    mPending.store(true, std::memory_order_release);
    return targetFrame;
}