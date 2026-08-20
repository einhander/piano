/*
 * Minimal iconv shim for Android (bionic does not ship iconv).
 *
 * The LADSPA DSP path does not perform charset conversion, so these stubs
 * simply report failure: iconv_open returns (iconv_t)(-1) and iconv/_close
 * are no-ops. Any charset conversion requested by the runtime will fall back
 * to its own error handling (init_iconv_* returns iconv_t(-1)).
 */
#include <cstddef>
#include <iconv.h>

#if defined(__ANDROID__)

extern "C"
{
    iconv_t iconv_open(const char * /*tocode*/, const char * /*fromcode*/)
    {
        return iconv_t(-1);
    }

    size_t iconv(iconv_t /*cd*/,
                 char **inbuf, size_t *inbytesleft,
                 char **outbuf, size_t *outbytesleft)
    {
        // Report an irrecoverable error. If buffers are present we must not
        // consume/produce anything; iconv() semantics: return (size_t)-1.
        if (inbuf && outbuf && inbytesleft && outbytesleft && *inbytesleft && *outbytesleft)
            return size_t(-1);
        return size_t(-1);
    }

    int iconv_close(iconv_t /*cd*/)
    {
        return 0;
    }
}

#endif /* __ANDROID__ */
