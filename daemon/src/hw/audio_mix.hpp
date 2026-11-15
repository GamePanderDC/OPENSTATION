#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace openstation {

// Blend one 16 kHz mono station frame with a simultaneously arriving BLE remote mic frame
// on the station's single 16 kHz clock so both mics work in tandem without 2x sample rate distortion.
inline void mix_remote_and_station_pcm(int16_t* station_frame, const int16_t* remote_frame, size_t count) {
    if (!station_frame || !remote_frame || count == 0) return;
    double st_sq = 0.0;
    double rm_sq = 0.0;
    for (size_t i = 0; i < count; ++i) {
        const double s = static_cast<double>(station_frame[i]);
        const double r = static_cast<double>(remote_frame[i]);
        st_sq += s * s;
        rm_sq += r * r;
    }
    const float st_rms = static_cast<float>(std::sqrt(st_sq / static_cast<double>(count)));
    const float rm_rms = static_cast<float>(std::sqrt(rm_sq / static_cast<double>(count)));
    const float r_dom = rm_rms / (rm_rms + st_rms + 200.0f);
    const float w_remote = std::clamp(0.60f + 0.35f * r_dom, 0.55f, 0.95f);
    const float w_station = std::clamp(0.95f - 0.50f * r_dom, 0.45f, 0.95f);
    for (size_t i = 0; i < count; ++i) {
        float mixed = w_station * static_cast<float>(station_frame[i]) +
                      w_remote * static_cast<float>(remote_frame[i]);
        if (mixed > 32767.0f) mixed = 32767.0f;
        else if (mixed < -32768.0f) mixed = -32768.0f;
        station_frame[i] = static_cast<int16_t>(std::lrintf(mixed));
    }
}

} // namespace openstation
