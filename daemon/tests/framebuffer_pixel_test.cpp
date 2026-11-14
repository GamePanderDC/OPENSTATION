#include "hw/framebuffer_pixel.hpp"
#include <cassert>

int main() {
    using openstation::write_framebuffer_pixel;
    std::vector<uint8_t> screen(75 * 16, 0);
    // 24-bpp screen reports grayscale=1 on some firmware; the final column still uses three bytes.
    write_framebuffer_pixel(screen, 25, 16, 75, 24, 0, 8, 16, 24, 15, 31, 47, 63);
    assert(screen[15 * 75 + 72] == 31);
    assert(screen[15 * 75 + 73] == 47);
    assert(screen[15 * 75 + 74] == 63);
    assert(screen[15 * 75 + 24] == 0);
    std::vector<uint8_t> mono(10, 0);
    write_framebuffer_pixel(mono, 5, 2, 5, 8, 0, 0, 0, 4, 1, 255, 255, 255);
    assert(mono[9] == 255);
    std::vector<uint8_t> ring(4, 0);
    write_framebuffer_pixel(ring, 1, 1, 4, 32, 16, 8, 0, 0, 0, 1, 2, 3);
    assert(ring[0] == 3 && ring[1] == 2 && ring[2] == 1 && ring[3] == 255);
}
