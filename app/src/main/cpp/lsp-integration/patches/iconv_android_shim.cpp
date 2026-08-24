/*
 * Working iconv implementation for Android (bionic does not ship iconv).
 *
 * Why this exists: the LSP Wrapper::init() reads builtin://manifest.json via
 * io::InSequence, which decodes the (UTF-8) stream to lsp_wchar_t (uint32_t =
 * UTF-32) through CharsetDecoder. CharsetDecoder::init() calls
 * init_iconv_to_wchar_t(), which calls iconv_open("UTF-32LE"/"WCHAR_T",
 * "UTF-8"). If that returns (iconv_t)-1, init() returns STATUS_BAD_LOCALE
 * (status code 29) and wrapper->init() fails -> instantiate() returns nullptr
 * -> every master effect slot is unavailable (chain bypassed). The previous
 * stub returned -1 unconditionally, which is exactly that failure.
 *
 * This implementation supports the conversions the manifest/charset path
 * actually requests, via a Unicode code-point intermediate:
 *   UTF-8, UTF-16LE, UTF-16BE, UTF-32LE, UTF-32BE, WCHAR_T (= UTF-32 on
 *   Android, LE on arm64/armeabi), US-ASCII, ISO-8859-1.
 * Charset names are matched case-insensitively after stripping everything but
 * [a-z0-9] (so "utf-8", "UTF_8", "UTF-32LE" all normalize), matching libiconv/
 * glibc leniency. Unknown charsets return (iconv_t)-1 (errno=EINVAL), which
 * lets the LSP fallback paths behave as before for anything we do not cover.
 */
#include <cstddef>
#include <cstdint>
#include <cerrno>
#include <cstring>
#include <iconv.h>

#if defined(__ANDROID__)

namespace {

enum CsId {
    CS_UNSUPPORTED = 0,
    CS_UTF8,
    CS_UTF16LE,
    CS_UTF16BE,
    CS_UTF32LE,
    CS_UTF32BE,
    CS_ASCII,
    CS_ISO88591,
    CS_WCHAR_T,   // == UTF-32LE on Android (lsp_wchar_t = uint32_t, arm LE)
    CS_UCS4LE,    // alias of UTF-32LE
    CS_UCS4BE,
};

// Normalize "UTF-32LE" / "utf_8" / "WCHAR_T" -> lowercase alnum-only buffer.
CsId cs_id(const char *name)
{
    if (name == nullptr) return CS_UNSUPPORTED;
    char buf[24];
    size_t n = 0;
    for (size_t i = 0; name[i] && n < sizeof(buf) - 1; ++i) {
        char c = name[i];
        if (c >= 'A' && c <= 'Z') c = char(c - 'A' + 'a');
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))
            buf[n++] = c;
    }
    buf[n] = '\0';

    if (!::std::strcmp(buf, "utf8") || !::std::strcmp(buf, "utf")) return CS_UTF8;
    if (!::std::strcmp(buf, "utf16le") || !::std::strcmp(buf, "ucs2le")) return CS_UTF16LE;
    if (!::std::strcmp(buf, "utf16be") || !::std::strcmp(buf, "ucs2be")) return CS_UTF16BE;
    if (!::std::strcmp(buf, "utf32le")) return CS_UTF32LE;
    if (!::std::strcmp(buf, "utf32be")) return CS_UTF32BE;
    if (!::std::strcmp(buf, "wchart") || !::std::strcmp(buf, "wchar")) return CS_WCHAR_T;
    if (!::std::strcmp(buf, "ucs4le")) return CS_UCS4LE;
    if (!::std::strcmp(buf, "ucs4be")) return CS_UCS4BE;
    if (!::std::strcmp(buf, "ascii") || !::std::strcmp(buf, "usascii")) return CS_ASCII;
    if (!::std::strcmp(buf, "iso88591") || !::std::strcmp(buf, "latin1") ||
        !::std::strcmp(buf, "iso885915")) return CS_ISO88591;
    return CS_UNSUPPORTED;
}

