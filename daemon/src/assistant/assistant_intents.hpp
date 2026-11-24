#pragma once

#include <string>
#include <vector>

namespace openstation {

inline std::string utf8_to_lower_ru(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        if (c >= 'A' && c <= 'Z') {
            out.push_back(static_cast<char>(c + 32));
        } else if (c == 0xD0 && i + 1 < s.size()) {
            unsigned char c2 = static_cast<unsigned char>(s[i + 1]);
            if (c2 >= 0x90 && c2 <= 0xAF) {
                if (c2 <= 0x9F) {
                    out.push_back(static_cast<char>(0xD0));
                    out.push_back(static_cast<char>(c2 + 0x20));
                } else {
                    out.push_back(static_cast<char>(0xD1));
                    out.push_back(static_cast<char>(c2 - 0x20));
                }
                i++;
                continue;
            } else if (c2 == 0x81) {
                out.push_back(static_cast<char>(0xD1));
                out.push_back(static_cast<char>(0x91));
                i++;
                continue;
            }
            out.push_back(static_cast<char>(c));
        } else {
            out.push_back(static_cast<char>(c));
        }
    }
    return out;
}

inline std::string strip_wake_prefix(const std::string& input_lower) {
    std::string s = input_lower;
    auto trim_symbols = [](std::string& str) {
        size_t start = 0;
        while (start < str.size()) {
            unsigned char c = static_cast<unsigned char>(str[start]);
            if (c == ' ' || c == ',' || c == '.' || c == '!' || c == '?' || c == '-' || c == ':' || c == ';' || c == '\n' || c == '\t') {
                start++;
            } else {
                break;
            }
        }
        size_t end = str.size();
        while (end > start) {
            unsigned char c = static_cast<unsigned char>(str[end - 1]);
            if (c == ' ' || c == ',' || c == '.' || c == '!' || c == '?' || c == '-' || c == ':' || c == ';' || c == '\n' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        str = str.substr(start, end - start);
    };

    trim_symbols(s);
    static const std::vector<std::string> prefixes = {
        "алиса", "яндекс", "станция", "эй алиса", "слушай алиса", "привет алиса",
        "ок алиса", "эй яндекс", "слушай яндекс", "привет яндекс"
    };
    bool found = true;
    while (found) {
        found = false;
        for (const auto& pref : prefixes) {
            if (s.rfind(pref, 0) == 0) {
                if (s.size() == pref.size() ||
                    s[pref.size()] == ' ' || s[pref.size()] == ',' || s[pref.size()] == '.' ||
                    s[pref.size()] == '!' || s[pref.size()] == '?' || s[pref.size()] == '-' ||
                    s[pref.size()] == ':' || s[pref.size()] == ';') {
                    s = s.substr(pref.size());
                    trim_symbols(s);
                    found = true;
                    break;
                }
            }
        }
    }
    return s;
}

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

    std::string stripped = strip_wake_prefix(normalized);
    return stripped == "выключись" || stripped == "выключись пожалуйста" ||
           stripped == "пожалуйста выключись" || stripped == "отключись" ||
           stripped == "спи" || stripped == "засни" ||
           normalized == "выключись" || normalized == "алиса выключись" ||
           normalized == "выключись пожалуйста" || normalized == "алиса выключись пожалуйста" ||
           normalized == "пожалуйста выключись" || normalized == "алиса пожалуйста выключись";
}

inline bool block_power_tool_for_stop(bool stop_requested, const std::string& tool) {
    return stop_requested && (tool == "tv_hdmi_cec" || tool == "remote_control" || tool == "device_power");
}

inline std::string normalize_device_power_action(const std::string& raw) {
    if (raw == "wake" || raw == "wakeup" || raw == "wake_screen" || raw == "screen_on") {
        return "screen_wake";
    }
    if (raw == "sleep" || raw == "sleep_screen" || raw == "screen_off") {
        return "screen_sleep";
    }
    if (raw == "restart" || raw == "restart_daemon" || raw == "reboot_daemon" ||
        raw == "reload_daemon" || raw == "restart_service") {
        return "restart_daemon";
    }
    return raw;
}

} // namespace openstation
