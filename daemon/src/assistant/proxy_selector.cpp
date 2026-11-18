#include "assistant/proxy_selector.hpp"
#include "assistant/smart_dns.hpp"
#include "assistant/tls_ws_client.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <arpa/inet.h>
#include <chrono>
#include <mutex>
#include <netdb.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <thread>
#include <vector>

namespace openstation {
namespace {
std::mutex cache_mu;
std::string cache_host, cache_address;
std::chrono::steady_clock::time_point cache_until;
std::string failed_host, failed_address;
std::chrono::steady_clock::time_point failed_until;

std::vector<std::string> query_smart_dns(const std::string& hostname) {
    if (hostname != "generativelanguage.googleapis.com" && hostname != "auth.openai.com" &&
        hostname != "api.openai.com") return {};
    std::vector<unsigned char> question;
    for (size_t begin = 0; begin < hostname.size();) {
        const size_t end = hostname.find('.', begin);
        const size_t len = (end == std::string::npos ? hostname.size() : end) - begin;
        if (len == 0 || len > 63) return {};
        question.push_back(static_cast<unsigned char>(len));
        question.insert(question.end(), hostname.begin() + static_cast<ptrdiff_t>(begin),
                        hostname.begin() + static_cast<ptrdiff_t>(begin + len));
        begin += len + 1;
    }
    question.insert(question.end(), {0, 0, 1, 0, 1});
    const auto ticks = std::chrono::steady_clock::now().time_since_epoch().count();
    const uint16_t id = static_cast<uint16_t>(ticks ^ static_cast<decltype(ticks)>(getpid()));
    std::vector<unsigned char> packet = {static_cast<unsigned char>(id >> 8), static_cast<unsigned char>(id),
                                          1,0,0,1,0,0,0,0,0,0};
    packet.insert(packet.end(), question.begin(), question.end());
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return {};
    sockaddr_in server{};
    server.sin_family = AF_INET;
    server.sin_port = htons(53);
    inet_pton(AF_INET, "153.80.200.170", &server.sin_addr);
    std::vector<std::string> answers;
    if (connect(fd, reinterpret_cast<sockaddr*>(&server), sizeof(server)) == 0 &&
        send(fd, packet.data(), packet.size(), 0) == static_cast<ssize_t>(packet.size())) {
        pollfd pfd{fd, POLLIN, 0};
        if (poll(&pfd, 1, 2000) > 0 && (pfd.revents & POLLIN)) {
            unsigned char buf[4096];
            ssize_t n = recv(fd, buf, sizeof(buf), 0);
            if (n > 0) answers = parse_smart_dns_reply(buf, static_cast<size_t>(n), id, question);
        }
    }
    close(fd);
    return answers;
}
}

std::vector<std::string> resolve_smart_dns(const std::string& hostname) {
    return query_smart_dns(hostname);
}

void invalidate_proxy_selection(const std::string& host) {
    std::lock_guard<std::mutex> lock(cache_mu);
    if (cache_host == host && !cache_address.empty()) {
        failed_host = host;
        failed_address = cache_address;
        failed_until = std::chrono::steady_clock::now() + std::chrono::seconds(30);
        cache_host.clear();
        cache_address.clear();
    }
}

std::string select_fast_proxy(const std::string& host, const std::string& ca_path,
                              bool force_refresh, const std::string& exclude) {
    if (host.empty()) return {};
    unsigned char numeric[sizeof(in6_addr)];
    if (inet_pton(AF_INET, host.c_str(), numeric) == 1 ||
        inet_pton(AF_INET6, host.c_str(), numeric) == 1) return host == exclude ? "" : host;
    std::string failed;
    {
        std::lock_guard<std::mutex> lock(cache_mu);
        if (!force_refresh && cache_host == host && !cache_address.empty() &&
            std::chrono::steady_clock::now() < cache_until) return cache_address;
        if (failed_host == host && std::chrono::steady_clock::now() < failed_until) failed = failed_address;
    }
    std::vector<std::string> addresses;
    if (host == "smart-auto") {
        addresses = query_smart_dns("generativelanguage.googleapis.com");
    } else {
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* results = nullptr;
    if (getaddrinfo(host.c_str(), "443", &hints, &results) != 0 || !results) return {};
    for (auto* p = results; p && addresses.size() < 8; p = p->ai_next) {
        char ip[INET6_ADDRSTRLEN]{};
        const void* src = nullptr;
        if (p->ai_family == AF_INET) src = &reinterpret_cast<sockaddr_in*>(p->ai_addr)->sin_addr;
        else if (p->ai_family == AF_INET6) src = &reinterpret_cast<sockaddr_in6*>(p->ai_addr)->sin6_addr;
        if (src && inet_ntop(p->ai_family, src, ip, sizeof(ip)) && ip != exclude &&
            std::find(addresses.begin(), addresses.end(), ip) == addresses.end()) addresses.emplace_back(ip);
    }
    freeaddrinfo(results);
    }
    addresses.erase(std::remove(addresses.begin(), addresses.end(), exclude), addresses.end());
    if (!failed.empty()) addresses.erase(std::remove(addresses.begin(), addresses.end(), failed), addresses.end());
    std::sort(addresses.begin(), addresses.end());
    addresses.erase(std::unique(addresses.begin(), addresses.end()), addresses.end());
    if (addresses.size() > 8) addresses.resize(8);
    std::vector<long long> latency(addresses.size(), -1);
    std::vector<std::thread> threads;
    for (size_t i = 0; i < addresses.size(); ++i) {
        threads.emplace_back([&, i] {
            const auto start = std::chrono::steady_clock::now();
            TlsWsClient probe;
            std::string error;
            if (probe.connect("generativelanguage.googleapis.com", 443, "", ca_path,
                              error, 3500, addresses[i], true)) {
                latency[i] = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - start).count();
            }
        });
    }
    for (auto& thread : threads) thread.join();
    size_t best = addresses.size();
    for (size_t i = 0; i < addresses.size(); ++i) {
        if (latency[i] >= 0 && (best == addresses.size() || latency[i] < latency[best])) best = i;
    }
    if (best == addresses.size()) {
        if (host == "smart-auto") return select_fast_proxy("ai-pool.comss.one", ca_path, force_refresh, exclude);
        return {};
    }
    LOGI("ProxySelector", "Selected %s from %s (TLS %lld ms)", addresses[best].c_str(), host.c_str(), latency[best]);
    {
        std::lock_guard<std::mutex> lock(cache_mu);
        cache_host = host;
        cache_address = addresses[best];
        cache_until = std::chrono::steady_clock::now() + std::chrono::minutes(5);
    }
    return addresses[best];
}

bool connect_best_proxy(TlsWsClient& client, const std::string& host,
                        const std::string& path, const std::string& ca_path,
                        std::string& error, int timeout_ms) {
    std::string address = select_fast_proxy(host, ca_path);
    if (address.empty()) address = host;
    if (client.connect("generativelanguage.googleapis.com", 443, path, ca_path,
                       error, timeout_ms, address)) return true;
    invalidate_proxy_selection(host);
    if (address == host || error == "aborted") return false;
    std::string next = select_fast_proxy(host, ca_path, true, address);
    if (next.empty() || next == address) return false;
    return client.connect("generativelanguage.googleapis.com", 443, path, ca_path,
                          error, timeout_ms, next);
}
}
