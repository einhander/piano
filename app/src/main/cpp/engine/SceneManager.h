#pragma once

#include <cstdint>
#include <atomic>

struct SceneData {
    int32_t sceneId;
    // Scene-specific data: clip references, track states, etc.
    // MVP: just a placeholder
    int32_t trackCount = 0;
};

class SceneManager {
public:
    SceneManager();
    ~SceneManager();

    // Switch to a scene. Returns true if scene actually changed.
    bool switchScene(int32_t sceneId);

    // Get current scene
    int32_t currentSceneId() const { return mCurrentScene.load(); }

    // Check if scene changed (atomic read)
    bool hasSceneChanged() const { return mSceneChanged.load(); }

    // Acknowledge scene change
    void acknowledgeSceneChange() { mSceneChanged.store(false); }

    // ── Launch queue (pre-allocated, fixed size) ──
    static constexpr int32_t kMaxQueueDepth = 8;

    struct LaunchCommand {
        int32_t sceneId;
        int64_t targetFrame;
        bool active;
    };

    // Queue a scene launch (called from UI thread)
    bool queueSceneLaunch(int32_t sceneId, int64_t targetFrame);

    // Process queued launches — called from audio callback
    // Returns true if a scene was switched
    bool processLaunchQueue(int64_t currentFrame);

    // Get queue depth for UI feedback
    int32_t getQueueDepth() const { return mQueueCount.load(std::memory_order_acquire); }

    // ── Scene registry ──
    static constexpr int32_t kMaxScenes = 16;

    struct SceneEntry {
        int32_t sceneId;
        char name[64];
        bool active;
    };

    // Register a scene (called from UI thread, not audio callback)
    void registerScene(int32_t sceneId, const char* name);

    // Navigate to next/previous scene in registration order
    // Returns the sceneId of the next/previous scene, or -1 if at end/beginning
    int32_t nextScene() const;
    int32_t previousScene() const;

    // Get registered scene count
    int32_t getSceneCount() const { return mSceneCount.load(std::memory_order_acquire); }

private:
    std::atomic<int32_t> mCurrentScene{0};
    std::atomic<bool> mSceneChanged{false};

    // Launch queue (circular buffer)
    LaunchCommand mLaunchQueue[kMaxQueueDepth];
    std::atomic<int32_t> mQueueHead{0};
    std::atomic<int32_t> mQueueTail{0};
    std::atomic<int32_t> mQueueCount{0};

    // Scene registry
    SceneEntry mScenes[kMaxScenes];
    std::atomic<int32_t> mSceneCount{0};
};