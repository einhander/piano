// Native crash diagnostics. See CrashHandler.h for the rationale.

#include "CrashHandler.h"

#include <csignal>
#include <cstring>
#include <cstdlib>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <unwind.h>
#include <stdio.h>
#include <ucontext.h>

namespace crash {

static char g_logPath[512] = {};
static bool g_installed = false;
static stack_t g_altStack = {};

struct SigName { int sig; const char* name; };
static const SigName kSigNames[] = {
    { SIGSEGV, "SIGSEGV" }, { SIGABRT, "SIGABRT" }, { SIGBUS, "SIGBUS" },
    { SIGILL, "SIGILL" },   { SIGFPE, "SIGFPE" },   { SIGPIPE, "SIGPIPE" },
};
static const char* sigName(int sig) {
    for (const auto& s : kSigNames) if (s.sig == sig) return s.name;
    return "?";
}

static void writeAll(int fd, const char* s) {
    if (fd < 0 || !s) return;
    ::write(fd, s, strlen(s));
}
static void writeInt(int fd, unsigned long v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%lu", v);
    writeAll(fd, buf);
}
static void writeHex(int fd, unsigned long v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "0x%lx", v);
    writeAll(fd, buf);
}

struct BtCtx { int fd; int count; int max; };

static _Unwind_Reason_Code unwindCb(struct _Unwind_Context* ctx, void* arg) {
    auto* b = static_cast<BtCtx*>(arg);
    if (b->count >= b->max) return _URC_END_OF_STACK;
    _Unwind_Word pc = _Unwind_GetIP(ctx);
    if (pc == 0) return _URC_NO_REASON;
    writeAll(b->fd, "  #");
    writeInt(b->fd, b->count);
    writeAll(b->fd, " ");
    writeHex(b->fd, pc);
    Dl_info info;
    if (dladdr(reinterpret_cast<void*>(pc), &info)) {
        writeAll(b->fd, " ");
        writeAll(b->fd, info.dli_fname ? info.dli_fname : "?");
        if (info.dli_sname) {
            writeAll(b->fd, " ");
            writeAll(b->fd, info.dli_sname);
            writeAll(b->fd, " +");
            writeHex(b->fd, pc - reinterpret_cast<unsigned long>(info.dli_saddr));
        }
    }
    writeAll(b->fd, "\n");
    b->count++;
    return _URC_NO_REASON;
}

static void crashHandler(int sig, siginfo_t* si, void* uc) {
    int fd = -1;
    if (g_logPath[0] != '\0') {
        fd = ::open(g_logPath, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    }
    if (fd >= 0) {
        writeAll(fd, "=== native crash ===\n");
        writeAll(fd, "signal: ");
        writeAll(fd, sigName(sig));
        writeAll(fd, " (");
        writeInt(fd, sig);
        writeAll(fd, ")\n");
        if (si) {
            writeAll(fd, "si_code: ");
            writeInt(fd, static_cast<unsigned long>(si->si_code));
            writeAll(fd, "\nsi_addr: ");
            writeHex(fd, reinterpret_cast<unsigned long>(si->si_addr));
            writeAll(fd, "\n");
        }
        if (uc) {
            auto* ctx = static_cast<ucontext_t*>(uc);
            unsigned long pc = 0;
#ifdef __aarch64__
            pc = ctx->uc_mcontext.pc;
#elif defined(__arm__)
            pc = ctx->uc_mcontext.arm_pc;
#elif defined(__x86_64__)
            pc = ctx->uc_mcontext.gregs[REG_RIP];
#endif
            if (pc) {
                writeAll(fd, "fault PC: ");
                writeHex(fd, pc);
                writeAll(fd, "\n");
            }
        }
        writeAll(fd, "backtrace:\n");
        BtCtx b{fd, 0, 64};
        _Unwind_Backtrace(unwindCb, &b);
        // _Unwind_Backtrace stops where unwind info ends (often the libc abort
        // trampoline), so the LSP static-ctor frames above abort() are lost.
        // Dump /proc/self/maps instead of dl_iterate_phdr: the latter takes
        // the linker's g_dl_mutex, which is likely already held during a
        // dlopen-time abort (→ deadlock, nothing flushes). open()/read() are
        // async-signal-safe; the map shows whether the LSP .so was mapped
        // before the abort (mapped → fault in a static ctor; not mapped → the
        // linker aborted during mapping, e.g. soname/dependency conflict).
        writeAll(fd, "mapped files (from /proc/self/maps):\n");
        int mfd = ::open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
        if (mfd >= 0) {
            char mbuf[1024];
            for (;;) {
                ssize_t n = ::read(mfd, mbuf, sizeof(mbuf));
                if (n <= 0) break;
                ::write(fd, mbuf, static_cast<size_t>(n));
            }
            ::close(mfd);
        }
        writeAll(fd, "=== end ===\n");
        ::fsync(fd);
        ::close(fd);
    }
    // Restore default disposition and re-raise so a tombstone still forms.
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    sigemptyset(&sa.sa_mask);
    sigaction(sig, &sa, nullptr);
    raise(sig);
}

void install(const char* logFilePath) {
    if (logFilePath) {
        strncpy(g_logPath, logFilePath, sizeof(g_logPath) - 1);
        g_logPath[sizeof(g_logPath) - 1] = '\0';
    }
    if (g_installed) return;
    g_installed = true;

    g_altStack.ss_sp = malloc(SIGSTKSZ);
    if (g_altStack.ss_sp) {
        g_altStack.ss_size = SIGSTKSZ;
        g_altStack.ss_flags = 0;
        sigaltstack(&g_altStack, nullptr);
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESETHAND;
    sigemptyset(&sa.sa_mask);
    const int sigs[] = { SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGPIPE };
    for (int s : sigs) sigaction(s, &sa, nullptr);
}

// --- Scoped stderr (fd 2) capture -------------------------------------------------
// The Android dynamic linker and Bionic's __libc_fatal / async_safe write the
// reason for a load-time abort (soname conflict, unsatisfied symbol version,
// bad ELF, …) to fd 2 *before* calling abort(). The signal handler only sees
// the abort frames, so without this capture the reason is lost. begin/end
// redirect fd 2 to a file for the duration of a risky dlopen/loadLibrary.
static int g_savedStderr = -1;

void beginStderrCapture(const char* capturePath) {
    if (!capturePath || capturePath[0] == '\0') return;
    // Only save the original once; a nested begin keeps capturing into the
    // same file (the outer end restores the real fd).
    if (g_savedStderr < 0) {
        g_savedStderr = dup(STDERR_FILENO);
    }
    int fd = ::open(capturePath, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd >= 0) {
        dup2(fd, STDERR_FILENO);
        close(fd);
    }
}

void endStderrCapture() {
    if (g_savedStderr >= 0) {
        dup2(g_savedStderr, STDERR_FILENO);
        close(g_savedStderr);
        g_savedStderr = -1;
    }
}

}  // namespace crash
