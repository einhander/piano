cmake_minimum_required(VERSION 3.22.1)
project("live-sequencer")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Oboe
set(OBOE_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/oboe")
add_subdirectory(${OBOE_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/oboe)

# FluidSynth with minimal options
set(ENABLE_SHARED OFF CACHE BOOL "")
set(ENABLE_DASHBOARD OFF CACHE BOOL "")
set(ENABLE_READLINE OFF CACHE BOOL "")
set(ENABLE_SQLITE3 OFF CACHE BOOL "")
set(ENABLE_SHARED_INTERNAL OFF CACHE BOOL "")
set(ENABLE_OPTIMIZATIONS OFF CACHE BOOL "")
set(ENABLE_FFT_DIALOG OFF CACHE BOOL "")
set(ENABLE_TRACE OFF CACHE BOOL "")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "")
set(LIBSNDFILE_LIBRARY "" CACHE STRING "")
set(LIBSNDFILE_INCLUDE_DIR "" CACHE PATH "")
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/third_party/fluidsynth ${CMAKE_CURRENT_BINARY_DIR}/fluidsynth)

# Native library
add_library(native-lib SHARED
    native_engine_jni.cpp
    audio/OboeOutput.cpp
    audio/RingBuffer.cpp
    audio/AudioClipPlayer.cpp
    audio/AudioDecoderBridge.cpp
    synth/FluidSynthEngine.cpp
    realtime/MidiQueue.cpp
    engine/MidiRouter.cpp
    engine/MidiRecorder.cpp
    engine/Sequencer.cpp
    engine/SceneManager.cpp
    engine/ClipScheduler.cpp
    engine/NativeEngine.cpp
)

# Include directories
target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    engine
    audio
    synth
    realtime
    model
)

# Link libraries
target_link_libraries(native-lib
    oboe
    fluidsynth
    log
    android
    OpenSLES
)