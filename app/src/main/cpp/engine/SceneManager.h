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

private:
    std::atomic<int32_t> mCurrentScene{0};
    std::atomic<bool> mSceneChanged{false};
};