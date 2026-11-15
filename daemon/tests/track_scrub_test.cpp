#include "../src/hw/track_scrub.hpp"

#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>

using namespace openstation;
using clock_t_ = std::chrono::steady_clock;

namespace {

std::vector<uint8_t> make_sine_block_u8(double freq_hz, double& phase_rad, size_t count, int sample_rate) {
    std::vector<uint8_t> out(count);
    const double dphi = 2.0 * 3.14159265358979323846 * freq_hz / static_cast<double>(sample_rate);
    for (size_t i = 0; i < count; ++i) {
        double s = std::sin(phase_rad) * 0.92;
        int v = static_cast<int>(std::lround(128.0 + s * 127.0));
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        out[i] = static_cast<uint8_t>(v);
        phase_rad += dphi;
    }
    return out;
}

double measure_dominant_freq_hz(const std::vector<int16_t>& stereo_pcm, int sample_rate) {
    const size_t n = stereo_pcm.size() / 2;
    if (n < 64) return 0.0;
    // Count positive-going zero crossings on the middle 75% to avoid boundary fades.
    const size_t i0 = n / 8;
    const size_t i1 = (n * 7) / 8;
    int crossings = 0;
    size_t first_idx = 0;
    size_t last_idx = 0;
    for (size_t i = i0 + 1; i < i1; ++i) {
        if (stereo_pcm[(i - 1) * 2] <= 0 && stereo_pcm[i * 2] > 0) {
            if (crossings == 0) first_idx = i;
            last_idx = i;
            ++crossings;
        }
    }
    if (crossings < 2 || last_idx <= first_idx) return 0.0;
    double avg_period_samples = static_cast<double>(last_idx - first_idx) / static_cast<double>(crossings - 1);
    return static_cast<double>(sample_rate) / avg_period_samples;
}

} // namespace

