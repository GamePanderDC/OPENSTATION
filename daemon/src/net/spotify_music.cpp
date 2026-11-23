#include "net/spotify_music.hpp"

#include "net/http_client.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <mbedtls/base64.h>
#include <mbedtls/sha1.h>
#include <mbedtls/sha256.h>
#include <string_view>
#include <sys/random.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>

namespace openstation {
namespace {

using json::Value;
constexpr char kAccountPath[] = "/data/local/openstation/.spotify-music.json";
constexpr char kRedirect[] = "http://127.0.0.1:8080/api/music/spotify/callback";
constexpr char kDeviceName[] = "OpenStation Max"; // Same name used by spotify_receiver.sh.
constexpr char kScope[] = "user-read-playback-state user-modify-playback-state "
                          "playlist-read-private playlist-read-collaborative user-library-read user-library-modify";

const Value& field(const Value& object, const char* key) {
    static const Value empty;
    const auto* value = object.get(key);
    return value ? *value : empty;
}

std::string str(const Value& object, const char* key) {
    return field(object, key).as_str();
}

HttpResponse reply(Value value, int status = 200) {
    return HttpResponse::json(value.dump(), status);
}

HttpResponse error(const char* message, int status) {
    Value value = Value::make_obj();
    value["error"] = Value::make_str(message);
    return reply(std::move(value), status);
}

HttpResponse ok() {
    return HttpResponse::json("{\"ok\":true}");
}

HttpResponse api_error(int status) {
    switch (status) {
        case 401: return error("Подключите аккаунт Spotify заново.", 401);
        case 403: return error("Spotify ограничил доступ. Проверьте Premium и доступ аккаунта в настройках приложения Spotify for Developers.", 403);
        case 404: return error("Устройство или запись недоступны в Spotify.", 404);
        case 429: return error("Spotify временно ограничил запросы. Повторите чуть позже.", 429);
        default: return error("Spotify не ответил. Проверьте интернет на станции.", 502);
    }
}

bool hex_string(std::string_view value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    });
}

bool spotify_id(std::string_view value) {
    return value.size() == 22 && std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    });
}

std::string device_id() {
    unsigned char digest[20];
    if (mbedtls_sha1(reinterpret_cast<const unsigned char*>(kDeviceName), sizeof(kDeviceName) - 1, digest) != 0)
        return {};
    char hex[41] {};
    for (size_t i = 0; i < sizeof(digest); ++i)
        snprintf(hex + i * 2, 3, "%02x", static_cast<unsigned>(digest[i]));
    return hex;
}

std::string base64url(const unsigned char* bytes, size_t count) {
    unsigned char data[128] {};
    size_t size = 0;
    if (mbedtls_base64_encode(data, sizeof(data), &size, bytes, count) != 0) return {};
    std::string result(reinterpret_cast<const char*>(data), size);
    std::replace(result.begin(), result.end(), '+', '-');
    std::replace(result.begin(), result.end(), '/', '_');
    return result.substr(0, result.find('='));
}

std::string random_string() {
    unsigned char bytes[32];
    if (getrandom(bytes, sizeof(bytes), 0) != static_cast<ssize_t>(sizeof(bytes))) return {};
    return base64url(bytes, sizeof(bytes));
}

bool safe_post(const HttpRequest& request) {
    return (request.content_type == "application/json" || request.content_type.rfind("application/json;", 0) == 0) &&
           (request.origin.empty() || request.origin == "http://" + request.host || request.origin == "https://" + request.host);
}

std::string query(const std::string& raw, std::string_view key) {
    size_t start = 0;
    while (start < raw.size()) {
        const size_t next = raw.find('&', start);
        const std::string_view param(raw.data() + start, std::min(next, raw.size()) - start);
        if (param.size() > key.size() && param.substr(0, key.size()) == key && param[key.size()] == '=') {
            // Spotify's PKCE parameters use the unreserved URI alphabet.
            return std::string(param.substr(key.size() + 1));
        }
        if (next == std::string::npos) break;
        start = next + 1;
    }
    return {};
}

std::string title(const Value& item) {
    auto value = str(item, "name");
    return value.empty() ? "Без названия" : value;
}

