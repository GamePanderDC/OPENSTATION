#include "assistant/proxy_selector.hpp"
#include "assistant/tls_ws_client.hpp"
#include "utils/logger.hpp"

#include <algorithm>
#include <arpa/inet.h>
#include <chrono>
#include <mutex>
#include <netdb.h>
#include <thread>
#include <vector>

namespace openstation {
namespace {
std::mutex cache_mu;
std::string cache_host, cache_address;
std::chrono::steady_clock::time_point cache_until;
}

void invalidate_proxy_selection(const std::string& host) {
    std::lock_guard<std::mutex> lock(cache_mu);
    if (cache_host == host) { cache_host.clear(); cache_address.clear(); }
}

std::string select_fast_proxy(const std::string& host, const std::string& ca_path,
                              bool force_refresh, const std::string& exclude) {
    if (host.empty()) return {};
    unsigned char numeric[sizeof(in6_addr)];
    if (inet_pton(AF_INET, host.c_str(), numeric) == 1 ||
        inet_pton(AF_INET6, host.c_str(), numeric) == 1) return host == exclude ? "" : host;
    {
        std::lock_guard<std::mutex> lock(cache_mu);
        if (!force_refresh && cache_host == host && !cache_address.empty() &&
            std::chrono::steady_clock::now() < cache_until) return cache_address;
    }
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* results = nullptr;
    if (getaddrinfo(host.c_str(), "443", &hints, &results) != 0 || !results) return {};
    std::vector<std::string> addresses;
    for (auto* p = results; p && addresses.size() < 8; p = p->ai_next) {
        char ip[INET6_ADDRSTRLEN]{};
        const void* src = nullptr;
        if (p->ai_family == AF_INET) src = &reinterpret_cast<sockaddr_in*>(p->ai_addr)->sin_addr;
        else if (p->ai_family == AF_INET6) src = &reinterpret_cast<sockaddr_in6*>(p->ai_addr)->sin6_addr;
        if (src && inet_ntop(p->ai_family, src, ip, sizeof(ip)) && ip != exclude &&
            std::find(addresses.begin(), addresses.end(), ip) == addresses.end()) addresses.emplace_back(ip);
    }
    freeaddrinfo(results);
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
    if (best == addresses.size()) return {};
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
