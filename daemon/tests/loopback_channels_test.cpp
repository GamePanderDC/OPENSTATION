#include "hw/loopback_channels.hpp"
#include <cassert>

int main() {
    using openstation::is_stereo_loopback;
    assert(is_stereo_loopback(8, 16));
    assert(is_stereo_loopback(10, 16));
    assert(!is_stereo_loopback(10, 11));
    assert(!is_stereo_loopback(8, 9));
    assert(!is_stereo_loopback(9, 16));
    assert(!is_stereo_loopback(-1, 16));
}
