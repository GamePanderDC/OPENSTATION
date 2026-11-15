#include "hw/framebuffer_hw.hpp"
#include <cassert>
#include <string>

int main() {
    using openstation::FramebufferHw;

    // Empty string -> 0 width, still bounded to minimum banner duration
    assert(FramebufferHw::measure_text_pixel_width("") == 0);
    assert(FramebufferHw::compute_full_scroll_duration_ms("") >= 2500);

    // Cyrillic + punctuation/dash/quotes glyphs all measure positive non-zero widths
    const std::string short_track = "Miyagi - Samurai";
    const int short_w = FramebufferHw::measure_text_pixel_width(short_track);
    assert(short_w >= static_cast<int>(short_track.size()) * 3);

    // Very long artist - track title computes full traversal > 15 seconds (never clipped at 12-15s)
    const std::string long_track =
        "Кино — Перемен требуют наши сердца (Remastered Live Edition) / Victor Tsoi & Kino";
    const int long_w = FramebufferHw::measure_text_pixel_width(long_track);
    assert(long_w > short_w);
    const int long_ms = FramebufferHw::compute_full_scroll_duration_ms(long_track, 16.0, 25);
    assert(long_ms > 20000);
    assert(long_ms <= 180000);

    // Extra-long title respects 180s upper clamp cleanly
    std::string giant(500, 'A');
    const int giant_ms = FramebufferHw::compute_full_scroll_duration_ms(giant, 16.0, 25);
    assert(giant_ms == 180000);

    return 0;
}
