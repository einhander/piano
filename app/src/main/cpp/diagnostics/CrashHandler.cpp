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

}  // namespace crash
