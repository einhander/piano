#ifndef LSP_PLUG_IN_3RDPARTY_SNDFILE_STUB_H_
#define LSP_PLUG_IN_3RDPARTY_SNDFILE_STUB_H_

/*
 * Minimal libsndfile-compatible shim for builds without libsndfile
 * (e.g. the Android LADSPA cross-compile, where libsndfile is unavailable).
 *
 * It declares just enough of the libsndfile API (types, enums and function
 * prototypes) for the rest of lsp-runtime-lib to compile. Every function is
 * resolved to a no-op stub that reports failure; the LADSPA DSP path never
 * decodes/encodes audio files on disk, so this is safe.
 *
 * The numeric values of the SF_FORMAT_* / SF_ERR_* enumerators are irrelevant
 * at runtime (the stub sf_open() always returns NULL, so the format-switch
 * sites in InAudioFileStream/OutAudioFileStream are unreachable) and are chosen
 * only to be distinct compile-time constants.
 */

#include <stdint.h>
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef int64_t sf_count_t;

typedef struct sf_placeholder      SNDFILE;

typedef struct SF_INFO
{
    sf_count_t  frames;
    int         samplerate;
    int         channels;
    int         format;
    int         sections;
    int         seekable;
} SF_INFO;

enum
{
    SFM_READ  = 0x10,
    SFM_WRITE = 0x20,
    SFM_RDWR  = 0x30
};

/* Major formats (high byte) */
#define SF_FORMAT_WAV        0x010000
#define SF_FORMAT_AIFF       0x020000
#define SF_FORMAT_AU         0x030000
#define SF_FORMAT_RAW        0x040000
#define SF_FORMAT_PAF        0x050000
#define SF_FORMAT_SVX        0x060000
#define SF_FORMAT_NIST       0x070000
#define SF_FORMAT_VOC        0x080000
#define SF_FORMAT_IRCAM      0x0A0000
#define SF_FORMAT_W64        0x0B0000
#define SF_FORMAT_MAT4       0x0C0000
#define SF_FORMAT_MAT5       0x0D0000
#define SF_FORMAT_PVF        0x0E0000
#define SF_FORMAT_XI         0x100000
#define SF_FORMAT_HTK        0x110000
#define SF_FORMAT_SDS        0x120000
#define SF_FORMAT_AVR        0x130000
#define SF_FORMAT_WAVEX      0x140000
#define SF_FORMAT_SD2        0x160000
#define SF_FORMAT_FLAC       0x170000
#define SF_FORMAT_CAF        0x180000
#define SF_FORMAT_WVE        0x190000
#define SF_FORMAT_OGG        0x200000
#define SF_FORMAT_MPC2K      0x210000
#define SF_FORMAT_RF64       0x230000

/* Subtypes (low byte) */
#define SF_FORMAT_PCM_S8        0x0001
#define SF_FORMAT_PCM_U8        0x0002
#define SF_FORMAT_PCM_16        0x0003
#define SF_FORMAT_PCM_24        0x0004
#define SF_FORMAT_PCM_32        0x0005
#define SF_FORMAT_ULAW          0x0010
#define SF_FORMAT_ALAW          0x0011
#define SF_FORMAT_IMA_ADPCM     0x0012
#define SF_FORMAT_MS_ADPCM      0x0013
#define SF_FORMAT_GSM610        0x0014
#define SF_FORMAT_VOX_ADPCM    0x0017
#define SF_FORMAT_G721_32       0x0030
#define SF_FORMAT_G723_24       0x0031
#define SF_FORMAT_G723_40       0x0032
#define SF_FORMAT_DWVW_12       0x0040
#define SF_FORMAT_DWVW_16       0x0041
#define SF_FORMAT_DWVW_24       0x0042
#define SF_FORMAT_DWVW_N        0x0043
#define SF_FORMAT_DPCM_8        0x0050
#define SF_FORMAT_DPCM_16       0x0051
#define SF_FORMAT_VORBIS        0x0060
#define SF_FORMAT_ALAC_16       0x0070
#define SF_FORMAT_ALAC_20       0x0071
#define SF_FORMAT_ALAC_24       0x0072
#define SF_FORMAT_ALAC_32       0x0073
#define SF_FORMAT_FLOAT         0x0016
#define SF_FORMAT_DOUBLE        0x0017

#define SF_FORMAT_SUBMASK       0x0000FFFF
#define SF_FORMAT_TYPEMASK      0x0FFF0000
#define SF_FORMAT_ENDMASK      0x0F000000

/* Endianness */
#define SF_ENDIAN_FILE      0x00000000
#define SF_ENDIAN_LITTLE    0x10000000
#define SF_ENDIAN_BIG       0x20000000
#define SF_ENDIAN_CPU       0x30000000

enum
{
    SF_ERR_NO_ERROR              = 0,
    SF_ERR_UNRECOGNISED_FORMAT   = SF_ERR_NO_ERROR + 1,
    SF_ERR_MALFORMED_FILE,
    SF_ERR_UNSUPPORTED_ENCODING
};

SNDFILE *sf_open(const char *path, int mode, SF_INFO *sfinfo);
int      sf_error(SNDFILE *sndfile);
int      sf_close(SNDFILE *sndfile);
void     sf_write_sync(SNDFILE *sndfile);

sf_count_t sf_readf_short(SNDFILE *sndfile, short *ptr, sf_count_t frames);
sf_count_t sf_readf_int(SNDFILE *sndfile, int *ptr, sf_count_t frames);
sf_count_t sf_readf_float(SNDFILE *sndfile, float *ptr, sf_count_t frames);
sf_count_t sf_readf_double(SNDFILE *sndfile, double *ptr, sf_count_t frames);

sf_count_t sf_writef_short(SNDFILE *sndfile, const short *ptr, sf_count_t frames);
sf_count_t sf_writef_int(SNDFILE *sndfile, const int *ptr, sf_count_t frames);
sf_count_t sf_writef_float(SNDFILE *sndfile, const float *ptr, sf_count_t frames);
sf_count_t sf_writef_double(SNDFILE *sndfile, const double *ptr, sf_count_t frames);

sf_count_t sf_seek(SNDFILE *sndfile, sf_count_t frames, int whence);

#ifdef __cplusplus
}
#endif

#endif /* LSP_PLUG_IN_3RDPARTY_SNDFILE_STUB_H_ */
