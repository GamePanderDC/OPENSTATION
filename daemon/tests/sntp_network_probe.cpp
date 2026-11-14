#include "assistant/sntp_packet.hpp"
#include <arpa/inet.h>
#include <ctime>
#include <netdb.h>
#include <poll.h>
#include <cstdio>
#include <sys/socket.h>
#include <unistd.h>

int main() {
    addrinfo hints{}; hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_DGRAM;
    addrinfo* addresses = nullptr;
    if (getaddrinfo("time.google.com", "123", &hints, &addresses) != 0) return 1;
    bool ok = false;
    for (auto* a = addresses; a && !ok; a = a->ai_next) {
        int fd = socket(a->ai_family, a->ai_socktype, a->ai_protocol);
        if (fd < 0) continue;
        uint8_t req[48]{}; req[0] = 0x23;
        timespec now{}; clock_gettime(CLOCK_REALTIME, &now);
        uint64_t stamp = (uint64_t(now.tv_sec + 2208988800ULL) << 32) |
                         ((uint64_t(now.tv_nsec) << 32) / 1000000000ULL);
        for (int i = 0; i < 8; ++i) req[40 + i] = uint8_t(stamp >> (56 - 8 * i));
        if (connect(fd, a->ai_addr, a->ai_addrlen) == 0 && send(fd, req, sizeof(req), 0) == sizeof(req)) {
            pollfd p{fd, POLLIN, 0};
            if (poll(&p, 1, 2000) > 0 && (p.revents & POLLIN)) {
                uint8_t resp[128]; const ssize_t n = recv(fd, resp, sizeof(resp), 0);
                openstation::SntpTime parsed;
                ok = n >= 0 && openstation::parse_sntp_reply(resp, size_t(n), req + 40,
                                                             1735689600LL, 2524608000LL, parsed);
            }
        }
        close(fd);
    }
    freeaddrinfo(addresses);
    std::puts(ok ? "SNTP response validated" : "SNTP unavailable or invalid response");
    return ok ? 0 : 1;
}