std::string image(const Value& item) {
    const auto& images = field(item, "images").arr_val;
    if (images.empty()) return {};
    const auto url = str(images.front(), "url");
    return url.rfind("https://", 0) == 0 ? url : "";
}

Value music_item(const Value& item, const std::string& fallback_kind = "") {
    auto kind = str(item, "type");
    if (kind.empty()) kind = fallback_kind;
    if (kind != "track" && kind != "album" && kind != "playlist" && kind != "episode")
        return {};
    auto id = str(item, "id");
    if (!spotify_id(id) || field(item, "is_local").as_bool()) return {};
    Value result = Value::make_obj();
    result["id"] = Value::make_str(id);
    result["kind"] = Value::make_str(kind);
    result["title"] = Value::make_str(title(item));
    std::string detail;
    for (const auto& artist : field(item, "artists").arr_val) {
        if (!detail.empty()) detail += ", ";
        detail += title(artist);
    }
    if (detail.empty() && kind == "playlist") detail = str(field(item, "owner"), "display_name");
    if (detail.empty() && kind == "episode") detail = str(field(item, "show"), "name");
    result["detail"] = Value::make_str(detail);
    auto cover = image(item);
    if (cover.empty()) cover = image(field(item, "album"));
    result["cover"] = Value::make_str(cover);
    result["durationMs"] = Value::make_int(field(item, "duration_ms").as_i64());
    result["link"] = Value::make_str("https://open.spotify.com/" + kind + "/" + id);
    result["playable"] = Value::make_bool(field(item, "is_playable").as_bool(true));
    return result;
}

} // namespace