// Decode one code point from *inbuf/*inleft. Advances inbuf/inleft.
// Returns the code point (<=0x10FFFF), -1 on invalid (EILSEQ), or -2 on
// incomplete input (EINVAL: caller should stop and wait for more bytes).
long decode_next(CsId cs, const uint8_t *&inbuf, size_t &inleft)
{
    switch (cs) {
        case CS_UTF8: {
            if (inleft == 0) { errno = EINVAL; return -2; }
            uint8_t b0 = inbuf[0];
            size_t need;
            uint32_t cp;
            if (b0 < 0x80) { cp = b0; need = 1; }
            else if ((b0 & 0xE0) == 0xC0) { cp = b0 & 0x1F; need = 2; }
            else if ((b0 & 0xF0) == 0xE0) { cp = b0 & 0x0F; need = 3; }
            else if ((b0 & 0xF8) == 0xF0) { cp = b0 & 0x07; need = 4; }
            else { errno = EILSEQ; return -1; }  // invalid lead byte
            if (inleft < need) { errno = EINVAL; return -2; }  // incomplete
            for (size_t i = 1; i < need; ++i) {
                uint8_t b = inbuf[i];
                if ((b & 0xC0) != 0x80) { errno = EILSEQ; return -1; }
                cp = (cp << 6) | (b & 0x3F);
            }
            if (need == 2 && cp < 0x80) { errno = EILSEQ; return -1; }      // overlong
            if (need == 3 && cp < 0x800) { errno = EILSEQ; return -1; }     // overlong
            if (need == 4 && (cp < 0x10000 || cp > 0x10FFFF)) { errno = EILSEQ; return -1; }
            if (cp >= 0xD800 && cp <= 0xDFFF) { errno = EILSEQ; return -1; } // surrogate
            inbuf += need; inleft -= need;
            return long(cp);
        }
        case CS_UTF16LE: {
            if (inleft < 2) { errno = EINVAL; return -2; }
            uint16_t u = uint16_t(inbuf[0]) | (uint16_t(inbuf[1]) << 8);
            if (u < 0xD800 || u > 0xDFFF) { inbuf += 2; inleft -= 2; return long(u); }
            if (u > 0xDBFF) { errno = EILSEQ; return -1; }  // trail without lead
            if (inleft < 4) { errno = EINVAL; return -2; }
            uint16_t lo = uint16_t(inbuf[2]) | (uint16_t(inbuf[3]) << 8);
            if (lo < 0xDC00 || lo > 0xDFFF) { errno = EILSEQ; return -1; }
            uint32_t cp = 0x10000 + ((uint32_t(u - 0xD800) << 10) | (lo - 0xDC00));
            inbuf += 4; inleft -= 4;
            return long(cp);
        }
        case CS_UTF16BE: {
            if (inleft < 2) { errno = EINVAL; return -2; }
            uint16_t u = (uint16_t(inbuf[0]) << 8) | uint16_t(inbuf[1]);
            if (u < 0xD800 || u > 0xDFFF) { inbuf += 2; inleft -= 2; return long(u); }
            if (u > 0xDBFF) { errno = EILSEQ; return -1; }
            if (inleft < 4) { errno = EINVAL; return -2; }
            uint16_t lo = (uint16_t(inbuf[2]) << 8) | uint16_t(inbuf[3]);
            if (lo < 0xDC00 || lo > 0xDFFF) { errno = EILSEQ; return -1; }
            uint32_t cp = 0x10000 + ((uint32_t(u - 0xD800) << 10) | (lo - 0xDC00));
            inbuf += 4; inleft -= 4;
            return long(cp);
        }
        case CS_UTF32LE:
        case CS_WCHAR_T:
        case CS_UCS4LE: {
            if (inleft < 4) { errno = EINVAL; return -2; }
            uint32_t cp = uint32_t(inbuf[0]) | (uint32_t(inbuf[1]) << 8) |
                          (uint32_t(inbuf[2]) << 16) | (uint32_t(inbuf[3]) << 24);
            if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) { errno = EILSEQ; return -1; }
            inbuf += 4; inleft -= 4;
            return long(cp);
        }
        case CS_UTF32BE:
        case CS_UCS4BE: {
            if (inleft < 4) { errno = EINVAL; return -2; }
            uint32_t cp = (uint32_t(inbuf[0]) << 24) | (uint32_t(inbuf[1]) << 16) |
                          (uint32_t(inbuf[2]) << 8) | uint32_t(inbuf[3]);
            if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) { errno = EILSEQ; return -1; }
            inbuf += 4; inleft -= 4;
            return long(cp);
        }
        case CS_ASCII: {
            if (inleft == 0) { errno = EINVAL; return -2; }
            uint8_t b = inbuf[0];
            if (b > 0x7F) { errno = EILSEQ; return -1; }
            inbuf += 1; inleft -= 1;
            return long(b);
        }
        case CS_ISO88591: {
            if (inleft == 0) { errno = EINVAL; return -2; }
            uint32_t cp = inbuf[0];  // 0x00..0xFF maps directly
            inbuf += 1; inleft -= 1;
            return long(cp);
        }
        default:
            errno = EINVAL;
            return -1;
    }
}

