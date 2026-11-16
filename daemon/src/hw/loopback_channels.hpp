#pragma once

namespace openstation {
// The synchronized Max capture layout has stereo references at 8/9 (HDMI or
// software) and 10/11 (speaker). Other configured channels remain mono.
inline bool is_stereo_loopback(int first_channel, int channel_count) {
    return (first_channel == 8 || first_channel == 10) &&
           first_channel + 1 < channel_count;
}
} // namespace openstation