SpotifyMusic::SpotifyMusic(const std::atomic<bool>& shutdown) : shutdown_(shutdown) {
    const int fd = open(kAccountPath, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return;
    struct stat info {};
    std::array<char, 8192> data {};
    const ssize_t size = fstat(fd, &info) == 0 && S_ISREG(info.st_mode) && info.st_uid == 0 &&
                         (info.st_mode & 0077) == 0 ? read(fd, data.data(), data.size()) : -1;
    close(fd);
    Value state;
    if (size <= 0 || size == static_cast<ssize_t>(data.size()) ||
        !json::Parser::parse(std::string(data.data(), static_cast<size_t>(size)), state)) return;
    if (str(state, "provider") == "spotify") provider_ = "spotify";
    const auto client = str(state, "clientId");
    if (client.size() == 32 && hex_string(client)) {
        client_ = client;
        refresh_ = str(state, "refresh");
    }
}

bool SpotifyMusic::save(const std::string& provider, const std::string& client, const std::string& refresh) const {
    Value state = Value::make_obj();
    state["provider"] = Value::make_str(provider);
    state["clientId"] = Value::make_str(client);
    state["refresh"] = Value::make_str(refresh);
    const auto data = state.dump();
    char temp[] = "/data/local/openstation/.spotify-music-XXXXXX";
    const int fd = mkstemp(temp);
    if (fd < 0) return false;
    size_t sent = 0;
    while (sent < data.size()) {
        const ssize_t count = write(fd, data.data() + sent, data.size() - sent);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        sent += static_cast<size_t>(count);
    }
    const bool written = sent == data.size() && fsync(fd) == 0;
    const bool closed = close(fd) == 0;
    if (written && closed && rename(temp, kAccountPath) == 0) return true;
    unlink(temp);
    return false;
}

HttpResponse SpotifyMusic::handle(const HttpRequest& request) {
    if (request.method == "GET" && request.path == "/api/music/spotify/status") {
        std::lock_guard<std::mutex> lock(mutex_);
        return status();
    }
    if (request.method == "GET" && request.path == "/api/music/spotify/callback") {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto result = finish(query(request.query, "state"), query(request.query, "code"));
        const char* html = result.status == 200
            ? "<!doctype html><meta charset=utf-8><title>OpenStation · Spotify</title><p>Spotify подключён. Можно вернуться в OpenStation.</p><p><a href='/spotify.html'>Открыть плеер</a></p>"
            : "<!doctype html><meta charset=utf-8><title>OpenStation · Spotify</title><p>Вход не завершён. Вернитесь в плеер и повторите вход.</p>";
        const std::string body(html);
        auto response = HttpResponse::bytes({body.begin(), body.end()}, "text/html; charset=utf-8", result.status);
        return response;
    }
    if (request.method == "GET" && request.path == "/api/music/provider") {
        std::lock_guard<std::mutex> lock(mutex_);
        Value output = Value::make_obj();
        output["provider"] = Value::make_str(provider_);
        return reply(std::move(output));
    }
    if (request.method != "POST") return error("Неизвестный запрос к Spotify.", 404);
    Value input;
    if (!safe_post(request) || request.body.size() > 8192 ||
        !json::Parser::parse(request.body, input) || !input.is_obj())
        return error("Откройте веб-пульт станции напрямую.", 400);
    std::lock_guard<std::mutex> lock(mutex_);
    if (request.path == "/api/music/provider") {
        const auto next = str(input, "provider");
        if (next != "yandex" && next != "spotify") return error("Неизвестный музыкальный сервис.", 400);
        if (next != provider_) {
            if (!save(next, client_, refresh_)) return error("Не удалось сохранить выбор сервиса.", 500);
            provider_ = next;
        }
        return ok();
    }
    if (request.path == "/api/music/spotify/login") return begin(input);
    if (request.path == "/api/music/spotify/finish") return finish(str(input, "state"), str(input, "code"));
    if (request.path == "/api/music/spotify/browse") return browse(input);
    if (request.path == "/api/music/spotify/command") return command(input);
    if (request.path == "/api/music/spotify/logout") {
        if (!save(provider_, client_, "")) return error("Не удалось сохранить выход из аккаунта.", 500);
        refresh_.clear();
        access_.clear();
        pending_state_.clear();
        pending_verifier_.clear();
        playback_ = {};
        checked_track_.clear();
        saved_track_.clear();
        status_until_ = {};
        return ok();
    }
    return error("Неизвестный запрос к Spotify.", 404);
}

HttpResponse SpotifyMusic::begin(const Value& input) {
    auto client = str(input, "clientId");
    if (client.empty()) client = client_;
    if (client.size() != 32 || !hex_string(client)) return error("Укажите Client ID из Spotify for Developers.", 400);
    auto state = random_string();
    auto verifier = random_string();
    unsigned char digest[32];
    if (state.empty() || verifier.empty() ||
        mbedtls_sha256(reinterpret_cast<const unsigned char*>(verifier.data()), verifier.size(), digest, 0) != 0)
        return error("Не удалось подготовить вход в Spotify.", 500);
    pending_client_ = client;
    pending_state_ = state;
    pending_verifier_ = verifier;
    pending_until_ = Clock::now() + std::chrono::minutes(10);
    Value output = Value::make_obj();
    output["url"] = Value::make_str(std::string("https://accounts.spotify.com/authorize?response_type=code&client_id=") +
        client + "&code_challenge_method=S256&code_challenge=" + base64url(digest, sizeof(digest)) +
        "&scope=" + url_encode(kScope) + "&redirect_uri=" + url_encode(kRedirect) + "&state=" + state);
    return reply(std::move(output));
}

HttpResponse SpotifyMusic::finish(const std::string& state, const std::string& code) {
    if (pending_state_.empty() || state != pending_state_ || Clock::now() >= pending_until_)
        return error("Ссылка входа устарела. Начните вход заново.", 400);
    if (code.empty() || code.size() > 1024) return error("В ссылке отсутствует код входа Spotify.", 400);
    const auto result = exchange(pending_client_, "grant_type=authorization_code&code=" + url_encode(code) +
        "&redirect_uri=" + url_encode(kRedirect) + "&code_verifier=" + pending_verifier_, false);
    pending_state_.clear();
    pending_verifier_.clear();
    if (result == 400 || result == 401) return error("Spotify отклонил код. Проверьте Client ID и адрес перенаправления.", 400);
    if (result != 200) return api_error(result);
    status_until_ = {};
    checked_track_.clear();
    saved_track_.clear();
    return ok();
}

int SpotifyMusic::exchange(const std::string& client, const std::string& form, bool refresh) {
    HttpClientRequest request;
    request.url = "https://accounts.spotify.com/api/token";
    request.method = "POST";
    request.headers["Content-Type"] = "application/x-www-form-urlencoded";
    request.body = form + "&client_id=" + client;
    request.max_redirects = 0;
    request.max_body_bytes = 16384;
    request.abort_req = &shutdown_;
    const auto response = http_perform(request);
    Value result;
    if (response.truncated || !json::Parser::parse(response.body, result))
        return response.status_code == 200 ? 502 : response.status_code;
    if (response.status_code != 200) {
        if (refresh && response.status_code == 400 && str(result, "error") == "invalid_grant") {
            if (save(provider_, client_, "")) refresh_.clear();
            access_.clear();
            return 401;
        }
        return response.status_code;
    }
    auto access = str(result, "access_token");
    auto updated_refresh = str(result, "refresh_token");
    if (updated_refresh.empty() && refresh) updated_refresh = refresh_;
    const int lifetime = field(result, "expires_in").as_int(0);
    if (access.empty() || updated_refresh.empty() || lifetime <= 0) return 502;
    if ((client != client_ || updated_refresh != refresh_) && !save(provider_, client, updated_refresh))
        return 500;
    client_ = client;
    refresh_ = std::move(updated_refresh);
    access_ = std::move(access);
    access_until_ = Clock::now() + std::chrono::seconds(std::clamp(lifetime - 30, 1, 86400));
    return 200;
}

int SpotifyMusic::api(const std::string& path, Value& output, const char* method, const std::string& body) {
    if (refresh_.empty()) return 401;
    if (Clock::now() < retry_at_) return 429;
    for (int attempt = 0; attempt < 2; ++attempt) {
        if (access_.empty() || Clock::now() >= access_until_) {
            int result = exchange(client_, "grant_type=refresh_token&refresh_token=" + url_encode(refresh_), true);
            if (result != 200) return result;
        }
        HttpClientRequest request;
        request.method = method;
        request.url = "https://api.spotify.com/v1" + path;
        request.headers["Authorization"] = "Bearer " + access_;
        if (!body.empty()) request.headers["Content-Type"] = "application/json";
        request.body = body;
        request.max_redirects = 0;
        request.max_body_bytes = 3 * 1024 * 1024;
        request.abort_req = &shutdown_;
        const auto response = http_perform(request);
        if (response.status_code == 401 && attempt == 0) {
            access_.clear();
            continue;
        }
        if (response.status_code == 429) {
            const auto it = response.headers.find("retry-after");
            const int seconds = it == response.headers.end() ? 30 : atoi(it->second.c_str());
            retry_at_ = Clock::now() + std::chrono::seconds(std::clamp(seconds, 2, 3600));
        }
        if (response.status_code == 200) {
            if (response.truncated) return 502;
            if (std::string_view(method) == "GET") {
                if (response.body.empty() || !json::Parser::parse(response.body, output)) return 502;
            } else if (!response.body.empty() && (response.body.front() == '{' || response.body.front() == '[')) {
                (void)json::Parser::parse(response.body, output);
            }
        }
        return response.status_code;
    }
    return 401;
}

HttpResponse SpotifyMusic::status() {
    Value output = Value::make_obj();
    output["clientConfigured"] = Value::make_bool(!client_.empty());
    output["redirect"] = Value::make_str(kRedirect);
    if (!refresh_.empty() && Clock::now() >= status_until_) {
        Value playback;
        playback_status_ = api("/me/player?additional_types=track%2Cepisode", playback);
        playback_ = {};
        const auto& device = field(playback, "device");
        const auto id = device_id();
        std::string dev_name = str(device, "name");
        std::string dev_id = str(device, "id");
        bool is_station = (dev_name.find("OpenStation") != std::string::npos) ||
                          (!id.empty() && dev_id == id) ||
                          field(device, "is_active").as_bool();
        if (playback_status_ == 200 && is_station) {
            playback_ = music_item(field(playback, "item"));
            if (!playback_.is_null()) {
                playback_["playing"] = Value::make_bool(field(playback, "is_playing").as_bool());
                playback_["positionMs"] = Value::make_int(field(playback, "progress_ms").as_i64());
                if (str(playback_, "kind") == "track") {
                    const auto track = str(playback_, "id");
                    if (checked_track_ != track) {
                        Value savings;
                        if (api("/me/library/contains?uris=" + url_encode("spotify:track:" + track), savings) == 200 &&
                            savings.is_arr() && !savings.arr_val.empty() && savings.arr_val.front().is_bool()) {
                            checked_track_ = track;
                            saved_track_ = savings.arr_val.front().as_bool() ? track : "";
                        }
                    }
                    if (checked_track_ == track) playback_["saved"] = Value::make_bool(saved_track_ == track);
                }
            }
        }
        status_until_ = Clock::now() + std::chrono::milliseconds(playback_status_ == 200 ? 1500 : 3500);
    }
    output["linked"] = Value::make_bool(!refresh_.empty());
    output["playback"] = playback_;
    if (!refresh_.empty() && playback_status_ != 200 && playback_status_ != 204)
        output["spotifyStatus"] = Value::make_int(playback_status_);
    return reply(std::move(output));
}

HttpResponse SpotifyMusic::browse(const Value& input) {
    const auto section = str(input, "section");
    auto kind = str(input, "kind");
    const auto id = str(input, "id");
    const int offset = std::clamp(field(input, "offset").as_int(), 0, section == "search" ? 1000 : 100000);
    const int limit = section == "search" ? 10 : 24;
    const auto paging = "limit=" + std::to_string(limit) + "&offset=" + std::to_string(offset);
    std::string path;
    bool wrapped_items = false;
    if (section == "search") {
        const auto term = str(input, "query");
        if (term.empty() || term.size() > 480 || (kind != "track" && kind != "album" && kind != "playlist"))
            return error("Укажите трек, исполнителя или название.", 400);
        path = "/search?type=" + kind + "&q=" + url_encode(term) + "&" + paging;
    } else if (section == "playlists") {
        kind = "playlist";
        path = "/me/playlists?" + paging;
    } else if (section == "saved") {
        kind = "track";
        wrapped_items = true;
        path = "/me/tracks?" + paging;
    } else if (section == "album" && spotify_id(id)) {
        kind = "track";
        path = "/albums/" + id + "/tracks?" + paging;
    } else if (section == "playlist" && spotify_id(id)) {
        kind = "track";
        wrapped_items = true;
        path = "/playlists/" + id + "/items?" + paging;
    } else {
        return error("Неизвестный раздел Spotify.", 400);
    }
    Value result;
    int response = api(path, result);
    // Some existing Extended Quota applications still expose the old playlist endpoint.
    if (response == 404 && section == "playlist")
        response = api("/playlists/" + id + "/tracks?" + paging, result);
    if (response == 403 && section == "playlist")
        return error("Spotify показывает состав только своих и совместных плейлистов. Этот плейлист можно включить целиком.", 403);
    if (response != 200) return api_error(response);
    const Value& page = section == "search" ? field(result, (kind + "s").c_str()) : result;
    Value output = Value::make_obj();
    output["items"] = Value::make_arr();
    for (const auto& entry : field(page, "items").arr_val) {
        const auto& modern = field(entry, "item");
        const auto& item = wrapped_items ? (modern.is_obj() ? modern : field(entry, "track")) : entry;
        auto row = music_item(item, kind);
        if (!row.is_null()) output["items"].push(std::move(row));
    }
    output["total"] = Value::make_int(field(page, "total").as_i64(-1));
    const bool has_more = !str(page, "next").empty() && (section != "search" || offset < 1000);
    output["nextOffset"] = has_more ? Value::make_int(offset + limit) : Value::make_null();
    return reply(std::move(output));
}

HttpResponse SpotifyMusic::command(const Value& input) {
    const auto action = str(input, "action");
    const auto kind = str(input, "kind");
    const auto id = str(input, "id");
    const auto context_kind = str(input, "contextKind");
    const auto context_id = str(input, "contextId");
    const bool track = kind == "track" || kind == "episode";
    const bool context = kind == "album" || kind == "playlist";
    Value result;
    if ((action == "save" || action == "unsave") && kind == "track" && spotify_id(id)) {
        const int saved = api("/me/library?uris=" + url_encode("spotify:track:" + id),
                              result, action == "save" ? "PUT" : "DELETE");
        status_until_ = {};
        if (saved == 200 || saved == 204) {
            checked_track_ = id;
            saved_track_ = action == "save" ? id : "";
        }
        return saved == 200 || saved == 204 ? ok() : api_error(saved);
    }
    const char* method = "PUT";
    std::string path;
    Value payload = Value::make_obj();
    if (action == "play" && !id.empty() && spotify_id(id) && (track || context)) {
        path = "/play?";
        if (context) {
            payload["context_uri"] = Value::make_str("spotify:" + kind + ":" + id);
        } else if ((context_kind == "playlist" || context_kind == "album") && spotify_id(context_id)) {
            payload["context_uri"] = Value::make_str("spotify:" + context_kind + ":" + context_id);
            payload["offset"] = Value::make_obj();
            payload["offset"]["uri"] = Value::make_str("spotify:" + kind + ":" + id);
        } else {
            payload["uris"] = Value::make_arr();
            payload["uris"].push(Value::make_str("spotify:" + kind + ":" + id));
            for (const auto& next : field(input, "nextTracks").arr_val) {
                if (payload["uris"].arr_val.size() >= 50) break;
                const auto next_id = next.as_str();
                if (spotify_id(next_id)) payload["uris"].push(Value::make_str("spotify:track:" + next_id));
            }
        }
    } else if (action == "resume" || (action == "play" && id.empty())) {
        path = "/play?";
    } else if (action == "pause") {
        path = "/pause?";
    } else if (action == "play_pause" || action == "toggle") {
        if (field(playback_, "playing").as_bool()) {
            path = "/pause?";
        } else {
            path = "/play?";
        }
    } else if (action == "next" || action == "previous" || action == "prev") {
        path = "/" + std::string(action == "prev" ? "previous" : action) + "?";
        method = "POST";
    } else if (action == "seek" && field(input, "positionMs").is_num()) {
        const auto position = std::clamp<int64_t>(field(input, "positionMs").as_i64(), 0, 86400000);
        path = "/seek?position_ms=" + std::to_string(position) + "&";
    } else if (action == "queue" && track && spotify_id(id)) {
        path = "/queue?uri=" + url_encode("spotify:" + kind + ":" + id) + "&";
        method = "POST";
    } else {
        return error("Неизвестная команда Spotify.", 400);
    }
    std::string target = resolve_target_device();
    if (target.empty()) {
        (void)::system("/system/bin/am start -n com.spotify.tv.android/.SpotifyTVActivity >/dev/null 2>&1");
        std::this_thread::sleep_for(std::chrono::milliseconds(1200));
        target = resolve_target_device();
    }
    if (target.empty()) {
        return error("Spotify Connect на станции не найден. Запустите Spotify на телевизоре или телефоне.", 409);
    }
    std::string full_path = "/me/player" + path;
    if (full_path.back() == '?' || full_path.back() == '&') {
        full_path += "device_id=" + target;
    } else {
        full_path += (full_path.find('?') == std::string::npos ? "?" : "&") + std::string("device_id=") + target;
    }
    const int response = api(full_path, result, method,
                             action == "play" ? payload.dump() : "");
    fprintf(stderr, "[Spotify] command: action=%s, path=%s, target=%s, code=%d\n", action.c_str(), full_path.c_str(), target.c_str(), response);
    fflush(stderr);
    status_until_ = {};
    return response == 200 || response == 204 ? ok() : api_error(response);
}

std::string SpotifyMusic::resolve_target_device() {
    Value result;
    const int devices = api("/me/player/devices", result);
    if (devices != 200) return {};
    const auto& list = field(result, "devices").arr_val;
    // 1. Prefer TV device named OpenStation
    for (const auto& item : list) {
        if (field(item, "is_restricted").as_bool()) continue;
        std::string name = str(item, "name");
        std::string type = str(item, "type");
        if (name.find("OpenStation") != std::string::npos && type == "TV") {
            return str(item, "id");
        }
    }
    // 2. Any device named OpenStation
    for (const auto& item : list) {
        if (field(item, "is_restricted").as_bool()) continue;
        std::string name = str(item, "name");
        if (name.find("OpenStation") != std::string::npos) {
            return str(item, "id");
        }
    }
    // 3. Active device
    for (const auto& item : list) {
        if (field(item, "is_restricted").as_bool()) continue;
        if (field(item, "is_active").as_bool()) {
            return str(item, "id");
        }
    }
    // 4. Default SHA1 device_id()
    const auto def_id = device_id();
    for (const auto& item : list) {
        if (field(item, "is_restricted").as_bool()) continue;
        if (str(item, "id") == def_id) {
            return str(item, "id");
        }
    }
    // 5. First non-restricted device
    for (const auto& item : list) {
        if (!field(item, "is_restricted").as_bool()) {
            return str(item, "id");
        }
    }
    return {};
}

void SpotifyMusic::pause_if_playing() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!field(playback_, "playing").as_bool()) return;
    std::string target = resolve_target_device();
    Value response;
    std::string path = target.empty() ? "/me/player/pause" : ("/me/player/pause?device_id=" + target);
    const int code = api(path, response, "PUT");
    if (code == 200 || code == 204) {
        playback_["playing"] = Value::make_bool(false);
        status_until_ = {};
    }
}

