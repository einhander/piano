#include "SceneManager.h"

SceneManager::SceneManager() = default;
SceneManager::~SceneManager() = default;

bool SceneManager::switchScene(int32_t sceneId) {
    if (sceneId < 0) return false;
    int32_t current = mCurrentScene.load(std::memory_order_acquire);
    if (current != sceneId) {
        mCurrentScene.store(sceneId, std::memory_order_release);
        mSceneChanged.store(true, std::memory_order_release);
        return true;
    }
    return false;
}