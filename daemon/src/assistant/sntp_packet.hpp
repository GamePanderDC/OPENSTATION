#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace openstation {

struct SntpTime {
    int64_t seconds = 0;
    long nanoseconds = 0;
};

inline uint32_t sntp_u32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
}

inline bool parse_sntp_reply(const uint8_t* reply, size_t size, const uint8_t* request_timestamp,
                             int64_t earliest, int64_t latest, SntpTime& out) {
    if (size < 48 || (reply[0] >> 6) == 3 || (reply[0] & 7) != 4 ||
        reply[1] == 0 || reply[1] > 15 || std::memcmp(reply + 24, request_timestamp, 8) != 0) return false;
    const uint32_t seconds = sntp_u32(reply + 40);
    const uint32_t fraction = sntp_u32(reply + 44);
    if (seconds == 0 && fraction == 0) return false;
    constexpr uint64_t offset = 2208988800ULL;
    const uint64_t ntp = seconds < offset ? uint64_t(seconds) + (1ULL << 32) : seconds;
    const int64_t unix_seconds = static_cast<int64_t>(ntp - offset);
    if (unix_seconds < earliest || unix_seconds >= latest) return false;
    out.seconds = unix_seconds;
    out.nanoseconds = static_cast<long>((uint64_t(fraction) * 1000000000ULL) >> 32);
    return true;
}

} // namespace openstation
