#pragma once
#include <string>

namespace openstation {
class TlsWsClient;
// Chooses an address belonging to the configured proxy host. Results are
// cached briefly; an empty result means no candidate passed the TLS probe.
std::string select_fast_proxy(const std::string& proxy_host, const std::string& ca_path,
                              bool force_refresh = false, const std::string& exclude = "");
void invalidate_proxy_selection(const std::string& proxy_host);
bool connect_best_proxy(TlsWsClient& client, const std::string& proxy_host,
                        const std::string& ws_path, const std::string& ca_path,
                        std::string& error, int timeout_ms);
}
