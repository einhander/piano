#pragma once

namespace crash {

// Install signal handlers that write a backtrace to <logFilePath> on a native
// crash (SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE/SIGPIPE). logFilePath must point
// into the app's private storage (filesDir). Idempotent; updating the path is
// allowed. Called from nativeInitCrashHandler before any risky native load.
void install(const char* logFilePath);

}  // namespace crash
