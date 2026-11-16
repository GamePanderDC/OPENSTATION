#pragma once
#include <algorithm>

namespace openstation {
// VQE already suppresses echo and noise. Provide additional headroom only
// when the incoming level rises above its measured ambient floor.
inline float vqe_max_speech_gain(float sensitivity) {
    return 1.0f + 2.5f * std::clamp(sensitivity, 0.1f, 1.0f);
}
inline float vqe_idle_gain_cap(float sensitivity) {
    return std::max(0.85f, (0.9f + 0.9f * std::clamp(sensitivity, 0.1f, 1.0f)) * 0.75f);
}
inline float vqe_speech_trigger(float floor, float sensitivity) {
    return floor * (1.45f - std::clamp(sensitivity, 0.1f, 1.0f) * 0.12f) + 10.0f;
}
inline float dialogue_activity_threshold(float processed_noise) {
    return std::max(95.0f, processed_noise * 1.8f + 10.0f);
}
}
