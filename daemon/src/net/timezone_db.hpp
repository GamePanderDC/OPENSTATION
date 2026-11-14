#pragma once

#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>

namespace openstation {

inline uint32_t timezone_be32(const unsigned char* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
}

// Android's tzdata index consists of 52-byte entries beginning with a 40-byte name.
inline bool timezone_in_db(const std::string& zone, const std::string& path) {
    if (zone.empty() || zone.size() >= 40) return false;
    for (char c : zone) {
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
              (c >= '0' && c <= '9') || c == '/' || c == '_' || c == '-' || c == '+')) return false;
    }
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) return false;
    const auto file_size = in.tellg();
    if (file_size < 24 || file_size > 16 * 1024 * 1024) return false;
    in.seekg(0);
    unsigned char header[24]{};
    if (!in.read(reinterpret_cast<char*>(header), sizeof(header)) || std::memcmp(header, "tzdata", 6) != 0) return false;
    const uint32_t index = timezone_be32(header + 12);
    const uint32_t data = timezone_be32(header + 16);
    if (index < 24 || data < index || data > static_cast<uint64_t>(file_size) || (data - index) % 52 != 0) return false;
    in.seekg(index);
    for (uint32_t pos = index; pos < data; pos += 52) {
        char entry[52]{};
        if (!in.read(entry, sizeof(entry))) return false;
        size_t len = 0;
        while (len < 40 && entry[len] != '\0') ++len;
        if (len == zone.size() && std::memcmp(entry, zone.data(), len) == 0) return true;
    }
    return false;
}

inline bool timezone_available(const std::string& zone) {
    // Android uses the downloaded database if it exists, otherwise the system copy.
    const char* update = "/data/misc/zoneinfo/current/tzdata";
    std::ifstream updated(update, std::ios::binary);
    return updated ? timezone_in_db(zone, update) : timezone_in_db(zone, "/system/usr/share/zoneinfo/tzdata");
}

} // namespace openstation
