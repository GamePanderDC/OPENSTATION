#pragma once

#include <cctype>
#include <string>

namespace openstation {

inline std::string trim_ascii_whitespace(std::string s) {
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front()))) s.erase(s.begin());
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) s.pop_back();
    return s;
}

inline bool is_male_voice_name(const std::string& voice_name) {
    return voice_name == "Puck" || voice_name == "Charon" ||
           voice_name == "Fenrir" || voice_name == "Orus";
}

inline std::string personalize_assistant_system_prompt(std::string custom_sp,
                                                       const std::string& raw_name,
                                                       bool is_male_voice) {
    const std::string clean_name = trim_ascii_whitespace(raw_name);
    const std::string asst_name = clean_name.empty() ? std::string("Алиса") : clean_name;

    auto replace_all = [&](const std::string& from, const std::string& to) {
        if (from.empty() || from == to) return;
        size_t pos = 0;
        while ((pos = custom_sp.find(from, pos)) != std::string::npos) {
            custom_sp.replace(pos, from.size(), to);
            pos += to.size();
        }
    };

    const std::string legacy_gs = "Для погоды, новостей и актуальных фактов используй Google Search.";
    replace_all(legacy_gs, "");

    const std::string base_role =
        (is_male_voice ? std::string("Ты — голосовой ассистент ") : std::string("Ты — девушка, голосовая помощница ")) + asst_name;
    const std::string witty_role =
        (is_male_voice ? std::string("Ты — весёлый и остроумный ") : std::string("Ты — весёлая и остроумная ")) + asst_name;

    replace_all("Ты — девушка, голосовая помощница Алиса", base_role);
    replace_all("Ты — голосовой ассистент Алиса", base_role);
    replace_all("Ты — весёлая и остроумная Алиса", witty_role);
    replace_all("Ты — весёлый и остроумный Алиса", witty_role);

    if (is_male_voice) {
        replace_all("Говори о себе исключительно в женском роде («я готова», «я нашла», «я включила», «я сделала»).",
                    "Говори о себе исключительно в мужском роде («я готов», «я нашёл», «я включил», «я сделал»).");
        replace_all("Ты — весёлая и остроумная ", "Ты — весёлый и остроумный ");
        replace_all("Ты — девушка, голосовая помощница ", "Ты — голосовой ассистент ");
        replace_all("оставаясь полезной", "оставаясь полезным");
    } else {
        replace_all("Говори о себе исключительно в мужском роде («я готов», «я нашёл», «я включил», «я сделал»).",
                    "Говори о себе исключительно в женском роде («я готова», «я нашла», «я включила», «я сделала»).");
        replace_all("Ты — весёлый и остроумный ", "Ты — весёлая и остроумная ");
        replace_all("оставаясь полезным", "оставаясь полезной");
    }
    return trim_ascii_whitespace(custom_sp);
}

} // namespace openstation