bool SpotifyMusic::is_linked() {
    std::lock_guard<std::mutex> lock(mutex_);
    return !refresh_.empty();
}

std::string SpotifyMusic::provider() const {
    std::lock_guard<std::mutex> lock(const_cast<std::mutex&>(mutex_));
    return provider_;
}

json::Value SpotifyMusic::poll_playback() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (refresh_.empty()) return {};
    if (Clock::now() < retry_at_) return playback_;
    Value playback;
    playback_status_ = api("/me/player?additional_types=track%2Cepisode", playback);
    if (playback_status_ == 200) {
        const auto& device = field(playback, "device");
        const auto id = device_id();
        std::string dev_name = str(device, "name");
        std::string dev_id = str(device, "id");
        bool is_station = (dev_name.find("OpenStation") != std::string::npos) ||
                          (!id.empty() && dev_id == id) ||
                          field(device, "is_active").as_bool();
        if (is_station) {
            playback_ = music_item(field(playback, "item"));
            if (!playback_.is_null()) {
                playback_["playing"] = Value::make_bool(field(playback, "is_playing").as_bool());
                playback_["positionMs"] = Value::make_int(field(playback, "progress_ms").as_i64());
                if (checked_track_ == str(playback_, "id")) {
                    playback_["saved"] = Value::make_bool(saved_track_ == checked_track_);
                }
            }
        } else {
            playback_ = {};
        }
    } else if (playback_status_ == 204) {
        playback_ = {};
    }
    return playback_;
}

