#pragma once
#include <cstdint>
#include <string>

namespace openstation {
struct ChatGptIdentity {
    std::string subject;
    std::string email;
};

// Verifies the OIDC ID token against OpenAI's JWKS and the pending OAuth attempt.
bool verify_chatgpt_id_token(const std::string& token, const std::string& jwks_json,
                             const std::string& client_id, const std::string& nonce,
                             int64_t now_epoch, ChatGptIdentity& identity);
}
