package com.piano.sequencer.project

import java.util.UUID

data class Scene(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Scene 1",
    var sceneId: Int = 0,
    var trackScenes: MutableMap<String, SceneTrackState> = mutableMapOf()
) {
    data class SceneTrackState(
        var clipId: String? = null,
        var startTick: Int = 0,
        var lengthTicks: Int = 960
    )

    fun touch() {}
}