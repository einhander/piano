#pragma once

// Tiny logging shim. On Android it routes to logcat (tag "PianoLSP"); on the
// host (offline unit/integration tests) it falls back to stderr so the same
// source files compile under both g++ and the NDK toolchain.

#ifdef __ANDROID__
#include <android/log.h>
#define LSP_LOG_TAG "PianoLSP"
#define LSP_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LSP_LOG_TAG, __VA_ARGS__)
#define LSP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LSP_LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LSP_LOGI(...) do { std::fprintf(stderr, "[I] " __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#define LSP_LOGE(...) do { std::fprintf(stderr, "[E] " __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#endif
