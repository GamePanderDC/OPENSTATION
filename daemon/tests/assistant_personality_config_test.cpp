#include "config.hpp"
#include <cassert>

int main() {
    openstation::DaemonConfig config;
    const std::string selected = "Ты — голосовой ассистент Алиса. Отвечай коротко.";
    config.assistant.system_prompt = selected;
    config.assistant.name = "Джарвис";
    config.assistant.gemini_api_key = "secret-gemini-key";
    config.audio.hardware_eq_profile = "custom";
    config.audio.hardware_eq_bass_gain = 4.5;
    config.audio.hardware_eq_treble_gain = -1.5;
    config.audio.hardware_eq_subsonic = 55;
    config.audio.hardware_eq_notch120 = false;
    config.home_assistant.url = "http://192.168.0.5:8123/";
    config.home_assistant.token = "ha-secret-token";
    openstation::AlarmEntry al;
    al.id = 1;
    al.time = "07:30";
    al.label = "Подъём";
    al.days = {true, true, true, true, true, false, false};
    al.sound = "chime";
    config.alarms.push_back(al);
    config.sanitize_and_validate();
    assert(config.assistant.system_prompt == selected);
    assert(config.assistant.name == "Джарвис");
    assert(config.home_assistant.url == "http://192.168.0.5:8123");

    openstation::DaemonConfig restored;
    restored.from_json(config.to_json());
    restored.sanitize_and_validate();
    assert(restored.assistant.system_prompt == selected);
    assert(restored.assistant.name == "Джарвис");
    assert(restored.audio.hardware_eq_profile == "custom");
    assert(restored.audio.hardware_eq_subsonic == 55);
    assert(restored.audio.hardware_eq_notch120 == false);
    assert(restored.alarms.size() == 1 && restored.alarms[0].time == "07:30");

    auto hw_req = restored.audio.hardware_eq_request();
    assert(hw_req.get("profile") && hw_req.get("profile")->as_str() == "custom");
    assert(hw_req.get("params") && hw_req.get("filters"));

    // Simulate WebUI saving empty secrets (must preserve existing tokens)
    openstation::json::Value empty_secrets = openstation::json::Value::make_obj();
    empty_secrets["assistant"]["geminiApiKey"] = openstation::json::Value::make_str("");
    empty_secrets["homeAssistant"]["token"] = openstation::json::Value::make_str("");
    restored.from_json(empty_secrets);
    assert(restored.assistant.gemini_api_key == "secret-gemini-key");
    assert(restored.home_assistant.token == "ha-secret-token");

    restored.assistant.system_prompt.clear();
    restored.assistant.name.clear();
    restored.sanitize_and_validate();
    assert(!restored.assistant.system_prompt.empty());
    assert(!restored.assistant.name.empty());
}
