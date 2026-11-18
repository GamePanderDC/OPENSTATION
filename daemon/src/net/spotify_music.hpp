#pragma once

#include "utils/json_min.hpp"
#include "web/http_ws_server.hpp"

#include <atomic>
#include <chrono>
#include <mutex>
#include <string>

namespace openstation {

class SpotifyMusic {
public:
    explicit SpotifyMusic(const std::atomic<bool>& shutdown);
    HttpResponse handle(const HttpRequest& request);
    void pause_if_playing();

private:
    using Clock = std::chrono::steady_clock;
    HttpResponse begin(const json::Value& input);
    HttpResponse finish(const std::string& state, const std::string& code);
    HttpResponse status();
    HttpResponse browse(const json::Value& input);
    HttpResponse command(const json::Value& input);
    int exchange(const std::string& client, const std::string& form, bool refresh);
    int api(const std::string& path, json::Value& output, const char* method = "GET",
            const std::string& body = "");
    bool save(const std::string& provider, const std::string& client, const std::string& refresh) const;

    const std::atomic<bool>& shutdown_;
    std::mutex mutex_;
    std::string provider_ = "yandex";
    std::string client_;
    std::string refresh_;
    std::string access_;
    std::string pending_client_;
    std::string pending_state_;
    std::string pending_verifier_;
    Clock::time_point pending_until_{};
    Clock::time_point access_until_{};
    Clock::time_point retry_at_{};
    Clock::time_point status_until_{};
    json::Value playback_;
    std::string saved_track_;
    std::string checked_track_;
    int playback_status_ = 204;
};

} // namespace openstation
