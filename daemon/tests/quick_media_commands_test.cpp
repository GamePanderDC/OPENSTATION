#include "assistant/quick_commands.hpp"
#include "assistant/quiet_speech.hpp"

#include <array>
#include <cassert>
#include <cmath>
#include <random>

using namespace openstation;

int main() {
    assert(quick_command_from_transcript("  на паузу! ").command == QuickCommand::Pause);
    assert(quick_command_from_transcript("сними с паузы").command == QuickCommand::Play);
    assert(quick_command_from_transcript("предыдущий трек").command == QuickCommand::Previous);
    assert(quick_command_from_transcript("следующее видео").command == QuickCommand::VideoNext);
    assert(quick_command_from_transcript("там можно сделать громче музыку").command == QuickCommand::None);
    assert(quick_command_from_transcript("перемотай назад на двадцать секунд").seconds == -20);
    assert(quick_command_from_transcript("перемотай на две минуты вперёд").seconds == 120);
    assert(quick_command_from_transcript("перемотай вперед на секунду").seconds == 1);
    assert(quick_command_from_transcript("перемотай на 601 секунду назад").command == QuickCommand::None);
    assert(quick_command_from_transcript("перемотай на много секунд назад").command == QuickCommand::None);
    assert(quick_command_from_transcript("включи на ютубе").command == QuickCommand::None);
    const auto search = quick_command_from_transcript("найди на ютубе северное сияние");
    assert(search.command == QuickCommand::YouTubeSearch && search.query == "северное сияние");
    assert(quick_command_from_transcript("найди на ютуби джаз").query == "джаз");
    const auto play = quick_command_from_transcript("включи на youtube северное сияние");
    assert(play.command == QuickCommand::YouTube && play.query == "северное сияние");

    QuietSpeech voiced, unvoiced, silence, noise_floor;
    std::array<int16_t, 320> frame{};
    std::mt19937 random(25);
    std::uniform_real_distribution<float> noise(-1.0f, 1.0f);

    for (int block = 0; block < 15; ++block) {
        silence.observe(frame.data(), frame.size(), 25.0f);
    }
    for (int block = 0; block < 15; ++block) {
        for (size_t i = 0; i < frame.size(); ++i) {
            frame[i] = static_cast<int16_t>((300 + block * 23) * std::sin(i * 0.061f));
        }
        voiced.observe(frame.data(), frame.size(), 35.0f);
        for (auto& sample : frame) sample = static_cast<int16_t>((380 + block * 18) * noise(random));
        unvoiced.observe(frame.data(), frame.size(), 40.0f);
        noise_floor.observe(frame.data(), frame.size(), 500.0f);
    }
    assert(!silence.detected() && !voiced.detected() && !noise_floor.detected());
    assert(unvoiced.detected());
}
