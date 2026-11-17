#pragma once
#include <linux/input.h>
#include <chrono>
#include <string>

namespace openstation {
inline bool is_remote_assistant_key(const std::string& name, int code) {
    const bool yandex_remote = name.find("Yandex Remote") != std::string::npos ||
                               name.find("YNDX-0006") != std::string::npos;
    return yandex_remote && (code == KEY_SEARCH || code == KEY_VOICECOMMAND || code == KEY_ASSISTANT);
}

enum class RemoteAssistantGesture { None, Short, Long };

// Each input device owns its own state; repeats cannot trigger extra actions.
class RemoteAssistantPress {
public:
    using Clock = std::chrono::steady_clock;
    RemoteAssistantGesture event(int value, Clock::time_point now) {
        if (value == 1 && !down_) { down_ = true; long_fired_ = false; start_ = now; }
        if (value != 0 || !down_) return RemoteAssistantGesture::None;
        auto result = long_fired_ ? RemoteAssistantGesture::None :
            (now - start_ >= std::chrono::milliseconds(800) ? RemoteAssistantGesture::Long : RemoteAssistantGesture::Short);
        down_ = false;
        return result;
    }
    RemoteAssistantGesture tick(Clock::time_point now) {
        if (down_ && !long_fired_ && now - start_ >= std::chrono::milliseconds(800)) {
            long_fired_ = true;
            return RemoteAssistantGesture::Long;
        }
        return RemoteAssistantGesture::None;
    }
private:
    bool down_ = false;
    bool long_fired_ = false;
    Clock::time_point start_{};
};
}
