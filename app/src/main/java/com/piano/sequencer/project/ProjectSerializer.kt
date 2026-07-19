package com.piano.sequencer.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProjectSerializer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @Serializable
    data class SerializableProject(
        val id: String,
        val name: String,
        val bpm: Double,
        val ppq: Int,
        val numerator: Int,
        val denominator: Int,
        val tracks: List<SerializableTrack>,
        val scenes: List<SerializableScene>,
        val masterGain: Float,
        val polyphony: Int,
        val soundFontPath: String?,
        val createdAt: Long,
        val updatedAt: Long
    )

    @Serializable
    data class SerializableTrack(
        val id: String,
        val name: String,
        val trackId: Int,
        val volume: Float,
        val pan: Float,
        val muted: Boolean,
        val solo: Boolean,
        val clips: List<SerializableClip>,
        val program: Int,
        val transpose: Int,
        val velocityScale: Float
    )

    @Serializable
    data class SerializableClip(
        val id: String,
        val name: String,
        val clipId: Int,
        val trackId: String,
        val startTick: Int,
        val lengthTicks: Int,
        val events: List<SerializableMidiEvent>,
        val audioFilePath: String?
    )

    @Serializable
    data class SerializableMidiEvent(
        val tick: Int,
        val status: Int,
        val data1: Int,
        val data2: Int
    )

    @Serializable
    data class SerializableScene(
        val id: String,
        val name: String,
        val sceneId: Int,
        val trackScenes: Map<String, SerializableSceneTrackState>
    )

    @Serializable
    data class SerializableSceneTrackState(
        val clipId: String?,
        val startTick: Int,
        val lengthTicks: Int
    )

    fun toJson(project: Project): String {
        val serializable = SerializableProject(
            project.id, project.name, project.bpm, project.ppq,
            project.numerator, project.denominator,
            project.tracks.map { track ->
                SerializableTrack(
                    track.id, track.name, track.trackId, track.volume,
                    track.pan, track.muted, track.solo,
                    track.clips.map { clip ->
                        SerializableClip(
                            clip.id, clip.name, clip.clipId, clip.trackId,
                            clip.startTick, clip.lengthTicks,
                            clip.events.map { event ->
                                SerializableMidiEvent(event.tick, event.status, event.data1, event.data2)
                            },
                            clip.audioFilePath
                        )
                    },
                    track.program, track.transpose, track.velocityScale
                )
            },
            project.scenes.map { scene ->
                SerializableScene(
                    scene.id, scene.name, scene.sceneId,
                    scene.trackScenes.mapValues { (_, state) ->
                        SerializableSceneTrackState(state.clipId, state.startTick, state.lengthTicks)
                    }
                )
            },
            project.masterGain, project.polyphony, project.soundFontPath,
            project.createdAt, project.updatedAt
        )
        return json.encodeToString(serializable)
    }

    fun fromJson(jsonString: String): Project {
        val serializable = json.decodeFromString<SerializableProject>(jsonString)
        return Project(
            id = serializable.id,
            name = serializable.name,
            bpm = serializable.bpm,
            ppq = serializable.ppq,
            numerator = serializable.numerator,
            denominator = serializable.denominator,
            tracks = serializable.tracks.map { track ->
                Track(
                    id = track.id, name = track.name, trackId = track.trackId,
                    volume = track.volume, pan = track.pan,
                    muted = track.muted, solo = track.solo,
                    clips = track.clips.map { clip ->
                        Clip(
                            id = clip.id, name = clip.name, clipId = clip.clipId,
                            trackId = clip.trackId, startTick = clip.startTick,
                            lengthTicks = clip.lengthTicks,
                            events = clip.events.map { event ->
                                Clip.MidiEvent(event.tick, event.status, event.data1, event.data2)
                            }.toMutableList(),
                            audioFilePath = clip.audioFilePath
                        )
                    }.toMutableList(),
                    program = track.program, transpose = track.transpose,
                    velocityScale = track.velocityScale
                )
            }.toMutableList(),
            scenes = serializable.scenes.map { scene ->
                Scene(
                    id = scene.id, name = scene.name, sceneId = scene.sceneId,
                    trackScenes = scene.trackScenes.mapValues { (_, state) ->
                        Scene.SceneTrackState(state.clipId, state.startTick, state.lengthTicks)
                    }.toMutableMap()
                )
            }.toMutableList(),
            masterGain = serializable.masterGain,
            polyphony = serializable.polyphony,
            soundFontPath = serializable.soundFontPath,
            createdAt = serializable.createdAt,
            updatedAt = serializable.updatedAt
        )
    }
}