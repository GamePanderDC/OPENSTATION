#pragma once
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace openstation {
// Local Russian speech recognition; never sends microphone samples to a server.
class OfflineAsr {
public:
    OfflineAsr();
    ~OfflineAsr();
    OfflineAsr(const OfflineAsr&) = delete;
    OfflineAsr& operator=(const OfflineAsr&) = delete;
    bool initialize(std::string& error);
    bool recognize(const std::vector<int16_t>& pcm, std::string& text, std::string& error, bool require_speech = true);
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    std::mutex mutex_;
};
}
