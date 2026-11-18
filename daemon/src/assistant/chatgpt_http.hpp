#pragma once
#include "net/http_client.hpp"

namespace openstation {
// Requests are restricted to OpenAI hosts, use TLS verification and never follow redirects.
HttpClientResponse chatgpt_http_request(HttpClientRequest request);
}
