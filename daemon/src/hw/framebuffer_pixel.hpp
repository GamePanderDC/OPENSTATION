#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace openstation {
// Pixel storage width is determined by bpp; grayscale does not change its stride.
inline void write_framebuffer_pixel(std::vector<uint8_t>& buf, uint32_t width, uint32_t height,
                                    uint32_t stride, uint32_t bpp, uint32_t red_off,
                                    uint32_t green_off, uint32_t blue_off,
                                    uint32_t x, uint32_t y, uint8_t r, uint8_t g, uint8_t b) {
    if (x >= width || y >= height) return;
    const size_t row = static_cast<size_t>(y) * stride;
    if (bpp == 8) {
        const size_t off = row + x;
        if (off < buf.size()) buf[off] = static_cast<uint8_t>((77 * r + 150 * g + 29 * b) >> 8);
        return;
    }
    size_t bytes = bpp / 8;
    if (bytes < 3) bytes = 3;
    const size_t off = row + static_cast<size_t>(x) * bytes;
    if (off + bytes > buf.size()) return;
    uint32_t ri = red_off / 8, gi = green_off / 8, bi = blue_off / 8;
    if (ri >= bytes || gi >= bytes || bi >= bytes || (ri == gi && gi == bi)) {
        ri = 0; gi = 1; bi = 2;
    }
    buf[off + ri] = r; buf[off + gi] = g; buf[off + bi] = b;
    if (bytes == 4) buf[off + 3] = 0xff;
}
}
