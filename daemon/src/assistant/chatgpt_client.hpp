#pragma once

#include "assistant/chatgpt_auth.hpp"

namespace openstation {

bool chatgpt_list_models(ChatGptAuth& auth, json::Value& models, std::string& error);
bool chatgpt_reply(ChatGptAuth& auth, const std::string& model, const std::string& instructions,
                   const json::Value& input, const std::atomic<bool>& abort,
                   std::string& reply, std::string& error);

}
