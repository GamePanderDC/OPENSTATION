#include "wyoming/wyoming_server.hpp"
#include <arpa/inet.h>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstring>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

using namespace openstation;

static std::string event(int fd) {
    std::string line;
    char c;
    while (recv(fd, &c, 1, 0) == 1 && c != '\n') line += c;
    assert(!line.empty());
    json::Value header;
    assert(json::Parser::parse(line, header));
    int n = header.get("data_length") ? header.get("data_length")->as_int() : 0;
    int payload = header.get("payload_length") ? header.get("payload_length")->as_int() : 0;
    std::string body(n + payload, '\0');
    size_t got = 0;
    while (got < body.size()) {
        auto r = recv(fd, &body[got], body.size() - got, 0);
        assert(r > 0);
        got += r;
    }
    return header.get("type")->as_str() + ":" + body.substr(0, n);
}

static void send_type(int fd, const char* type) {
    std::string line = std::string("{\"type\":\"") + type + "\"}\n";
    assert(send(fd, line.data(), line.size(), 0) == static_cast<ssize_t>(line.size()));
}

int main() {
    WyomingConfig cfg;
    cfg.enabled = true;
    cfg.always_stream = true;
    cfg.port = 29763;
    WyomingServer server;
    std::atomic<int> tts_frames{0};
    assert(server.start(cfg, [&](const int16_t*, size_t frames, int rate, int channels) {
        assert(rate == 22050 && channels == 1);
        tts_frames.fetch_add(static_cast<int>(frames));
    }, {}));
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(cfg.port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    assert(connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
    timeval timeout{0, 200000};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    int16_t samples[320]{};
    server.feed_audio_16k(samples, 320, 1000);
    char c;
    assert(recv(fd, &c, 1, 0) < 0); // discovery must not receive unsolicited audio
    send_type(fd, "describe");
    assert(event(fd).find("info:") == 0);
    send_type(fd, "run-satellite");
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    server.feed_audio_16k(samples, 320, 1000);
    assert(event(fd).find("\"start_stage\":\"wake\"") != std::string::npos);
    assert(event(fd).find("audio-start:") == 0);
    assert(event(fd).find("audio-chunk:") == 0);
    send_type(fd, "audio-start");
    std::string tts = "{\"type\":\"audio-chunk\",\"data\":{\"rate\":22050,\"channels\":1},\"payload_length\":4}\n";
    tts.append(4, '\0');
    assert(send(fd, tts.data(), tts.size(), 0) == static_cast<ssize_t>(tts.size()));
    assert(send(fd, tts.data(), tts.size(), 0) == static_cast<ssize_t>(tts.size()));
    send_type(fd, "audio-stop");
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    assert(tts_frames.load() == 4);
    send_type(fd, "pause-satellite");
    assert(event(fd).find("audio-stop:") == 0);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    server.feed_audio_16k(samples, 320, 1000);
    assert(recv(fd, &c, 1, 0) < 0);
    cfg.always_stream = false;
    server.update_config(cfg);
    send_type(fd, "run-satellite");
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    server.trigger_button_session();
    assert(event(fd).find("\"start_stage\":\"asr\"") != std::string::npos);
    assert(event(fd).find("audio-start:") == 0);
    close(fd);
    server.stop();
}
