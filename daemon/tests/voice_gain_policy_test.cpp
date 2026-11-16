#include "hw/voice_gain_policy.hpp"
#include <cassert>

int main() {
    using namespace openstation;
    // Representative levels measured on a Max: ambient ~55, soft voice ~150.
    const float trigger = vqe_speech_trigger(55.0f, 0.8f);
    assert(64.0f < trigger);
    assert(150.0f > trigger);
    assert(vqe_max_speech_gain(0.8f) > 1.62f);
    assert(vqe_idle_gain_cap(0.8f) < vqe_max_speech_gain(0.8f));
    assert(vqe_idle_gain_cap(0.1f) >= 0.85f);
    // The follow-up timer ignores ordinary amplified room noise but sees speech.
    const float deadline_threshold = dialogue_activity_threshold(80.0f);
    assert(100.0f < deadline_threshold);
    assert(240.0f > deadline_threshold);
    assert(dialogue_activity_threshold(0.0f) >= 95.0f);
}
