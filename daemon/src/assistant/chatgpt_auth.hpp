#pragma once

#include "utils/json_min.hpp"

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

namespace openstation {

class ChatGptAuth {
public:
    ~ChatGptAuth();
    bool start(const std::string& client_id, std::string& url, std::string& error);
    json::Value status();
    bool select(const std::string& client_id);
    bool access_token(std::string& token, std::string& error);
    bool sign_out(bool& revoked, std::string& error);

private:
    void callback_loop(int fd, std::string state, std::string nonce,
                       std::string verifier, std::string prior_client);
    std::mutex mutex_;
    std::thread listener_;
    std::atomic<bool> stop_{false};
    std::string state_ = "idle";
    std::string error_;
};

}
