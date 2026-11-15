#include "hw/audio_engine.hpp"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

void append_le16(std::vector<uint8_t>& out, uint16_t v) {
    out.push_back(static_cast<uint8_t>(v & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
}

void append_le32(std::vector<uint8_t>& out, uint32_t v) {
    out.push_back(static_cast<uint8_t>(v & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
}

std::vector<uint8_t> make_pcm16_wav(int sample_rate, int channels,
                                    const std::vector<int16_t>& samples,
                                    bool add_odd_junk_chunk = false) {
    std::vector<uint8_t> buf;
    buf.insert(buf.end(), {'R', 'I', 'F', 'F'});
    append_le32(buf, 0); // patched below
    buf.insert(buf.end(), {'W', 'A', 'V', 'E'});

    if (add_odd_junk_chunk) {
        buf.insert(buf.end(), {'J', 'U', 'N', 'K'});
        append_le32(buf, 3); // odd length -> requires 1 pad byte in RIFF!
        buf.push_back('a');
        buf.push_back('b');
        buf.push_back('c');
        buf.push_back(0); // word-align padding
    }

    // fmt chunk
    buf.insert(buf.end(), {'f', 'm', 't', ' '});
    append_le32(buf, 16);
    append_le16(buf, 1); // PCM
    append_le16(buf, static_cast<uint16_t>(channels));
    append_le32(buf, static_cast<uint32_t>(sample_rate));
    append_le32(buf, static_cast<uint32_t>(sample_rate * channels * 2));
    append_le16(buf, static_cast<uint16_t>(channels * 2));
    append_le16(buf, 16);

    // data chunk
    buf.insert(buf.end(), {'d', 'a', 't', 'a'});
    const uint32_t data_bytes = static_cast<uint32_t>(samples.size() * 2);
    append_le32(buf, data_bytes);
    for (int16_t s : samples) {
        append_le16(buf, static_cast<uint16_t>(s));
    }

    const uint32_t riff_sz = static_cast<uint32_t>(buf.size() - 8);
    buf[4] = static_cast<uint8_t>(riff_sz & 0xFF);
    buf[5] = static_cast<uint8_t>((riff_sz >> 8) & 0xFF);
    buf[6] = static_cast<uint8_t>((riff_sz >> 16) & 0xFF);
    buf[7] = static_cast<uint8_t>((riff_sz >> 24) & 0xFF);
    return buf;
}

} // namespace

int main() {
    using openstation::AudioEngine;

    // 1. Canonical stereo 44.1kHz WAV with odd-sized JUNK chunk before fmt/data
    {
        std::vector<int16_t> samples = {1234, -5678, 32767, -32768};
        auto wav = make_pcm16_wav(44100, 2, samples, true);
        AudioEngine::CachedSound out;
        assert(AudioEngine::parse_wav_pcm16(wav.data(), wav.size(), out));
        assert(out.sample_rate == 44100);
        assert(out.channels == 2);
        assert(out.samples == samples);
    }

    // 2. Mono 24kHz WAV (matches alarm.wav / timer.wav format)
    {
        std::vector<int16_t> samples = {0, 1024, -2048, 4096};
        auto wav = make_pcm16_wav(24000, 1, samples, false);
        AudioEngine::CachedSound out;
        assert(AudioEngine::parse_wav_pcm16(wav.data(), wav.size(), out));
        assert(out.sample_rate == 24000);
        assert(out.channels == 1);
        assert(out.samples == samples);
    }

    // 3. Rejects null, non-RIFF, and empty data chunks safely
    {
        AudioEngine::CachedSound out;
        assert(!AudioEngine::parse_wav_pcm16(nullptr, 64, out));
        const uint8_t junk[64] = {0};
        assert(!AudioEngine::parse_wav_pcm16(junk, sizeof(junk), out));
        auto empty_data = make_pcm16_wav(48000, 2, {}, false);
        assert(!AudioEngine::parse_wav_pcm16(empty_data.data(), empty_data.size(), out));
    }

    std::printf("wav_sound_pack_test: all checks passed\n");
    return 0;
}