bool SpotifyMusic::play_query(const std::string& query) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (refresh_.empty()) return false;
    if (query.empty()) {
        Value cmd = Value::make_obj();
        cmd["action"] = Value::make_str("resume");
        auto resp = command(cmd);
        if (resp.status == 200 || resp.status == 204) return true;
        Value saved_tracks;
        if (api("/me/tracks?limit=1", saved_tracks) == 200) {
            const auto& items = field(saved_tracks, "items").arr_val;
            if (!items.empty()) {
                const auto& track = field(items[0], "track");
                std::string tid = str(track, "id");
                if (!tid.empty()) {
                    Value play_cmd = Value::make_obj();
                    play_cmd["action"] = Value::make_str("play");
                    play_cmd["kind"] = Value::make_str("track");
                    play_cmd["id"] = Value::make_str(tid);
                    auto play_resp = command(play_cmd);
                    return play_resp.status == 200 || play_resp.status == 204;
                }
            }
        }
        return false;
    }
    Value search_res;
    int code = api("/search?q=" + url_encode(query) + "&type=track&limit=1", search_res);
    if (code != 200) return false;
    const auto& tracks = field(search_res, "tracks");
    const auto& items = field(tracks, "items").arr_val;
    if (items.empty()) return false;
    std::string track_id = str(items[0], "id");
    if (track_id.empty()) return false;

    Value cmd = Value::make_obj();
    cmd["action"] = Value::make_str("play");
    cmd["kind"] = Value::make_str("track");
    cmd["id"] = Value::make_str(track_id);
    auto resp = command(cmd);
    return resp.status == 200 || resp.status == 204;
}

bool SpotifyMusic::execute_simple_command(const std::string& action) {
    std::lock_guard<std::mutex> lock(mutex_);
    Value cmd = Value::make_obj();
    cmd["action"] = Value::make_str(action);
    auto resp = command(cmd);
    return resp.status == 200 || resp.status == 204;
}

} // namespace openstation
