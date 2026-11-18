#pragma once

#include <string_view>

namespace openstation {

enum class QuickCommand { None, Louder, Quieter, Next, Previous, Play, Pause, Stop };

struct QuickPhrase {
    std::string_view text;
    QuickCommand command;
};

inline constexpr QuickPhrase kQuickPhrases[] = {
    {"громче", QuickCommand::Louder},
    {"тише", QuickCommand::Quieter},
    {"дальше", QuickCommand::Next},
    {"назад", QuickCommand::Previous},
    {"играй", QuickCommand::Play},
    {"продолжай", QuickCommand::Play},
    {"пауза", QuickCommand::Pause},
    {"останови музыку", QuickCommand::Stop},
    {"остановим музыку", QuickCommand::Stop},
};

inline QuickCommand quick_command_from_transcript(std::string_view transcript) {
    while (!transcript.empty() && transcript.front() == ' ') transcript.remove_prefix(1);
    while (!transcript.empty() &&
           (transcript.back() == ' ' || transcript.back() == '.' || transcript.back() == '!')) {
        transcript.remove_suffix(1);
    }
    for (const auto& phrase : kQuickPhrases)
        if (transcript == phrase.text) return phrase.command;
    return QuickCommand::None;
}

}
