#include "assistant/chatgpt_http.hpp"
#include "assistant/proxy_selector.hpp"

namespace openstation {
HttpClientResponse chatgpt_http_request(HttpClientRequest request) {
    const std::string auth = "https://auth.openai.com/";
    const std::string api = "https://api.openai.com/";
    std::string host;
    if (request.url.rfind(auth, 0) == 0) host = "auth.openai.com";
    else if (request.url.rfind(api, 0) == 0) host = "api.openai.com";
    else { HttpClientResponse r; r.error = "Unsupported OpenAI endpoint"; return r; }
    request.verify_tls = true;
    request.max_redirects = 0;
    const auto candidates = resolve_smart_dns(host);
    HttpClientResponse last;
    // Do not repeat a token exchange or inference after any HTTP response.
    // Retrying a failed connection is safe only before the server may receive it,
    // which the HTTP client cannot currently distinguish; use a single address.
    if (!candidates.empty()) request.connect_address = candidates.front();
    last = http_perform(request);
    return last;
}
}
