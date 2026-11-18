#include "assistant/chatgpt_client.hpp"

#include "assistant/chatgpt_http.hpp"
#include <sstream>

namespace openstation {
namespace {
using json::Value;

std::string field(const Value& value, const char* key) {
    const auto* v = value.get(key);
    return v && v->is_str() ? v->as_str() : "";
}

std::string response_error(const HttpClientResponse& result) {
    if (result.status_code == 401) return "Сеанс ChatGPT истёк. Войдите снова.";
    if (result.status_code == 403) return "ChatGPT отклонил доступ. Проверьте разрешение в аккаунте и сеть.";
    if (result.status_code == 429) return "Достигнут лимит ChatGPT. Проверьте использование в аккаунте.";
    return "Не удалось получить ответ от ChatGPT.";
}

bool read_reply(const std::string& sse, std::string& reply, std::string& error) {
    std::istringstream stream(sse);
    std::string data;
    bool completed = false;
    auto read_event = [&]() {
        Value event;
        if (!json::Parser::parse(data, event) || !event.is_obj()) return;
        const std::string type = field(event, "type");
        if (type == "response.output_text.delta" || type == "response.refusal.delta") {
            reply += field(event, "delta");
        } else if (type == "response.completed") {
            completed = true;
        } else if (type == "response.failed" || type == "response.incomplete" || type == "error") {
            const auto* response = event.get("response");
            const auto* detail = response ? response->get("error") : &event;
            const std::string code = detail ? field(*detail, "code") : "";
            if (code == "subscription_sharing_usage_limit_exceeded" ||
                code == "subscription_sharing_usage_unavailable") {
                error = "Лимит ChatGPT исчерпан или доступ через подписку отключён.";
            } else {
                error = "ChatGPT не завершил ответ.";
            }
        }
    };
    for (std::string line; std::getline(stream, line);) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty()) {
            if (!data.empty()) read_event();
            data.clear();
        } else if (line.compare(0, 5, "data:") == 0) {
            if (!data.empty()) data += '\n';
            const size_t start = line.size() > 5 && line[5] == ' ' ? 6 : 5;
            data += line.substr(start);
        }
    }
    if (!data.empty()) read_event();
    if (error.empty() && (!completed || reply.empty())) error = "ChatGPT не прислал полный ответ.";
    return error.empty();
}
}

bool chatgpt_list_models(ChatGptAuth& auth, json::Value& models, std::string& error) {
    std::string token;
    if (!auth.access_token(token, error)) return false;
    HttpClientRequest request;
    request.url = "https://api.openai.com/v1/models";
    request.max_body_bytes = 4 * 1024 * 1024;
    request.headers["Authorization"] = "Bearer " + token;
    const auto response = chatgpt_http_request(std::move(request));
    if (response.status_code != 200) {
        error = response_error(response);
        return false;
    }
    Value catalog;
    if (!response.truncated && json::Parser::parse(response.body, catalog)) {
        const auto* entries = catalog.get("models");
        if (entries && entries->is_arr()) {
            models = Value::make_arr();
            for (const auto& item : entries->arr_val) {
                const auto slug = field(item, "slug");
                if (field(item, "visibility") != "list" || slug.empty() || slug.size() > 128) continue;
                Value entry = Value::make_obj();
                entry["slug"] = Value::make_str(slug);
                const auto label = field(item, "display_name");
                entry["name"] = Value::make_str(label.empty() ? slug : label);
                models.arr_val.push_back(std::move(entry));
            }
            if (!models.arr_val.empty()) return true;
        }
    }
    error = "В аккаунте нет доступных моделей ChatGPT.";
    return false;
}

bool chatgpt_reply(ChatGptAuth& auth, const std::string& model, const std::string& instructions,
                   const json::Value& input, const std::atomic<bool>& abort,
                   std::string& reply, std::string& error) {
    reply.clear();
    error.clear();
    if (model.empty()) {
        error = "Выберите модель ChatGPT в настройках.";
        return false;
    }
    std::string token;
    if (abort || !auth.access_token(token, error) || abort) return false;
    Value body = Value::make_obj();
    body["model"] = Value::make_str(model);
    body["instructions"] = Value::make_str(instructions);
    body["input"] = input;
    body["stream"] = Value::make_bool(true);
    body["store"] = Value::make_bool(false);
    HttpClientRequest request;
    request.url = "https://api.openai.com/v1/responses";
    request.method = "POST";
    request.headers["Authorization"] = "Bearer " + token;
    request.headers["Content-Type"] = "application/json";
    request.headers["Accept"] = "text/event-stream";
    request.body = body.dump();
    request.max_body_bytes = 2 * 1024 * 1024;
    request.timeout_ms = 45000;
    request.abort_req = &abort;
    const auto response = chatgpt_http_request(std::move(request));
    if (response.status_code != 200 || response.truncated) {
        error = response_error(response);
        return false;
    }
    return read_reply(response.body, reply, error);
}

}
