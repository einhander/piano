// LAME API smoke test. LAME's copyright and license notices are preserved in
// ../third_party/lame/{COPYING,LICENSE,README} and source files.
#include "lame.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

int main(int argc, char** argv) {
    const char* output_path = argc > 1 ? argv[1] : "lame-smoke.mp3";
    constexpr int sample_rate = 44100;
    constexpr int frames = sample_rate * 2;

    std::vector<short> pcm(static_cast<size_t>(frames) * 2);
    for (int frame = 0; frame < frames; ++frame) {
        const double t = static_cast<double>(frame) / sample_rate;
        pcm[static_cast<size_t>(frame) * 2] = static_cast<short>(
            12000.0 * std::sin(2.0 * 3.141592653589793 * 440.0 * t));
        pcm[static_cast<size_t>(frame) * 2 + 1] = static_cast<short>(
            9000.0 * std::sin(2.0 * 3.141592653589793 * 660.0 * t));
    }

    FILE* output = std::fopen(output_path, "wb");
    if (!output) return 2;
    lame_t encoder = lame_init();
    if (!encoder) { std::fclose(output); return 3; }
    lame_set_in_samplerate(encoder, sample_rate);
    lame_set_num_channels(encoder, 2);
    lame_set_quality(encoder, 5);
    if (lame_init_params(encoder) < 0) {
        lame_close(encoder); std::fclose(output); return 4;
    }

    std::vector<unsigned char> encoded(static_cast<size_t>(1.25 * frames) + 7200);
    int bytes = lame_encode_buffer_interleaved(
        encoder, pcm.data(), frames, encoded.data(), static_cast<int>(encoded.size()));
    if (bytes < 0 || std::fwrite(encoded.data(), 1, static_cast<size_t>(bytes), output) != static_cast<size_t>(bytes)) {
        lame_close(encoder); std::fclose(output); return 5;
    }
    bytes = lame_encode_flush(encoder, encoded.data(), static_cast<int>(encoded.size()));
    if (bytes < 0 || std::fwrite(encoded.data(), 1, static_cast<size_t>(bytes), output) != static_cast<size_t>(bytes)) {
        lame_close(encoder); std::fclose(output); return 6;
    }
    lame_close(encoder);
    if (std::fclose(output) != 0) return 7;

    FILE* check = std::fopen(output_path, "rb");
    if (!check) return 8;
    std::fseek(check, 0, SEEK_END);
    const long size = std::ftell(check);
    std::fclose(check);
    if (size <= 0) return 9;
    std::printf("Encoded %ld bytes to %s\n", size, output_path);
    return 0;
}
