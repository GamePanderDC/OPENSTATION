#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace openstation {

// Inspired by the PCM features used in qprots/station-max. Analyze only
// audio already queued for the current turn, with no recordings or models.
class QuietSpeech {
public:
    void observe(const int16_t* pcm, size_t count, float noise_rms) {
        constexpr size_t kFrame = 320;
        for (size_t off = 0; off + kFrame <= count && active_ < 100; off += kFrame) {
            const auto* frame = pcm + off;
            double power = 0.0;
            double high = 0.0;
            int crossings = 0;
            for (size_t i = 1; i < kFrame; ++i) {
                const double sample = frame[i];
                const double diff = sample - frame[i - 1];
                power += sample * sample;
                high += diff * diff;
                crossings += (frame[i] < 0) != (frame[i - 1] < 0);
            }
            const float rms = static_cast<float>(std::sqrt(power / (kFrame - 1)));
            if (rms < std::max(100.0f, noise_rms * 1.6f + 20.0f)) continue;

            ++active_;
            min_rms_ = std::min(min_rms_, rms);
            max_rms_ = std::max(max_rms_, rms);
            float periodicity = 0.0f;
            // Sample pitch lags at 8 kHz equivalent, around 75–400 Hz.
            for (size_t lag = 40; lag <= 212; lag += 2) {
                double cross = 0.0, first = 0.0, second = 0.0;
                for (size_t i = 0; i + lag < kFrame; i += 2) {
                    const double x = frame[i], y = frame[i + lag];
                    cross += x * y;
                    first += x * x;
                    second += y * y;
                }
                if (cross > 0.0 && first > 0.0 && second > 0.0) {
                    periodicity = std::max(periodicity, static_cast<float>(cross / std::sqrt(first * second)));
                }
            }
            if (periodicity >= 0.50f) ++voiced_;
            if (periodicity < 0.42f && crossings >= 33 && high > power * 0.28) ++unvoiced_;
        }
    }

    bool detected() const {
        return active_ >= 8 && voiced_ * 4 <= active_ &&
               unvoiced_ * 5 >= active_ * 3 && max_rms_ >= min_rms_ * 1.28f;
    }

private:
    int active_ = 0;
    int voiced_ = 0;
    int unvoiced_ = 0;
    float min_rms_ = 32768.0f;
    float max_rms_ = 0.0f;
};

} // namespace openstation