// Encode one code point into *outbuf/*outleft. Advances outbuf/outleft.
// Returns 0 on success, -1 on no output room (ENOMEM) or invalid cp (EILSEQ).
int encode_one(CsId cs, uint32_t cp, uint8_t *&outbuf, size_t &outleft)
{
    switch (cs) {
        case CS_UTF8: {
            size_t need;
            if (cp <= 0x7F) need = 1;
            else if (cp <= 0x7FF) need = 2;
            else if (cp <= 0xFFFF) need = 3;
            else need = 4;
            if (outleft < need) { errno = ENOMEM; return -1; }
            if (need == 1) {
                outbuf[0] = uint8_t(cp);
            } else if (need == 2) {
                outbuf[0] = uint8_t(0xC0 | (cp >> 6));
                outbuf[1] = uint8_t(0x80 | (cp & 0x3F));
            } else if (need == 3) {
                outbuf[0] = uint8_t(0xE0 | (cp >> 12));
                outbuf[1] = uint8_t(0x80 | ((cp >> 6) & 0x3F));
                outbuf[2] = uint8_t(0x80 | (cp & 0x3F));
            } else {
                outbuf[0] = uint8_t(0xF0 | (cp >> 18));
                outbuf[1] = uint8_t(0x80 | ((cp >> 12) & 0x3F));
                outbuf[2] = uint8_t(0x80 | ((cp >> 6) & 0x3F));
                outbuf[3] = uint8_t(0x80 | (cp & 0x3F));
            }
            outbuf += need; outleft -= need;
            return 0;
        }
        case CS_UTF16LE: {
            if (cp <= 0xFFFF) {
                if (outleft < 2) { errno = ENOMEM; return -1; }
                outbuf[0] = uint8_t(cp & 0xFF);
                outbuf[1] = uint8_t((cp >> 8) & 0xFF);
                outbuf += 2; outleft -= 2;
            } else {
                if (outleft < 4) { errno = ENOMEM; return -1; }
                uint32_t v = cp - 0x10000;
                uint16_t hi = uint16_t(0xD800 + (v >> 10));
                uint16_t lo = uint16_t(0xDC00 + (v & 0x3FF));
                outbuf[0] = uint8_t(hi & 0xFF);
                outbuf[1] = uint8_t((hi >> 8) & 0xFF);
                outbuf[2] = uint8_t(lo & 0xFF);
                outbuf[3] = uint8_t((lo >> 8) & 0xFF);
                outbuf += 4; outleft -= 4;
            }
            return 0;
        }
        case CS_UTF16BE: {
            if (cp <= 0xFFFF) {
                if (outleft < 2) { errno = ENOMEM; return -1; }
                outbuf[0] = uint8_t((cp >> 8) & 0xFF);
                outbuf[1] = uint8_t(cp & 0xFF);
                outbuf += 2; outleft -= 2;
            } else {
                if (outleft < 4) { errno = ENOMEM; return -1; }
                uint32_t v = cp - 0x10000;
                uint16_t hi = uint16_t(0xD800 + (v >> 10));
                uint16_t lo = uint16_t(0xDC00 + (v & 0x3FF));
                outbuf[0] = uint8_t((hi >> 8) & 0xFF);
                outbuf[1] = uint8_t(hi & 0xFF);
                outbuf[2] = uint8_t((lo >> 8) & 0xFF);
                outbuf[3] = uint8_t(lo & 0xFF);
                outbuf += 4; outleft -= 4;
            }
            return 0;
        }
        case CS_UTF32LE:
        case CS_WCHAR_T:
        case CS_UCS4LE: {
            if (outleft < 4) { errno = ENOMEM; return -1; }
            outbuf[0] = uint8_t(cp & 0xFF);
            outbuf[1] = uint8_t((cp >> 8) & 0xFF);
            outbuf[2] = uint8_t((cp >> 16) & 0xFF);
            outbuf[3] = uint8_t((cp >> 24) & 0xFF);
            outbuf += 4; outleft -= 4;
            return 0;
        }
        case CS_UTF32BE:
        case CS_UCS4BE: {
            if (outleft < 4) { errno = ENOMEM; return -1; }
            outbuf[0] = uint8_t((cp >> 24) & 0xFF);
            outbuf[1] = uint8_t((cp >> 16) & 0xFF);
            outbuf[2] = uint8_t((cp >> 8) & 0xFF);
            outbuf[3] = uint8_t(cp & 0xFF);
            outbuf += 4; outleft -= 4;
            return 0;
        }
        case CS_ASCII: {
            if (cp > 0x7F) { errno = EILSEQ; return -1; }
            if (outleft < 1) { errno = ENOMEM; return -1; }
            outbuf[0] = uint8_t(cp);
            outbuf += 1; outleft -= 1;
            return 0;
        }
        case CS_ISO88591: {
            if (cp > 0xFF) { errno = EILSEQ; return -1; }
            if (outleft < 1) { errno = ENOMEM; return -1; }
            outbuf[0] = uint8_t(cp);
            outbuf += 1; outleft -= 1;
            return 0;
        }
        default:
            errno = EINVAL;
            return -1;
    }
}

} // namespace

