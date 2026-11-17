#include "hw/remote_keys.hpp"
#include <cassert>

int main() {
    assert(openstation::is_remote_assistant_key("Yandex Remote", KEY_SEARCH));
    assert(openstation::is_remote_assistant_key("YNDX-0006", KEY_VOICECOMMAND));
    assert(!openstation::is_remote_assistant_key("Generic Remote", KEY_SEARCH));
    assert(!openstation::is_remote_assistant_key("Yandex Remote", KEY_RIGHT));
    using namespace std::chrono;
    using openstation::RemoteAssistantGesture;
    openstation::RemoteAssistantPress press;
    const auto t = openstation::RemoteAssistantPress::Clock::now();
    assert(press.event(1, t) == RemoteAssistantGesture::None);
    assert(press.event(1, t + milliseconds(100)) == RemoteAssistantGesture::None);
    assert(press.event(2, t + milliseconds(200)) == RemoteAssistantGesture::None);
    assert(press.event(0, t + milliseconds(300)) == RemoteAssistantGesture::Short);
    assert(press.event(1, t) == RemoteAssistantGesture::None);
    assert(press.tick(t + milliseconds(800)) == RemoteAssistantGesture::Long);
    assert(press.tick(t + milliseconds(900)) == RemoteAssistantGesture::None);
    assert(press.event(0, t + milliseconds(1000)) == RemoteAssistantGesture::None);
    assert(press.event(1, t) == RemoteAssistantGesture::None);
    assert(press.event(0, t + milliseconds(900)) == RemoteAssistantGesture::Long);
    assert(press.event(0, t + milliseconds(1000)) == RemoteAssistantGesture::None);
}
