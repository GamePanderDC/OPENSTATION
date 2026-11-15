#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <thread>
#include <vector>

namespace openstation {

enum class HwEventType {
    RotaryUp,
    RotaryDown,
    MuteToggle,
    ActionShort,
    ActionLong,
    ActionHoldStart,
    ActionHoldEnd,
    JackPlugged,
    JackUnplugged,
    BluetoothToggle,
    HomePress,
    RemoteAssistant
};

class InputMonitor {
public:
    using Callback = std::function<void(HwEventType)>;

    InputMonitor() = default;
    ~InputMonitor();

    bool start(Callback cb);
    void stop();

    void set_hold_scrub_enabled(bool enabled) { hold_scrub_enabled_.store(enabled); }
    bool headphone_plugged() const { return hp_plugged_.load(); }

private:
    enum class DevRole { Keypad, Rotary, HeadphoneJack, RemoteControl };
    struct WatchedFd {
        int fd = -1;
        DevRole role;
        std::string name;
        std::string path;
        bool exclusive_grab = false;
        bool grabbed = false;
    };

    void scan_devices();
    void poll_loop();

    Callback cb_;
    std::vector<WatchedFd> fds_;
    std::atomic<bool> running_{false};
    std::atomic<bool> hp_plugged_{false};
    std::atomic<bool> hold_scrub_enabled_{true};
    std::thread worker_;
};

} // namespace openstation
