#pragma once

namespace crash {

// Install signal handlers that write a backtrace to <logFilePath> on a native
// crash (SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE/SIGPIPE). logFilePath must point
// into the app's private storage (filesDir). Idempotent; updating the path is
// allowed. Called from nativeInitCrashHandler before any risky native load.
void install(const char* logFilePath);

// Redirect the process stderr (fd 2) to <capturePath> for the duration of a
// risky native load. The Android dynamic linker and Bionic's __libc_fatal /
// async_safe write the reason for a load-time SIGABRT (e.g. a soname/dependency
// conflict) to fd 2 *before* calling abort(), so capturing it lets us name the
// culprit without logcat/adb. Saves the original fd 2; endStderrCapture()
// restores it. Idempotent guards make a second begin a no-op (the original is
// only saved once).
void beginStderrCapture(const char* capturePath);

// Restore the original stderr (fd 2) saved by beginStderrCapture(). Safe to
// call without a matching begin (no-op).
void endStderrCapture();

}  // namespace crash
