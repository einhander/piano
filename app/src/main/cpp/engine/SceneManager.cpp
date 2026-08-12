#include "SceneManager.h"
#include <cstring>

SceneManager::SceneManager() {
    // Initialize launch queue slots
    for (int32_t i = 0; i < kMaxQueueDepth; i++) {
        mLaunchQueue[i].sceneId = 0;
        mLaunchQueue[i].targetFrame = 0;
        mLaunchQueue[i].active = false;
    }
    // Initialize scene registry slots
    for (int32_t i = 0; i < kMaxScenes; i++) {
        mScenes[i].sceneId = 0;
        mScenes[i].name[0] = '\0';
        mScenes[i].active = false;
    }
}

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

bool SceneManager::queueSceneLaunch(int32_t sceneId, int64_t targetFrame) {
    int32_t count = mQueueCount.load(std::memory_order_acquire);
    if (count >= kMaxQueueDepth) return false;

    int32_t tail = mQueueTail.load(std::memory_order_acquire);
    mLaunchQueue[tail].sceneId = sceneId;
    mLaunchQueue[tail].targetFrame = targetFrame;
    mLaunchQueue[tail].active = true;

    // Publish: increment tail, then increment count
    mQueueTail.store((tail + 1) % kMaxQueueDepth, std::memory_order_release);
    mQueueCount.store(count + 1, std::memory_order_release);
    return true;
}

bool SceneManager::processLaunchQueue(int64_t currentFrame) {
    int32_t count = mQueueCount.load(std::memory_order_acquire);
    if (count <= 0) return false;

    int32_t head = mQueueHead.load(std::memory_order_acquire);

    // Check if the head command's target frame has been reached
    if (mLaunchQueue[head].targetFrame > currentFrame) {
        return false;
    }

    // Execute the launch
    int32_t sceneId = mLaunchQueue[head].sceneId;
    bool switched = switchScene(sceneId);

    // Mark slot as inactive
    mLaunchQueue[head].active = false;

    // Advance head
    mQueueHead.store((head + 1) % kMaxQueueDepth, std::memory_order_release);
    mQueueCount.store(count - 1, std::memory_order_release);

    return switched;
}

void SceneManager::registerScene(int32_t sceneId, const char* name) {
    int32_t count = mSceneCount.load(std::memory_order_acquire);
    if (count >= kMaxScenes) return;

    int32_t slot = count;
    mScenes[slot].sceneId = sceneId;
    if (name) {
        // Copy name, null-terminate
        std::strncpy(mScenes[slot].name, name, sizeof(mScenes[slot].name) - 1);
        mScenes[slot].name[sizeof(mScenes[slot].name) - 1] = '\0';
    } else {
        mScenes[slot].name[0] = '\0';
    }
    mScenes[slot].active = true;

    mSceneCount.store(count + 1, std::memory_order_release);
}

int32_t SceneManager::nextScene() const {
    int32_t count = mSceneCount.load(std::memory_order_acquire);
    if (count <= 0) return -1;

    int32_t current = mCurrentScene.load(std::memory_order_acquire);

    // Find current index, then return next
    int32_t currentIndex = -1;
    for (int32_t i = 0; i < count; i++) {
        if (mScenes[i].sceneId == current) {
            currentIndex = i;
            break;
        }
    }

    if (currentIndex < 0) {
        // Current scene not found, return first registered scene
        return mScenes[0].sceneId;
    }

    int32_t nextIndex = (currentIndex + 1) % count;
    return mScenes[nextIndex].sceneId;
}

int32_t SceneManager::previousScene() const {
    int32_t count = mSceneCount.load(std::memory_order_acquire);
    if (count <= 0) return -1;

    int32_t current = mCurrentScene.load(std::memory_order_acquire);

    int32_t currentIndex = -1;
    for (int32_t i = 0; i < count; i++) {
        if (mScenes[i].sceneId == current) {
            currentIndex = i;
            break;
        }
    }

    if (currentIndex < 0) {
        return mScenes[count - 1].sceneId;
    }

    int32_t prevIndex = (currentIndex - 1 + count) % count;
    return mScenes[prevIndex].sceneId;
}