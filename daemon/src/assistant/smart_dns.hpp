#pragma once
#include <arpa/inet.h>
#include <cstdint>
#include <string>
#include <vector>

namespace openstation {
// Only accept A records from a successful reply to our exact question.
inline std::vector<std::string> parse_smart_dns_reply(const unsigned char* p, size_t n,
                                                      uint16_t id, const std::vector<unsigned char>& question) {
    std::vector<std::string> out;
    if (n < 12 || p[0] != (id >> 8) || p[1] != (id & 255) ||
        (p[2] & 0x80) == 0 || (p[2] & 0x78) != 0 || (p[2] & 0x02) != 0 ||
        (p[3] & 0x0f) != 0 || p[4] != 0 || p[5] != 1 || n < 12 + question.size()) return out;
    for (size_t i = 0; i < question.size(); ++i) if (p[12 + i] != question[i]) return out;
    size_t off = 12 + question.size();
    const unsigned count = (static_cast<unsigned>(p[6]) << 8) | p[7];
    for (unsigned i = 0; i < count; ++i) {
        if (off >= n) return {};
        if ((p[off] & 0xc0) == 0xc0) { if (off + 2 > n) return {}; off += 2; }
        else {
            while (off < n && p[off]) {
                if ((p[off] & 0xc0) || p[off] > 63 || off + 1 + p[off] > n) return {};
                off += 1 + p[off];
            }
            if (off >= n) return {};
            ++off;
        }
        if (off + 10 > n) return {};
        const unsigned type = (p[off] << 8) | p[off + 1];
        const unsigned cls = (p[off + 2] << 8) | p[off + 3];
        const unsigned len = (p[off + 8] << 8) | p[off + 9];
        off += 10;
        if (off + len > n) return {};
        if (type == 1 && cls == 1 && len == 4 && p[off] != 0 && p[off] != 10 && p[off] != 127 &&
            p[off] < 224 && !(p[off] == 169 && p[off+1] == 254) &&
            !(p[off] == 172 && p[off+1] >= 16 && p[off+1] <= 31) &&
            !(p[off] == 192 && p[off+1] == 168)) {
            char addr[INET_ADDRSTRLEN]{};
            if (inet_ntop(AF_INET, p + off, addr, sizeof(addr))) out.emplace_back(addr);
        }
        off += len;
    }
    return out;
}
}
