#include "config.hpp"
#include <cassert>

int main() {
    openstation::DaemonConfig config;
    const std::string selected = "Ты — голосовой ассистент Алиса. Отвечай коротко.";
    config.assistant.system_prompt = selected;
    config.sanitize_and_validate();
    assert(config.assistant.system_prompt == selected);
    openstation::DaemonConfig restored;
    restored.from_json(config.to_json());
    restored.sanitize_and_validate();
    assert(restored.assistant.system_prompt == selected);
    restored.assistant.system_prompt.clear();
    restored.sanitize_and_validate();
    assert(!restored.assistant.system_prompt.empty());
}
