#include "assistant/assistant_intents.hpp"
#include <cassert>

int main() {
    using openstation::is_assistant_stop_request;
    assert(is_assistant_stop_request("выключись"));
    assert(is_assistant_stop_request("алиса, выключись!"));
    assert(is_assistant_stop_request("алиса, пожалуйста выключись."));
    assert(!is_assistant_stop_request("выключи телевизор"));
    assert(!is_assistant_stop_request("телевизор выключись"));
    assert(!is_assistant_stop_request("не выключись"));
    assert(!is_assistant_stop_request("выключи музыку"));
    assert(openstation::block_power_tool_for_stop(true, "tv_hdmi_cec"));
    assert(openstation::block_power_tool_for_stop(true, "device_power"));
    assert(openstation::block_power_tool_for_stop(true, "remote_control"));
    assert(!openstation::block_power_tool_for_stop(false, "tv_hdmi_cec"));
    assert(!openstation::block_power_tool_for_stop(true, "stop_assistant"));
    assert(openstation::normalize_device_power_action("restart_daemon") == "restart_daemon");
    assert(openstation::normalize_device_power_action("restart") == "restart_daemon");
    assert(openstation::normalize_device_power_action("reboot_daemon") == "restart_daemon");
    assert(openstation::normalize_device_power_action("wake") == "screen_wake");
    assert(openstation::normalize_device_power_action("tv_on") == "screen_wake");
    assert(openstation::normalize_device_power_action("sleep") == "screen_sleep");
    assert(openstation::normalize_device_power_action("screen_off") == "screen_sleep");
    assert(openstation::normalize_device_power_action("standby") == "screen_sleep");
    assert(openstation::normalize_device_power_action("reboot") == "reboot");

    assert(openstation::parse_local_screen_power_intent("выключи экран") == "screen_sleep");
    assert(openstation::parse_local_screen_power_intent("погаси экран пожалуйста") == "screen_sleep");
    assert(openstation::parse_local_screen_power_intent("алиса выключи телевизор") == "screen_sleep");
    assert(openstation::parse_local_screen_power_intent("выруби телик") == "screen_sleep");
    assert(openstation::parse_local_screen_power_intent("выключи тв") == "screen_sleep");
    assert(openstation::parse_local_screen_power_intent("включи экран") == "screen_wake");
    assert(openstation::parse_local_screen_power_intent("включи телевизор") == "screen_wake");
    assert(openstation::parse_local_screen_power_intent("выключись").empty());
    assert(openstation::parse_local_screen_power_intent("не выключай экран").empty());
    assert(openstation::parse_local_screen_power_intent("выключи подсветку").empty());
    assert(openstation::parse_local_screen_power_intent("выключи bluetooth").empty());
}
