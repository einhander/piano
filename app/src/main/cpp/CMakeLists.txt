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
set(ENABLE_FFT_DIALOG OFF CACHE BOOL "")
# Fix #13: trim FluidSynth for a leaner, faster build on low-end devices.
# (These are the real option names in fluidsynth/CMakeLists.txt; the old
#  uppercase ENABLE_* names above are dead and have no effect.)
set(enable-ladspa OFF CACHE BOOL "")
set(enable-native-dls OFF CACHE BOOL "")
set(enable-network OFF CACHE BOOL "")
set(enable-ipv6 OFF CACHE BOOL "")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "")
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
    realtime/SynthCmdQueue.cpp
    engine/MidiRouter.cpp
    engine/MidiRecorder.cpp
    engine/Sequencer.cpp
    engine/SceneManager.cpp
    engine/ClipScheduler.cpp
    engine/Mixer.cpp
    engine/MasterBus.cpp
    engine/LaunchQuantizer.cpp
    engine/NativeEngine.cpp
    engine/MidiFilePlayer.cpp
    midi/MidiFileParser.cpp
    midi/MidiFileWriter.cpp
    # LSP master effect chain (Milestone 3+). The LADSPA bundle is loaded at
    # runtime via dlopen (no build-time link dependency on the prebuilt .so),
    # so a clean checkout builds with the chain as a passthrough. The vendored
    # ladspa.h (LGPL) is the only header dependency.
    effects/EffectChain.cpp
    effects/ladspa/LadspaEffect.cpp
    effects/ladspa/LadspaRegistry.cpp
    effects/lsp/LspEffectFactory.cpp
    effects/lsp/LspEffectIds.cpp
    diagnostics/CrashHandler.cpp
)

# Include directories
target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    engine
    audio
    synth
    realtime
    model
    midi
    effects
)

# Link libraries
target_link_libraries(native-lib
    oboe
libfluidsynth
    log
    android
    OpenSLES
    # dlopen for the runtime LSP LADSPA bundle loader.
    dl
)