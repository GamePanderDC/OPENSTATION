#pragma once
#include <algorithm>
#include <chrono>

namespace openstation {
// Noise can keep the local energy detector active indefinitely. Bound the
// unconfirmed interval, while a recognized word starts a fresh interval.
inline bool listening_deadline_expired(std::chrono::steady_clock::duration idle,
                                       std::chrono::steady_clock::duration unconfirmed,
                                       int follow_up_sec, bool had_interaction) {
    const int wait_sec = had_interaction ? std::max(1, follow_up_sec) : std::max(8, follow_up_sec);
    // Once a dialogue has started, noise alone must not extend a 4 s pause to
    // 12–20 s. Actual transcription resets this deadline as speech arrives.
    const int unconfirmed_sec = had_interaction ? std::max(6, wait_sec + 2)
                                                : std::max(12, wait_sec * 2);
    return idle >= std::chrono::seconds(wait_sec) ||
           unconfirmed >= std::chrono::seconds(unconfirmed_sec);
}
}
