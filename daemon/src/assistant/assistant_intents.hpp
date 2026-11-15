#pragma once

#include <string>

namespace openstation {

// Input is already lower-cased by the transcript handler. Match only short,
// unambiguous requests; named devices must continue through the normal path.
inline bool is_assistant_stop_request(const std::string& text) {
    std::string normalized;
    for (unsigned char ch : text) {
        if (ch == ',' || ch == '.' || ch == '!' || ch == '?' || ch == '\n' || ch == '\t' || ch == ' ') {
            if (!normalized.empty() && normalized.back() != ' ') normalized += ' ';
        } else {
            normalized += static_cast<char>(ch);
        }
    }
    if (!normalized.empty() && normalized.back() == ' ') normalized.pop_back();
    return normalized == "выключись" || normalized == "алиса выключись" ||
           normalized == "выключись пожалуйста" || normalized == "алиса выключись пожалуйста" ||
           normalized == "пожалуйста выключись" || normalized == "алиса пожалуйста выключись";
}

inline bool block_power_tool_for_stop(bool stop_requested, const std::string& tool) {
    return stop_requested && (tool == "tv_hdmi_cec" || tool == "remote_control" || tool == "device_power");
}

inline std::string normalize_device_power_action(const std::string& raw) {
    if (raw == "wake" || raw == "wakeup" || raw == "wake_screen" || raw == "screen_on" ||
        raw == "tv_on" || raw == "display_on" || raw == "on") {
        return "screen_wake";
    }
    if (raw == "sleep" || raw == "sleep_screen" || raw == "screen_off" ||
        raw == "tv_off" || raw == "display_off" || raw == "standby" ||
        raw == "blank" || raw == "off") {
        return "screen_sleep";
    }
    if (raw == "restart" || raw == "restart_daemon" || raw == "reboot_daemon" ||
        raw == "reload_daemon" || raw == "restart_service") {
        return "restart_daemon";
    }
    return raw;
}

// Input is already lower-cased via utf8_to_lower_ru.
// Returns "screen_sleep", "screen_wake", or "" when no unambiguous screen/TV power command matched.
inline std::string parse_local_screen_power_intent(const std::string& lower_ru) {
    if (lower_ru.empty() || is_assistant_stop_request(lower_ru)) return "";
    auto has_any = [&](std::initializer_list<const char*> needles) -> bool {
        for (const char* n : needles) {
            if (lower_ru.find(n) != std::string::npos) return true;
        }
        return false;
    };
    if (has_any({"не выключай", "не отключай", "не гаси", "не вырубай", "не включай",
                 "матриц", "кольцо", "подсвет", "светодиод", "блютуз", "музык", "волн"})) {
        return "";
    }
    const bool has_target = has_any({
        "экран", "дисплей", "монитор", "телевизор", "телик", "телек", " телик", "тв"
    });
    if (!has_target) return "";
    // Avoid false match on substrings like "втв" inside unrelated words for "тв"
    bool target_ok = has_any({"экран", "дисплей", "монитор", "телевизор", "телик", "телек"});
    if (!target_ok) {
        size_t pos = lower_ru.find("тв");
        while (pos != std::string::npos) {
            bool left_ok = (pos == 0 || lower_ru[pos - 1] == ' ' || lower_ru[pos - 1] == ',' || lower_ru[pos - 1] == '.');
            size_t end = pos + 4; // "тв" is 4 UTF-8 bytes
            bool right_ok = (end >= lower_ru.size() || lower_ru[end] == ' ' || lower_ru[end] == ',' ||
                             lower_ru[end] == '.' || lower_ru[end] == '!' || lower_ru[end] == '?');
            if (left_ok && right_ok) {
                target_ok = true;
                break;
            }
            pos = lower_ru.find("тв", pos + 4);
        }
    }
    if (!target_ok) return "";

    if (has_any({"выключи", "выключить", "отключи", "отключить", "погаси", "погасить",
                 "гаси", "выруби", "вырубить", "усыпи", "усыпить"})) {
        return "screen_sleep";
    }
    if (has_any({"включи", "включить", "вруби", "врубить", "разбуди", "разбудить", "зажги", "зажечь"})) {
        return "screen_wake";
    }
    return "";
}

} // namespace openstation
