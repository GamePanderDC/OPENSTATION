#include "hw/aec_filter.hpp"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <vector>

namespace {

float lcg_noise(uint32_t& state) {
    state = state * 1664525U + 1013904223U;
    return (static_cast<float>((state >> 8) & 0xFFFFU) / 32768.0f) - 1.0f;
}

double energy_sum(const std::vector<float>& v, size_t start, size_t end) {
    double s = 0.0;
    for (size_t i = start; i < end; ++i) {
        s += static_cast<double>(v[i]) * v[i];
    }
    return s;
}

double correlation(const std::vector<float>& a, const std::vector<float>& b, size_t start, size_t end) {
    double sa = 0.0, sb = 0.0, sab = 0.0;
    for (size_t i = start; i < end; ++i) {
        sa += static_cast<double>(a[i]) * a[i];
        sb += static_cast<double>(b[i]) * b[i];
        sab += static_cast<double>(a[i]) * b[i];
    }
    return sab / std::sqrt(std::max(1e-9, sa * sb));
}

} // namespace

int main() {
    using namespace openstation;

    constexpr size_t kRate = 16000;
    constexpr size_t kTotal = kRate * 4; // 4 seconds
    uint32_t rng = 0x12345678U;

    std::vector<float> ref(kTotal, 0.0f);
    std::vector<float> echo(kTotal, 0.0f);
    std::vector<float> near(kTotal, 0.0f);
    std::vector<float> mic(kTotal, 0.0f);

    // Rich broadband far-end music/speech excitation.
    for (size_t n = 0; n < kTotal; ++n) {
        const float t = static_cast<float>(n) / static_cast<float>(kRate);
        ref[n] = 2800.0f * (0.55f * lcg_noise(rng) +
                            0.25f * std::sin(2.0f * 3.1415926f * 340.0f * t) +
                            0.20f * std::sin(2.0f * 3.1415926f * 1180.0f * t));
    }

    // Room echo modeling Yandex Station Max hardware lag (~44 samples = 2.75 ms) + reflections.
    for (size_t n = 0; n < kTotal; ++n) {
        float e = 0.0f;
        if (n >= 44)  e += 0.45f * ref[n - 44];
        if (n >= 67)  e += 0.18f * ref[n - 67];
        if (n >= 110) e -= 0.09f * ref[n - 110];
        echo[n] = e;
    }

    // Double-talk near-end user speech segment in [2.5s .. 3.4s].
    for (size_t n = kRate * 5 / 2; n < kRate * 34 / 10; ++n) {
        const float t = static_cast<float>(n) / static_cast<float>(kRate);
        near[n] = 3200.0f * (0.65f * std::sin(2.0f * 3.1415926f * 210.0f * t) +
                             0.35f * std::sin(2.0f * 3.1415926f * 640.0f * t));
    }

    for (size_t n = 0; n < kTotal; ++n) {
        mic[n] = echo[n] + near[n];
    }

    Hpf95State hp_mic;
    Hpf95State hp_ref;
    Hpf95State hp_echo;
    Hpf95State hp_near;
    std::vector<float> mic_hp(kTotal);
    std::vector<float> ref_hp(kTotal);
    std::vector<float> echo_hp(kTotal);
    std::vector<float> near_hp(kTotal);
    for (size_t n = 0; n < kTotal; ++n) {
        mic_hp[n] = hp_mic.process(mic[n]);
        ref_hp[n] = hp_ref.process(ref[n]);
        echo_hp[n] = hp_echo.process(echo[n]);
        near_hp[n] = hp_near.process(near[n]);
    }

    Aec16kFilter aec;
    constexpr size_t kBlock = 320; // 20 ms chunks
    for (size_t pos = 0; pos < kTotal; pos += kBlock) {
        const size_t n = std::min(kBlock, kTotal - pos);
        aec.process_block(mic_hp.data() + pos, ref_hp.data() + pos, n);
    }

    // Check convergence during pure playback [1.8s .. 2.5s]
    const size_t conv_s = kRate * 18 / 10;
    const size_t conv_e = kRate * 25 / 10;
    const double e_before = energy_sum(echo_hp, conv_s, conv_e);
    const double e_after = energy_sum(mic_hp, conv_s, conv_e);
    const double erle_db = 10.0 * std::log10(e_before / std::max(1e-6, e_after));

    // Check near-end speech preservation during double-talk [2.6s .. 3.4s]
    const size_t dt_s = kRate * 26 / 10;
    const size_t dt_e = kRate * 34 / 10;
    const double corr = correlation(mic_hp, near_hp, dt_s, dt_e);

    // Check post-double-talk weights didn't diverge [3.6s .. 4.0s]
    const size_t tail_s = kRate * 36 / 10;
    const size_t tail_e = kTotal;
    const double tail_before = energy_sum(echo_hp, tail_s, tail_e);
    const double tail_after = energy_sum(mic_hp, tail_s, tail_e);
    const double tail_erle_db = 10.0 * std::log10(tail_before / std::max(1e-6, tail_after));

    std::cout << "ERLE=" << erle_db << " dB, DT_corr=" << corr << ", Tail_ERLE=" << tail_erle_db << " dB" << std::endl;

    assert(erle_db >= 14.0);
    assert(corr >= 0.90);
    assert(tail_erle_db >= 12.0);
    return 0;
}
