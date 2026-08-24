/*
 * libsndfile stub implementation for builds without libsndfile (Android
 * LADSPA cross-compile). Every function reports failure / does nothing;
 * the LADSPA DSP path never reads or writes audio files from disk.
 */
#include <lsp-plug.in/3rdparty/sndfile_stub.h>

extern "C"
{
    SNDFILE *sf_open(const char * /*path*/, int /*mode*/, SF_INFO * /*sfinfo*/)
    {
        return NULL;
    }

    int sf_error(SNDFILE * /*sndfile*/)
    {
        return SF_ERR_UNSUPPORTED_ENCODING;
    }

    int sf_close(SNDFILE * /*sndfile*/)
    {
        return 0;
    }

    void sf_write_sync(SNDFILE * /*sndfile*/)
    {
    }

    sf_count_t sf_readf_short(SNDFILE * /*sndfile*/, short * /*ptr*/, sf_count_t /*frames*/) { return 0; }
    sf_count_t sf_readf_int(SNDFILE * /*sndfile*/, int * /*ptr*/, sf_count_t /*frames*/) { return 0; }
    sf_count_t sf_readf_float(SNDFILE * /*sndfile*/, float * /*ptr*/, sf_count_t /*frames*/) { return 0; }
    sf_count_t sf_readf_double(SNDFILE * /*sndfile*/, double * /*ptr*/, sf_count_t /*frames*/) { return 0; }

    sf_count_t sf_writef_short(SNDFILE * /*sndfile*/, const short * /*ptr*/, sf_count_t /*frames*/) { return 0; }
    sf_count_t sf_writef_int(SNDFILE * /*sndfile*/, const int * /*ptr*/, sf_count_t /*frames*/) { return 0; }
    sf_count_t sf_writef_float(SNDFILE * /*sndfile*/, const float * /*ptr*/, sf_count_t /*frames*/) { return 0; }
    sf_count_t sf_writef_double(SNDFILE * /*sndfile*/, const double * /*ptr*/, sf_count_t /*frames*/) { return 0; }

    sf_count_t sf_seek(SNDFILE * /*sndfile*/, sf_count_t /*frames*/, int /*whence*/) { return 0; }
}