int main() {
    TrackScrubEngine scrub;
    auto t0 = clock_t_::time_point(std::chrono::seconds(100));

    // 1. Without captured playing audio: NO synthetic sound at all!
    std::vector<int16_t> pcm(256 * 2, 77);
    assert(!scrub.has_live_wave());
    scrub.set_hold_active(true, t0);
    assert(scrub.active());
    scrub.on_rotary_detent(+1, 1, t0);
    assert(!scrub.render_scrub_audio(pcm.data(), 256));
    for (int16_t s : pcm) assert(s == 0);
    auto rel0 = scrub.set_hold_active(false, t0 + std::chrono::milliseconds(50));
    assert(!rel0.active);
    assert(rel0.commit_seek);
    assert(rel0.commit_seek_ms > 0);

    // 2. Continuously ingest 2.0 seconds of real 440 Hz playing audio at 48 kHz
    double phase = 0.0;
    auto t_ing = t0 + std::chrono::seconds(1);
    for (int blk = 0; blk < 94; ++blk) { // 94 * 1024 = 96256 samples (~2.005s)
        auto w = make_sine_block_u8(440.0, phase, 1024, TrackScrubEngine::kSampleRate);
        scrub.ingest_wave_u8_block(w.data(), w.size(), 0.22f, 0.14f, t_ing);
        t_ing += std::chrono::milliseconds(21);
    }
    assert(scrub.has_live_wave());

    // 3. Hold button without turning -> NOT ducking yet, zero seek commit on release
    auto t_hold = t_ing + std::chrono::milliseconds(10);
    auto h1 = scrub.set_hold_active(true, t_hold);
    assert(h1.active);
    assert(!h1.duck_active);
    assert(!scrub.render_scrub_audio(pcm.data(), 256));
    auto h1_rel = scrub.set_hold_active(false, t_hold + std::chrono::milliseconds(150));
    assert(!h1_rel.active);
    assert(!h1_rel.commit_seek);
    assert(!h1_rel.duck_active);

    // 4. Hold and scrub CW (+) -> renders pitched real 440 Hz waveform (> 440 Hz when |omega| > 1)
    auto t_sc = t_hold + std::chrono::milliseconds(400);
    scrub.set_hold_active(true, t_sc);
    scrub.on_rotary_detent(+1, 1, t_sc);
    scrub.on_rotary_detent(+1, 1, t_sc + std::chrono::milliseconds(45));
    scrub.on_rotary_detent(+1, 1, t_sc + std::chrono::milliseconds(90));
    assert(scrub.is_ducking());

    std::vector<int16_t> fwd_pcm;
    for (int blk = 0; blk < 16; ++blk) {
        std::vector<int16_t> tmp(256 * 2, 0);
        assert(scrub.render_scrub_audio(tmp.data(), 256));
        fwd_pcm.insert(fwd_pcm.end(), tmp.begin(), tmp.end());
    }
    double fwd_hz = measure_dominant_freq_hz(fwd_pcm, TrackScrubEngine::kSampleRate);
    assert(fwd_hz > 500.0 && fwd_hz < 1800.0); // Varispeed >1x pitch-shifted 440 Hz real signal!

    // 5. While scrubbing CW -> reverse CCW (-) renders non-zero reversed real waveform & cancels positive seek
    auto t_rev = t_sc + std::chrono::milliseconds(140);
    scrub.on_rotary_detent(-1, 1, t_rev);
    scrub.on_rotary_detent(-1, 1, t_rev + std::chrono::milliseconds(40));
    scrub.on_rotary_detent(-1, 1, t_rev + std::chrono::milliseconds(80));
    scrub.on_rotary_detent(-1, 1, t_rev + std::chrono::milliseconds(120));

    std::vector<int16_t> rev_pcm;
    for (int blk = 0; blk < 16; ++blk) {
        std::vector<int16_t> tmp(256 * 2, 0);
        assert(scrub.render_scrub_audio(tmp.data(), 256));
        rev_pcm.insert(rev_pcm.end(), tmp.begin(), tmp.end());
    }
    double rev_hz = measure_dominant_freq_hz(rev_pcm, TrackScrubEngine::kSampleRate);
    assert(rev_hz > 400.0 && rev_hz < 1800.0);

    // 6. Duck blocks ingestion while active so scratch PCM cannot feed back into ring
    auto vis_before = scrub.visual_snapshot(t_rev + std::chrono::milliseconds(150));
    assert(vis_before.scrubbing_audio);
    std::vector<uint8_t> poison(1024, 250);
    scrub.ingest_wave_u8_block(poison.data(), poison.size(), 0.9f, 0.8f, t_rev + std::chrono::milliseconds(160));

    // 7. Release commits accumulated net negative seek and un-ducks immediately
    auto rel = scrub.set_hold_active(false, t_rev + std::chrono::milliseconds(200));
    assert(!rel.active);
    assert(rel.duck_changed);
    assert(!rel.duck_active);
    assert(rel.commit_seek);
    assert(rel.commit_seek_ms < 0);
    assert(!scrub.render_scrub_audio(pcm.data(), 256));

    // 8. Overlap deduplication & no-pumping continuity when Visualizer shifts block gain 2x
    {
        TrackScrubEngine ov_scrub;
        double ph = 0.0;
        const double dphi = 2.0 * 3.14159265358979323846 * 300.0 / 48000.0;
        std::vector<uint8_t> long_wave(4096);
        for (size_t i = 0; i < long_wave.size(); ++i) {
            // Alternating block half has half uint8 excursion while true RMS remains steady
            double scale = (i >= 1024 && i < 2048) ? 0.45 : 0.90;
            double s = std::sin(ph) * scale;
            long_wave[i] = static_cast<uint8_t>(std::lround(128.0 + s * 127.0));
            ph += dphi;
        }
        auto t_o = t0 + std::chrono::seconds(20);
        // Feed contiguous first block, then overlapped +256 block, etc.
        ov_scrub.ingest_wave_u8_block(&long_wave[0], 1024, 0.25f, 0.16f, t_o);
        ov_scrub.ingest_wave_u8_block(&long_wave[1024 - 128], 1024, 0.25f, 0.16f, t_o + std::chrono::milliseconds(18));
        ov_scrub.ingest_wave_u8_block(&long_wave[2048 - 128], 1024, 0.25f, 0.16f, t_o + std::chrono::milliseconds(36));
        ov_scrub.ingest_wave_u8_block(&long_wave[3072 - 128], 1024, 0.25f, 0.16f, t_o + std::chrono::milliseconds(54));
        assert(ov_scrub.has_live_wave());
        ov_scrub.set_hold_active(true, t_o + std::chrono::milliseconds(70));
        ov_scrub.on_rotary_detent(-1, 1, t_o + std::chrono::milliseconds(75));
        std::vector<int16_t> ov_pcm(256 * 2, 0);
        assert(ov_scrub.render_scrub_audio(ov_pcm.data(), 256));
    }

    std::printf("[PASS] track_scrub_test (fwd_hz=%.1f, rev_hz=%.1f, commit=%dms)\n",
                fwd_hz, rev_hz, rel.commit_seek_ms);
    return 0;
}