extern "C"
{
    iconv_t iconv_open(const char *tocode, const char *fromcode)
    {
        CsId to = cs_id(tocode);
        CsId from = cs_id(fromcode);
        if (to == CS_UNSUPPORTED || from == CS_UNSUPPORTED) {
            errno = EINVAL;
            return iconv_t(-1);
        }
        // Pack both ids into the opaque handle. (from=to=CS_UNSUPPORTED is
        // already filtered above, so the handle is never 0; iconv_t(-1) is
        // reserved for "unsupported" and iconv_t(0) is guarded against in iconv().)
        uintptr_t h = (uintptr_t(from) << 8) | uintptr_t(to);
        return iconv_t(h);
    }

    size_t iconv(iconv_t cd,
                 char **inbuf, size_t *inbytesleft,
                 char **outbuf, size_t *outbytesleft)
    {
        if (cd == iconv_t(-1) || cd == iconv_t(0)) {
            errno = EBADF;
            return size_t(-1);
        }
        uintptr_t h = uintptr_t(cd);
        CsId from = CsId((h >> 8) & 0xff);
        CsId to   = CsId(h & 0xff);

        // Flush request: inbuf==NULL or *inbuf==NULL.
        if (inbuf == nullptr || *inbuf == nullptr) {
            return size_t(0);
        }
        if (inbytesleft == nullptr || outbuf == nullptr || *outbuf == nullptr ||
            outbytesleft == nullptr) {
            errno = EINVAL;
            return size_t(-1);
        }

        const uint8_t *in = reinterpret_cast<const uint8_t *>(*inbuf);
        size_t inleft = *inbytesleft;
        uint8_t *out = reinterpret_cast<uint8_t *>(*outbuf);
        size_t outleft = *outbytesleft;
        size_t irreversible = 0;

        while (inleft > 0) {
            const uint8_t *in_before = in;
            size_t inleft_before = inleft;
            long r = decode_next(from, in, inleft);
            if (r == -2) {
                // Incomplete multibyte sequence: rewind, stop, await more input.
                in = in_before; inleft = inleft_before;
                errno = EINVAL;
                break;
            }
            if (r < 0) {
                // EILSEQ: invalid sequence. Skip one byte, count as irreversible.
                in = in_before + 1; inleft = inleft_before - 1;
                irreversible++;
                continue;
            }
            if (encode_one(to, uint32_t(r), out, outleft) != 0) {
                // ENOMEM: no output room. Rewind consumed input, let caller retry.
                in = in_before; inleft = inleft_before;
                errno = ENOMEM;
                break;
            }
        }

        *inbuf = reinterpret_cast<char *>(const_cast<uint8_t *>(in));
        *inbytesleft = inleft;
        *outbuf = reinterpret_cast<char *>(out);
        *outbytesleft = outleft;
        return irreversible;
    }

    int iconv_close(iconv_t /*cd*/)
    {
        return 0;
    }
}

#endif /* __ANDROID__ */
