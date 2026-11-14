#include "assistant/sntp_packet.hpp"
#include <cassert>

static void put32(uint8_t* p, uint32_t n) {
    p[0] = n >> 24; p[1] = n >> 16; p[2] = n >> 8; p[3] = n;
}

int main() {
    const uint8_t request_stamp[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    uint8_t reply[48]{};
    reply[0] = 0x24;
    reply[1] = 2;
    std::memcpy(reply + 24, request_stamp, 8);
    openstation::SntpTime t;
    constexpr int64_t earliest = 1735689600LL;
    constexpr int64_t latest = 2524608000LL;
    put32(reply + 40, static_cast<uint32_t>(1767225600ULL + 2208988800ULL));
    put32(reply + 44, 0x80000000U);
    assert(openstation::parse_sntp_reply(reply, sizeof(reply), request_stamp, earliest, latest, t));
    assert(t.seconds == 1767225600 && t.nanoseconds == 500000000);
    put32(reply + 40, static_cast<uint32_t>(2208988800ULL + 2208988800ULL)); // 2040, after NTP rollover
    assert(openstation::parse_sntp_reply(reply, sizeof(reply), request_stamp, earliest, latest, t));
    assert(t.seconds == 2208988800LL);
    reply[0] = 0xe4;
    assert(!openstation::parse_sntp_reply(reply, sizeof(reply), request_stamp, earliest, latest, t));
    reply[0] = 0x24; reply[1] = 0;
    assert(!openstation::parse_sntp_reply(reply, sizeof(reply), request_stamp, earliest, latest, t));
    reply[1] = 2; reply[24] ^= 1;
    assert(!openstation::parse_sntp_reply(reply, sizeof(reply), request_stamp, earliest, latest, t));
    assert(!openstation::parse_sntp_reply(reply, 47, request_stamp, earliest, latest, t));
}
