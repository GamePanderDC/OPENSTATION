#pragma once

#include <charconv>
#include <string>
#include <string_view>

namespace openstation {

enum class QuickCommand { None, Louder, Quieter, Next, Previous, Play, Pause, Stop, VideoNext, VideoPrevious, VideoStop, Seek, Home, YouTube, YouTubeSearch };

struct QuickAction {
    QuickCommand command = QuickCommand::None;
    int seconds = 0;
    std::string query;
};

struct QuickPhrase {
    std::string_view text;
    QuickCommand command;
};

inline constexpr QuickPhrase kQuickPhrases[] = {
    {"громче", QuickCommand::Louder},
    {"сделай громче", QuickCommand::Louder},
    {"тише", QuickCommand::Quieter},
    {"сделай тише", QuickCommand::Quieter},
    {"дальше", QuickCommand::Next},
    {"следующий трек", QuickCommand::Next},
    {"следующее видео", QuickCommand::VideoNext},
    {"назад", QuickCommand::Previous},
    {"предыдущий трек", QuickCommand::Previous},
    {"предыдущее видео", QuickCommand::VideoPrevious},
    {"играй", QuickCommand::Play},
    {"продолжай", QuickCommand::Play},
    {"продолжи", QuickCommand::Play},
    {"возобнови", QuickCommand::Play},
    {"сними с паузы", QuickCommand::Play},
    {"пауза", QuickCommand::Pause},
    {"на паузу", QuickCommand::Pause},
    {"поставь на паузу", QuickCommand::Pause},
    {"поставь музыку на паузу", QuickCommand::Pause},
    {"останови музыку", QuickCommand::Stop},
    {"остановим музыку", QuickCommand::Stop},
    {"останови видео", QuickCommand::VideoStop},
    {"домой", QuickCommand::Home},
    {"на главный экран", QuickCommand::Home},
    {"открой ютуб", QuickCommand::YouTube},
    {"открой youtube", QuickCommand::YouTube},
    {"открой смарттуб", QuickCommand::YouTube},
};

inline bool take_prefix(std::string_view& text, std::string_view prefix) {
    if (text.substr(0, prefix.size()) != prefix) return false;
    text.remove_prefix(prefix.size());
    return true;
}

inline bool take_suffix(std::string_view& text, std::string_view suffix) {
    if (text.size() < suffix.size() || text.substr(text.size() - suffix.size()) != suffix) return false;
    text.remove_suffix(suffix.size());
    return true;
}

inline int quick_duration_number(std::string_view text) {
    if (text.empty()) return 0;
    int value = 0;
    const auto result = std::from_chars(text.data(), text.data() + text.size(), value);
    if (result.ec == std::errc{} && result.ptr == text.data() + text.size()) return value;
    constexpr std::string_view units[] = {
        "", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
        "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    };
    if (text == "одну") return 1;
    if (text == "две") return 2;
    for (int i = 1; i < 20; ++i) if (text == units[i]) return i;
    constexpr std::string_view tens[] = {"двадцать", "тридцать", "сорок", "пятьдесят"};
    for (int i = 0; i < 4; ++i) {
        if (text == tens[i]) return (i + 2) * 10;
        auto rest = text;
        if (!take_prefix(rest, tens[i]) || !take_prefix(rest, " ")) continue;
        for (int unit = 1; unit < 10; ++unit) if (rest == units[unit]) return (i + 2) * 10 + unit;
    }
    return 0;
}

inline int quick_seek_seconds(std::string_view text) {
    if (!take_prefix(text, "перемотай ") && !take_prefix(text, "прокрути ")) return 0;
    int direction = 0;
    if (take_prefix(text, "назад ")) direction = -1;
    else if (take_prefix(text, "вперед ") || take_prefix(text, "вперёд ")) direction = 1;
    else if (take_suffix(text, " назад")) direction = -1;
    else if (take_suffix(text, " вперед") || take_suffix(text, " вперёд")) direction = 1;
    if (direction == 0 || !take_prefix(text, "на ")) return 0;
    int unit = 1;
    if (take_suffix(text, " минуту") || take_suffix(text, " минуты") || take_suffix(text, " минут")) {
        unit = 60;
    } else if (!take_suffix(text, " секунду") && !take_suffix(text, " секунды") &&
               !take_suffix(text, " секунд")) {
        if (text == "минуту") return direction * 60;
        if (text == "секунду") return direction;
        return 0;
    }
    const int number = quick_duration_number(text);
    if (number < 1 || number > 600 / unit) return 0;
    return direction * number * unit;
}

inline QuickAction quick_command_from_transcript(std::string_view transcript) {
    while (!transcript.empty() && transcript.front() == ' ') transcript.remove_prefix(1);
    while (!transcript.empty() &&
           (transcript.back() == ' ' || transcript.back() == '.' || transcript.back() == '!')) {
        transcript.remove_suffix(1);
    }
    for (const auto& phrase : kQuickPhrases) {
        if (transcript == phrase.text) return {phrase.command, 0, {}};
    }
    if (const int seconds = quick_seek_seconds(transcript); seconds != 0)
        return {QuickCommand::Seek, seconds, {}};
    auto query = transcript;
    const bool play_video = take_prefix(query, "включи на ");
    if ((play_video || take_prefix(query, "найди на ")) &&
        (take_prefix(query, "ютубе ") || take_prefix(query, "ютуби ") || take_prefix(query, "youtube ")) &&
        !query.empty() && query.size() <= 240) {
        return {play_video ? QuickCommand::YouTube : QuickCommand::YouTubeSearch, 0, std::string(query)};
    }
    return {};
}

} // namespace openstation
