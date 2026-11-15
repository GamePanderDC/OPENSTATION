#include "hw/audio_mix.hpp"

#include <cassert>
#include <cstdio>
#include <vector>

namespace {

void test_empty_and_null_guards() {
    int16_t buf[4] = {100, -200, 300, -400};
    int16_t rm[4] = {10, 20, 30, 40};
    openstation::mix_remote_and_station_pcm(nullptr, rm, 4);
    openstation::mix_remote_and_station_pcm(buf, nullptr, 4);
    openstation::mix_remote_and_station_pcm(buf, rm, 0);
    assert(buf[0] == 100 && buf[1] == -200 && buf[2] == 300 && buf[3] == -400);
}

void test_silent_remote_preserves_station_dominance() {
    int16_t station[4] = {1000, -1000, 500, -500};
    const int16_t remote[4] = {0, 0, 0, 0};
    openstation::mix_remote_and_station_pcm(station, remote, 4);
    // With silent remote: r_dom = 0 => w_station = 0.95 => ~950, -950, 475, -475
    assert(station[0] >= 940 && station[0] <= 960);
    assert(station[1] <= -940 && station[1] >= -960);
    assert(station[2] >= 470 && station[2] <= 480);
    assert(station[3] <= -470 && station[3] >= -480);
}

void test_tandem_mix_combines_both_microphones() {
    int16_t station[4] = {1000, 1000, 1000, 1000};
    const int16_t remote[4] = {2000, 2000, 2000, 2000};
    openstation::mix_remote_and_station_pcm(station, remote, 4);
    // Mixed amplitude includes both station and remote speech in tandem
    assert(station[0] > 1800 && station[0] < 2600);
    assert(station[1] == station[0] && station[2] == station[0] && station[3] == station[0]);
}

void test_saturation_clamping() {
    int16_t station[4] = {32767, -32768, 30000, -30000};
    const int16_t remote[4] = {32767, -32768, 30000, -30000};
    openstation::mix_remote_and_station_pcm(station, remote, 4);
    assert(station[0] == 32767);
    assert(station[1] == -32768);
    assert(station[2] == 32767);
    assert(station[3] == -32768);
}

}  // namespace

int main() {
    test_empty_and_null_guards();
    test_silent_remote_preserves_station_dominance();
    test_tandem_mix_combines_both_microphones();
    test_saturation_clamping();
    std::printf("audio_mix_test: all checks passed\n");
    return 0;
}
