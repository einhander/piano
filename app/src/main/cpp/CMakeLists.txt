cmake_minimum_required(VERSION 3.22.1)
project("live-sequencer")

# Diagnostic clock sampling is explicitly selected by Gradle build type;
# do not infer it from NDEBUG, which varies with native build configuration.
option(PIANO_CALLBACK_DIAGNOSTICS "Enable callback timing instrumentation" OFF)

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
set(enable-threads ON CACHE BOOL "FluidSynth experimental mixer workers" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "")
# Optional source-prepared GCEM headers for fully offline FluidSynth builds.
if(DEFINED ENV{PIANO_GCEM_INCLUDE_DIR} AND EXISTS "$ENV{PIANO_GCEM_INCLUDE_DIR}/gcem.hpp")
    set(GCEM_INCLUDE_DIR "$ENV{PIANO_GCEM_INCLUDE_DIR}" CACHE PATH "Prepared GCEM include directory" FORCE)
endif()
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/third_party/fluidsynth ${CMAKE_CURRENT_BINARY_DIR}/fluidsynth)

# LAME 4.0 encoder library only; sources and notices are vendored from the
# checksum-pinned upstream release. No command-line frontend or decoder.
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/third_party/lame)

# 0xFX guitar DSP core (vendored, pinned SHA — third_party/0xFX/UPSTREAM.txt).
# Separate static C library mirroring upstream's fx_engine target; no
# add_subdirectory on the upstream root (it builds GUI/desktop/plugins).
# C standard: gnu99. Upstream relies on CMAKE_C_EXTENSIONS=ON (default):
# POSIX timespec/clock_gettime in cab_ir.c and log.c are hidden under strict
# c99. See docs/guitar-fx-port.md.
add_library(fx0_engine STATIC
    third_party/0xFX/src/engine/internal/engine.c
    third_party/0xFX/src/engine/internal/amp.c
    third_party/0xFX/src/engine/internal/biquad.c
    third_party/0xFX/src/engine/internal/noise_gate.c
    third_party/0xFX/src/engine/internal/tuner.c
    third_party/0xFX/src/engine/internal/pedals.c
    third_party/0xFX/src/engine/internal/studio.c
    third_party/0xFX/src/engine/internal/cab_ir.c
    third_party/0xFX/src/engine/internal/mic_sim.c
    third_party/0xFX/src/engine/internal/preset.c
    third_party/0xFX/src/engine/internal/looper.c
    third_party/0xFX/src/core/log.c
    third_party/0xFX/deps/cJSON.c
    third_party/0xFX/deps/kissfft/kiss_fft.c
    third_party/0xFX/deps/kissfft/kiss_fftr.c
)
target_include_directories(fx0_engine PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/0xFX/src/engine
)
target_include_directories(fx0_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/0xFX/deps
)
set_target_properties(fx0_engine PROPERTIES
    C_STANDARD 99
    C_EXTENSIONS ON
    POSITION_INDEPENDENT_CODE ON
)
target_link_libraries(fx0_engine PRIVATE m)

# FluidSynth finds OpenMP::OpenMP_C internally; expose matching C++ runtime
# target to the app for worker-thread OpenMP controls (same NDK runtime).
find_package(OpenMP REQUIRED COMPONENTS CXX)

# Native library
add_library(native-lib SHARED
    native_engine_jni.cpp
    audio/OboeOutput.cpp
    audio/RingBuffer.cpp
    audio/AudioClipPlayer.cpp
    audio/AudioDecoderBridge.cpp
    audio/Mp3AudioRecorder.cpp
    audio/PcmSlotPlayer.cpp
    synth/FluidSynthEngine.cpp
    realtime/MidiQueue.cpp
    realtime/SynthCmdQueue.cpp
    realtime/TransportCmdQueue.cpp
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
    effects/SharedStereoDelay.cpp
    effects/InstrumentDelayBank.cpp
    effects/InstrumentEffectManager.cpp
    effects/guitar/GuitarRigParams.cpp
    effects/guitar/GuitarRigEffect.cpp
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
if(PIANO_CALLBACK_DIAGNOSTICS)
    target_compile_definitions(native-lib PRIVATE PIANO_CALLBACK_DIAGNOSTICS=1)
else()
    target_compile_definitions(native-lib PRIVATE PIANO_CALLBACK_DIAGNOSTICS=0)
endif()

# Link libraries
target_link_libraries(native-lib
    oboe
    mp3lame
    libfluidsynth
    fx0_engine
    OpenMP::OpenMP_CXX
    log
    android
    OpenSLES
    # dlopen for the runtime LSP LADSPA bundle loader.
    dl
)
