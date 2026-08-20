/*
 * Bionic POSIX shims for the Android LADSPA build.
 *
 * Bionic does not implement POSIX shared memory (shm_open/shm_unlink). The
 * LADSPA DSP path does not use cross-process shared memory, so these stubs
 * simply fail (return -1, errno = ENOSYS). Callers fall back to error handling.
 */
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/types.h>

#if defined(__ANDROID__)

extern "C"
{
    int shm_open(const char * /*name*/, int /*oflag*/, mode_t /*mode*/)
    {
        errno = ENOSYS;
        return -1;
    }

    int shm_unlink(const char * /*name*/)
    {
        errno = ENOSYS;
        return -1;
    }
}

#endif /* __ANDROID__ */
