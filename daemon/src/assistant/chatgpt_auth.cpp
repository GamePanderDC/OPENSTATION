#include "assistant/chatgpt_auth.hpp"

#include "assistant/chatgpt_http.hpp"
#include "assistant/chatgpt_oidc.hpp"
#include "utils/base64.hpp"

#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <ctime>
#include <fcntl.h>
#include <map>
#include <poll.h>
#include <sstream>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include "mbedtls/sha256.h"

namespace openstation {
namespace {

constexpr char kDirectory[] = "/data/local/openstation/chatgpt";
constexpr char kStore[] = "/data/local/openstation/chatgpt/accounts.json";
constexpr char kIssuer[] = "https://auth.openai.com";
constexpr char kCallback[] = "http://127.0.0.1:1455/auth/callback";
constexpr char kResource[] = "https://api.openai.com/v1";
constexpr char kDynamic[] = "dynamic_agent_client";
constexpr char kRequiredScope[] = "chatgpt.tokens.use.direct";

using json::Value;
using Fields = std::map<std::string, std::string>;

std::string field(const Value& obj, const std::string& name) {
    const auto* v = obj.get(name);
    return v && v->is_str() ? v->as_str() : "";
}

int64_t expiry(const Value& obj) {
    const auto* v = obj.get("expires_at");
    return v && v->is_num() ? v->as_i64() : 0;
}

bool random_bytes(uint8_t* bytes, size_t count) {
    const int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    size_t offset = 0;
    while (offset < count) {
        const ssize_t n = read(fd, bytes + offset, count - offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        offset += static_cast<size_t>(n);
    }
    close(fd);
    return offset == count;
}

std::string base64url(const uint8_t* bytes, size_t count) {
    auto text = b64::encode(bytes, count);
    for (char& c : text) {
        if (c == '+') c = '-';
        else if (c == '/') c = '_';
    }
    while (!text.empty() && text.back() == '=') text.pop_back();
    return text;
}

std::string random_secret() {
    uint8_t bytes[32];
    return random_bytes(bytes, sizeof(bytes)) ? base64url(bytes, sizeof(bytes)) : "";
}

std::string make_host_id() {
    uint8_t bytes[16];
    if (!random_bytes(bytes, sizeof(bytes))) return "";
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const char* hex = "0123456789abcdef";
    std::string id = "urn:uuid:";
    for (size_t i = 0; i < sizeof(bytes); ++i) {
        if (i == 4 || i == 6 || i == 8 || i == 10) id += '-';
        id += hex[bytes[i] >> 4];
        id += hex[bytes[i] & 0xf];
    }
    return id;
}

bool secure_directory() {
    if (mkdir(kDirectory, 0700) != 0 && errno != EEXIST) return false;
    struct stat st{};
    return lstat(kDirectory, &st) == 0 && S_ISDIR(st.st_mode) &&
           st.st_uid == geteuid() && (st.st_mode & 0077) == 0;
}

bool load_store(Value& obj) {
    obj = Value::make_obj();
    if (!secure_directory()) return false;
    const int fd = open(kStore, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return errno == ENOENT;
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid() ||
        (st.st_mode & 0077) || st.st_size <= 0 || st.st_size > 2 * 1024 * 1024) {
        close(fd);
        return false;
    }
    std::string data(static_cast<size_t>(st.st_size), '\0');
    size_t offset = 0;
    while (offset < data.size()) {
        const ssize_t n = read(fd, data.data() + offset, data.size() - offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        offset += static_cast<size_t>(n);
    }
    close(fd);
    return offset == data.size() && json::Parser::parse(data, obj) && obj.is_obj();
}

bool save_store(const Value& obj) {
    if (!secure_directory()) return false;
    const std::string secret = random_secret();
    if (secret.empty()) return false;
    const std::string path = std::string(kDirectory) + "/tmp-" + secret;
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return false;
    const std::string data = obj.dump();
    size_t offset = 0;
    while (offset < data.size()) {
        const ssize_t n = write(fd, data.data() + offset, data.size() - offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        offset += static_cast<size_t>(n);
    }
    const bool wrote = offset == data.size() && fsync(fd) == 0;
    const int closed = close(fd);
    if (!wrote || closed != 0 || rename(path.c_str(), kStore) != 0) {
        unlink(path.c_str());
        return false;
    }
    const int dir = open(kDirectory, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (dir >= 0) {
        fsync(dir);
        close(dir);
    }
    return true;
}

const Value* account(const Value& store, const std::string& client_id) {
    const auto* accounts = store.get("accounts");
    return accounts && accounts->is_obj() ? accounts->get(client_id) : nullptr;
}

bool valid_client_id(const std::string& value) {
    return !value.empty() && value != kDynamic && value.size() <= 128 &&
           value.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-") == std::string::npos;
}

std::string encode_form(const Fields& fields) {
    std::string form;
    for (const auto& item : fields) {
        if (!form.empty()) form += '&';
        form += item.first + "=" + url_encode(item.second);
    }
    return form;
}

int hex_digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

bool decode_form(const std::string& encoded, std::string& output) {
    for (size_t i = 0; i < encoded.size(); ++i) {
        unsigned char c = encoded[i];
        if (c == '+') c = ' ';
        else if (c == '%') {
            if (i + 2 >= encoded.size()) return false;
            const int a = hex_digit(encoded[i + 1]), b = hex_digit(encoded[i + 2]);
            if (a < 0 || b < 0) return false;
            c = static_cast<unsigned char>((a << 4) | b);
            i += 2;
        }
        if (c < 32 || c == 127) return false;
        output += static_cast<char>(c);
    }
    return true;
}

bool parse_callback(int connection, Fields& fields) {
    using Clock = std::chrono::steady_clock;
    const auto deadline = Clock::now() + std::chrono::seconds(2);
    std::string request;
    while (request.size() < 16384 && request.find("\r\n\r\n") == std::string::npos) {
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - Clock::now()).count();
        if (remaining <= 0) return false;
        pollfd fd{connection, POLLIN, 0};
        if (poll(&fd, 1, static_cast<int>(remaining)) <= 0) return false;
        char buffer[1024];
        const auto n = recv(connection, buffer, sizeof(buffer), MSG_DONTWAIT);
        if (n <= 0) return false;
        request.append(buffer, static_cast<size_t>(n));
    }
    if (request.find("\r\n\r\n") == std::string::npos) return false;
    const auto line_end = request.find("\r\n");
    const std::string prefix = "GET /auth/callback?";
    if (line_end == std::string::npos || request.compare(0, prefix.size(), prefix) != 0) return false;
    const auto target_end = request.find(' ', prefix.size());
    if (target_end == std::string::npos || target_end >= line_end) return false;
    const auto version = request.substr(target_end + 1, line_end - target_end - 1);
    if (version != "HTTP/1.1" && version != "HTTP/1.0") return false;
    std::istringstream query(request.substr(prefix.size(), target_end - prefix.size()));
    for (std::string item; std::getline(query, item, '&');) {
        const auto equal = item.find('=');
        if (equal == std::string::npos) return false;
        std::string name, value;
        if (!decode_form(item.substr(0, equal), name) ||
            !decode_form(item.substr(equal + 1), value) || name.empty() ||
            !fields.emplace(std::move(name), std::move(value)).second) return false;
    }
    return true;
}

void send_page(int fd, bool ok, const std::string& msg) {
    const std::string body = "<!doctype html><html lang=ru><meta charset=utf-8>"
        "<meta name=viewport content='width=device-width, initial-scale=1'>"
        "<title>OpenStation</title><main><h1>OpenStation</h1><p>" + msg + "</p></main></html>";
    const std::string status = ok ? "200 OK" : "400 Bad Request";
    const std::string response = "HTTP/1.1 " + status + "\r\nConnection: close\r\n"
        "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n"
        "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n"
        "Content-Type: text/html; charset=utf-8\r\nContent-Length: " +
        std::to_string(body.size()) + "\r\n\r\n" + body;
    size_t written = 0;
    while (written < response.size()) {
        const auto n = send(fd, response.data() + written, response.size() - written, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n <= 0) break;
        written += static_cast<size_t>(n);
    }
}

HttpClientResponse auth_post(const std::string& url, const Fields& fields, const std::atomic<bool>* abort = nullptr) {
    HttpClientRequest request;
    request.method = "POST";
    request.url = url;
    request.headers["Content-Type"] = "application/x-www-form-urlencoded";
    request.body = encode_form(fields);
    request.abort_req = abort;
    return chatgpt_http_request(std::move(request));
}

bool valid_bearer(const std::string& value) {
    return !value.empty() && value.size() <= 49152 &&
           value.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~+/=") == std::string::npos;
}

bool apply_tokens(const HttpClientResponse& response, Value& record, bool refreshing) {
    Value grant;
    if (response.status_code != 200 || response.truncated || !json::Parser::parse(response.body, grant) || !grant.is_obj()) return false;
    const std::string type = field(grant, "token_type");
    const std::string access = field(grant, "access_token");
    const std::string refresh = field(grant, "refresh_token");
    const std::string scope = grant.get("scope") ? field(grant, "scope") :
                              refreshing ? field(record, "scope") : "";
    const auto* expires = grant.get("expires_in");
    const int64_t seconds = expires && expires->is_num() ? expires->as_i64() : 0;
    if ((type != "Bearer" && type != "bearer") || !valid_bearer(access) ||
        (!refreshing && refresh.empty()) ||
        (grant.get("refresh_token") && !valid_bearer(refresh)) || seconds <= 0 || seconds > 7 * 86400) return false;
    std::istringstream scopes(scope);
    bool allowed = false;
    for (std::string s; scopes >> s;) {
        if (s == kRequiredScope) allowed = true;
    }
    if (!allowed) return false;
    record["access_token"] = Value::make_str(access);
    if (!refresh.empty()) record["refresh_token"] = Value::make_str(refresh);
    if (!refreshing) record["id_token"] = Value::make_str(field(grant, "id_token"));
    record["scope"] = Value::make_str(scope);
    record["expires_at"] = Value::make_num(std::time(nullptr) + seconds);
    return true;
}

}

ChatGptAuth::~ChatGptAuth() {
    stop_ = true;
    if (listener_.joinable()) listener_.join();
}

bool ChatGptAuth::start(const std::string& client_id, std::string& url, std::string& error) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ == "waiting") {
        error = "Вход уже начат. Завершите его в браузере.";
        return false;
    }
    if (listener_.joinable()) listener_.join();
    Value store;
    if (!load_store(store) || (!client_id.empty() && (!valid_client_id(client_id) || !account(store, client_id)))) {
        error = "Не удалось прочитать аккаунты ChatGPT.";
        return false;
    }
    std::string host_id = field(store, "host_id");
    if (host_id.empty()) {
        host_id = make_host_id();
        store["host_id"] = Value::make_str(host_id);
        if (host_id.empty() || !save_store(store)) {
            error = "Не удалось сохранить настройки входа.";
            return false;
        }
    }

    const std::string state = random_secret(), nonce = random_secret(), verifier = random_secret();
    uint8_t digest[32];
    if (state.empty() || nonce.empty() || verifier.empty() ||
        mbedtls_sha256(reinterpret_cast<const uint8_t*>(verifier.data()), verifier.size(), digest, 0) != 0) {
        error = "Не удалось подготовить вход.";
        return false;
    }
    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    const int reuse = 1;
    if (fd >= 0) setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(1455);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (fd < 0 || bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 || listen(fd, 4) != 0) {
        if (fd >= 0) close(fd);
        error = "Не удалось открыть порт входа на станции.";
        return false;
    }
    Fields params = {
        {"client_id", client_id.empty() ? kDynamic : client_id},
        {"ext_agent_host_id", host_id},
        {"response_type", "code"},
        {"redirect_uri", kCallback},
        {"scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"},
        {"resource", kResource},
        {"state", state},
        {"nonce", nonce},
        {"code_challenge_method", "S256"},
        {"code_challenge", base64url(digest, sizeof(digest))}
    };
    if (client_id.empty()) params["agent_name_hint"] = "OpenStation";
    state_ = "waiting";
    error_.clear();
    try {
        listener_ = std::thread(&ChatGptAuth::callback_loop, this, fd, state, nonce, verifier, client_id);
    } catch (...) {
        close(fd);
        state_ = "error";
        error = error_ = "Не удалось запустить вход.";
        return false;
    }
    url = std::string(kIssuer) + "/api/accounts/authorize?" + encode_form(params);
    return true;
}

void ChatGptAuth::callback_loop(int fd, std::string state, std::string nonce,
                                std::string verifier, std::string prior_client) {
    using Clock = std::chrono::steady_clock;
    const auto deadline = Clock::now() + std::chrono::minutes(5);
    std::string failure = "Время для входа истекло.";
    bool connected = false;
    while (!stop_ && Clock::now() < deadline) {
        pollfd ready{fd, POLLIN, 0};
        if (poll(&ready, 1, 250) <= 0) continue;
        const int browser = accept4(fd, nullptr, nullptr, SOCK_CLOEXEC);
        if (browser < 0) continue;
        Fields fields;
        if (!parse_callback(browser, fields) || fields["state"] != state) {
            send_page(browser, false, "Этот адрес не относится к текущему входу.");
            close(browser);
            continue;
        }
        std::string client_id = fields["client_id"];
        if (client_id.empty()) client_id = prior_client;
        if (fields.count("error")) {
            failure = "Пользователь не предоставил доступ к ChatGPT.";
        } else if (!valid_client_id(client_id) || (!prior_client.empty() && client_id != prior_client) ||
                   fields["code"].empty() || fields["code"].size() > 8192) {
            failure = "ChatGPT вернул неполный ответ о входе.";
        } else {
            const auto token = auth_post(std::string(kIssuer) + "/api/accounts/oauth/token",
                {{"grant_type", "authorization_code"}, {"client_id", client_id}, {"code", fields["code"]},
                 {"code_verifier", verifier}, {"redirect_uri", kCallback}, {"resource", kResource}}, &stop_);
            Value record = Value::make_obj();
            failure = "ChatGPT не подтвердил доступ через аккаунт.";
            if (apply_tokens(token, record, false) && !stop_) {
                HttpClientRequest request;
                request.url = std::string(kIssuer) + "/.well-known/jwks.json";
                request.abort_req = &stop_;
                const auto jwks = chatgpt_http_request(std::move(request));
                ChatGptIdentity identity;
                if (jwks.status_code == 200 && !jwks.truncated && verify_chatgpt_id_token(field(record, "id_token"), jwks.body, client_id, nonce,
                                                       std::time(nullptr), identity) && !stop_) {
                    std::lock_guard<std::mutex> lock(mutex_);
                    Value store;
                    if (load_store(store)) {
                        const auto* previous = account(store, client_id);
                        if ((previous && identity.subject == field(*previous, "subject")) ||
                            (!previous && prior_client.empty())) {
                            record["subject"] = Value::make_str(identity.subject);
                            record["email"] = Value::make_str(identity.email);
                            store["accounts"][client_id] = std::move(record);
                            store["active"] = Value::make_str(client_id);
                            connected = save_store(store);
                            if (!connected) failure = "Не удалось сохранить аккаунт на станции.";
                        } else {
                            failure = "Выбран другой аккаунт ChatGPT.";
                        }
                    }
                }
            }
        }
        send_page(browser, connected, connected ? "Аккаунт подключён. Это окно можно закрыть." : failure);
        close(browser);
        break;
    }
    close(fd);
    std::lock_guard<std::mutex> lock(mutex_);
    state_ = connected ? "connected" : "error";
    error_ = connected ? "" : failure;
}

Value ChatGptAuth::status() {
    std::lock_guard<std::mutex> lock(mutex_);
    Value store;
    const bool loaded = load_store(store);
    const std::string active = field(store, "active");
    const auto* current = account(store, active);
    Value result = Value::make_obj();
    result["state"] = Value::make_str(loaded ? state_ : "error");
    result["error"] = Value::make_str(loaded ? error_ : "Не удалось прочитать аккаунты ChatGPT.");
    result["active"] = Value::make_str(active);
    result["connected"] = Value::make_bool(current && valid_bearer(field(*current, "refresh_token")));
    auto accounts = Value::make_arr();
    const auto* records = store.get("accounts");
    if (records && records->is_obj()) {
        for (const auto& item : records->obj_val) {
            Value entry = Value::make_obj();
            entry["id"] = Value::make_str(item.first);
            entry["email"] = Value::make_str(field(item.second, "email"));
            entry["connected"] = Value::make_bool(valid_bearer(field(item.second, "refresh_token")));
            accounts.arr_val.push_back(std::move(entry));
        }
    }
    result["accounts"] = std::move(accounts);
    return result;
}

bool ChatGptAuth::select(const std::string& client_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    Value store;
    if (state_ == "waiting" || !valid_client_id(client_id) || !load_store(store)) return false;
    const auto* record = account(store, client_id);
    if (!record || !valid_bearer(field(*record, "refresh_token"))) return false;
    store["active"] = Value::make_str(client_id);
    if (!save_store(store)) return false;
    state_ = "connected";
    error_.clear();
    return true;
}

bool ChatGptAuth::access_token(std::string& token, std::string& error) {
    token.clear();
    std::lock_guard<std::mutex> lock(mutex_);
    Value store;
    if (!load_store(store)) {
        error = "Не удалось прочитать настройки входа ChatGPT.";
        return false;
    }
    const auto client_id = field(store, "active");
    const auto* current = account(store, client_id);
    if (!current || !valid_client_id(client_id) || !valid_bearer(field(*current, "refresh_token"))) {
        error = "Подключите аккаунт ChatGPT в настройках.";
        return false;
    }
    Value record = *current;
    if (expiry(record) <= std::time(nullptr) + 60 || !valid_bearer(field(record, "access_token"))) {
        const auto response = auth_post(std::string(kIssuer) + "/api/accounts/oauth/token",
            {{"grant_type", "refresh_token"}, {"client_id", client_id}, {"refresh_token", field(record, "refresh_token")},
             {"resource", kResource}});
        if (!apply_tokens(response, record, true)) {
            error = "Не удалось обновить вход в ChatGPT. Проверьте сеть или войдите снова.";
            return false;
        }
        store["accounts"][client_id] = record;
        if (!save_store(store)) {
            error = "Не удалось сохранить сеанс ChatGPT.";
            return false;
        }
    }
    token = field(record, "access_token");
    return true;
}

bool ChatGptAuth::sign_out(bool& revoked, std::string& error) {
    revoked = false;
    std::lock_guard<std::mutex> lock(mutex_);
    Value store;
    if (state_ == "waiting" || !load_store(store)) {
        error = "Не удалось выйти из ChatGPT. Завершите текущий вход и повторите.";
        return false;
    }
    const auto client_id = field(store, "active");
    const auto* current = account(store, client_id);
    if (!current || !valid_client_id(client_id)) {
        revoked = true;
        return true;
    }
    const std::string refresh = field(*current, "refresh_token");
    HttpClientRequest request;
    request.url = std::string(kIssuer) + "/.well-known/openid-configuration";
    const auto reply = chatgpt_http_request(std::move(request));
    Value config;
    if (reply.status_code == 200 && !reply.truncated && json::Parser::parse(reply.body, config)) {
        const std::string endpoint = field(config, "revocation_endpoint");
        if (endpoint.compare(0, sizeof(kIssuer) - 1, kIssuer) == 0 &&
            endpoint.size() >= sizeof(kIssuer) && endpoint[sizeof(kIssuer) - 1] == '/') {
            const Fields form{{"token", refresh}, {"token_type_hint", "refresh_token"}, {"client_id", client_id}};
            for (int attempt = 0; attempt < 2; ++attempt) {
                const int status = auth_post(endpoint, form).status_code;
                revoked = status == 200;
                if (status > 0 && status < 500) break;
                if (attempt == 0) std::this_thread::sleep_for(std::chrono::milliseconds(300));
            }
        }
    }
    auto retained = Value::make_obj();
    retained["email"] = Value::make_str(field(*current, "email"));
    retained["subject"] = Value::make_str(field(*current, "subject"));
    store["accounts"][client_id] = std::move(retained);
    store["active"] = Value::make_str("");
    if (!save_store(store)) {
        error = "Не удалось удалить сеанс ChatGPT со станции.";
        return false;
    }
    state_ = "idle";
    error_.clear();
    if (!revoked) error = "Вы вышли на станции. Отозвать доступ в ChatGPT не удалось.";
    return true;
}

}
