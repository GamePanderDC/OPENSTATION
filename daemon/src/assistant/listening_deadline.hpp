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
    const int unconfirmed_sec = std::max(20, wait_sec * 2);
    return idle >= std::chrono::seconds(wait_sec) ||
           unconfirmed >= std::chrono::seconds(unconfirmed_sec);
}
}
