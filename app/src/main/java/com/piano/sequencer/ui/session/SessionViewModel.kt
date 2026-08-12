package com.piano.sequencer.ui.session

import androidx.lifecycle.ViewModel
import com.piano.sequencer.NativeEngineBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClipCell(
    val trackId: Int,
    val sceneId: Int,
    val clipId: Int,
    val isPlaying: Boolean,
    val isQueued: Boolean,
    val name: String
)

data class SessionUiState(
    val isPlaying: Boolean = false,
    val isRecording: Boolean = false,
    val isCountingIn: Boolean = false,
    val currentScene: Int = 0,
    val bpm: Float = 120f,
    val currentBar: Int = 1,
    val currentBeat: Int = 1,
    val cells: List<ClipCell> = emptyList(),
    val trackCount: Int = 0,
    val sceneCount: Int = 0
)

class SessionViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    fun onPlayPause() {
        val newState = !_uiState.value.isPlaying
        _uiState.value = _uiState.value.copy(isPlaying = newState)
        if (newState) {
            NativeEngineBridge.nativeSetTransportState(1) // Playing
        } else {
            NativeEngineBridge.nativeSetTransportState(0) // Stopped
        }
    }

    fun onLaunchScene(sceneId: Int) {
        NativeEngineBridge.nativeSwitchScene(sceneId)
        _uiState.value = _uiState.value.copy(currentScene = sceneId)
    }

    fun onNextScene() {
        val next = NativeEngineBridge.nativeNextScene()
        if (next >= 0) {
            NativeEngineBridge.nativeSwitchScene(next)
            _uiState.value = _uiState.value.copy(currentScene = next)
        }
    }

    fun onPreviousScene() {
        val prev = NativeEngineBridge.nativePreviousScene()
        if (prev >= 0) {
            NativeEngineBridge.nativeSwitchScene(prev)
            _uiState.value = _uiState.value.copy(currentScene = prev)
        }
    }

    fun onPanic() {
        NativeEngineBridge.nativePanic()
    }

    fun onUpdateTransport(bar: Int, beat: Int, isPlaying: Boolean) {
        _uiState.value = _uiState.value.copy(
            currentBar = bar,
            currentBeat = beat,
            isPlaying = isPlaying
        )
    }

    fun updateBpm(bpm: Float) {
        NativeEngineBridge.nativeSetBPM(bpm.toDouble())
        _uiState.value = _uiState.value.copy(bpm = bpm)
    }

    fun setTrackCount(count: Int) {
        _uiState.value = _uiState.value.copy(trackCount = count)
    }

    fun setSceneCount(count: Int) {
        _uiState.value = _uiState.value.copy(sceneCount = count)
    }

    fun updateClipState(trackId: Int, sceneId: Int, isPlaying: Boolean, isQueued: Boolean) {
        val cells = _uiState.value.cells.toMutableList()
        val index = cells.indexOfFirst { it.trackId == trackId && it.sceneId == sceneId }
        if (index >= 0) {
            val cell = cells[index]
            cells[index] = cell.copy(isPlaying = isPlaying, isQueued = isQueued)
        }
        _uiState.value = _uiState.value.copy(cells = cells)
    }

    fun refreshFromNative() {
        _uiState.value = _uiState.value.copy(
            currentScene = NativeEngineBridge.nativeCurrentSceneId(),
            isPlaying = NativeEngineBridge.nativeIsAudioPlaying()
        )
    }

    fun onToggleRecordArm(trackId: Int) {
        val cells = _uiState.value.cells.toMutableList()
        // Toggle arm state tracked per track via cell index
        _uiState.value = _uiState.value.copy()
        NativeEngineBridge.nativeSetRecordArmed(trackId, !_uiState.value.isRecording)
    }

    fun onStartRecording() {
        _uiState.value = _uiState.value.copy(isRecording = true)
        NativeEngineBridge.nativeStartRecording()
    }

    fun onStopRecording() {
        _uiState.value = _uiState.value.copy(isRecording = false)
        NativeEngineBridge.nativeStopRecording()
    }

    fun onToggleOverdub() {
        val current = _uiState.value.isRecording
        NativeEngineBridge.nativeSetOverdub(!current)
    }

    fun onCountIn(beats: Int = 4) {
        _uiState.value = _uiState.value.copy(isCountingIn = true)
        NativeEngineBridge.nativeStartCountIn(beats)
    }

    fun updateCountInState(countingIn: Boolean) {
        _uiState.value = _uiState.value.copy(isCountingIn = countingIn)
    }
}