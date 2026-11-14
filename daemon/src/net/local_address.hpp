#pragma once

#include <cstdint>
#include <string>

namespace openstation {

// Keep the first active physical network, falling back to another active interface.
struct LocalAddressChoice {
    std::string ip;
    bool physical = false;

    void consider(const std::string& candidate, uint32_t host_address,
                  bool up, bool running, bool loopback, bool is_physical) {
        const uint8_t first = static_cast<uint8_t>(host_address >> 24);
        const uint8_t second = static_cast<uint8_t>(host_address >> 16);
        if (!up || !running || loopback || first == 0 || first == 127 || first >= 224 ||
            (first == 169 && second == 254)) return;
        if (ip.empty() || (is_physical && !physical)) {
            ip = candidate;
            physical = is_physical;
        }
    }
};

} // namespace openstation
