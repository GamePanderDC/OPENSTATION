#include "assistant/chatgpt_oidc.hpp"
#include "utils/base64.hpp"
#include "utils/json_min.hpp"
#include "mbedtls/pk.h"
#include "mbedtls/rsa.h"
#include "mbedtls/sha256.h"
#include "psa/crypto.h"
#include <algorithm>
#include <mutex>

namespace openstation {
namespace {
bool safe_b64url(const std::string& s) {
    if (s.empty() || s.size() > 16384 || s.size() % 4 == 1) return false;
    return std::all_of(s.begin(), s.end(), [](unsigned char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
               (c >= '0' && c <= '9') || c == '-' || c == '_';
    });
}
bool parse_b64_json(const std::string& part, json::Value& value) {
    if (!safe_b64url(part)) return false;
    auto decoded = b64::decode(part);
    return json::Parser::parse(std::string(decoded.begin(), decoded.end()), value) && value.is_obj();
}
std::string string_field(const json::Value& obj, const char* name) {
    const auto* v = obj.get(name);
    return v && v->is_str() ? v->as_str() : "";
}
}

bool verify_chatgpt_id_token(const std::string& token, const std::string& jwks_json,
                             const std::string& client_id, const std::string& nonce,
                             int64_t now_epoch, ChatGptIdentity& identity) {
    identity = {};
    if (client_id.empty() || nonce.empty() || token.size() > 49152 || jwks_json.size() > 262144) return false;
    size_t first = token.find('.'), second = first == std::string::npos ? first : token.find('.', first + 1);
    if (first == std::string::npos || second == std::string::npos || token.find('.', second + 1) != std::string::npos) return false;
    const std::string header_part = token.substr(0, first), payload_part = token.substr(first + 1, second - first - 1);
    const std::string signature_part = token.substr(second + 1);
    json::Value header, claims, jwks;
    if (!parse_b64_json(header_part, header) || !parse_b64_json(payload_part, claims) ||
        !safe_b64url(signature_part) || !json::Parser::parse(jwks_json, jwks) || !jwks.is_obj()) return false;
    const std::string kid = string_field(header, "kid");
    if (string_field(header, "alg") != "RS256" || kid.empty()) return false;
    const auto* keys = jwks.get("keys");
    if (!keys || !keys->is_arr()) return false;
    const json::Value* key = nullptr;
    for (const auto& k : keys->arr_val) {
        if (k.is_obj() && string_field(k,"kid") == kid && string_field(k,"kty") == "RSA" &&
            (string_field(k,"alg").empty() || string_field(k,"alg") == "RS256") &&
            (string_field(k,"use").empty() || string_field(k,"use") == "sig")) { key = &k; break; }
    }
    if (!key) return false;
    const std::string ns = string_field(*key, "n"), es = string_field(*key, "e");
    if (!safe_b64url(ns) || !safe_b64url(es)) return false;
    const auto n = b64::decode(ns), e = b64::decode(es), signature = b64::decode(signature_part);
    if (n.size() < 256 || n.size() > 1024 || e.empty() || e.size() > 8 || signature.size() != n.size()) return false;
    static std::once_flag psa_once;
    static bool psa_ok = false;
    std::call_once(psa_once, [] { psa_ok = psa_crypto_init() == PSA_SUCCESS; });
    if (!psa_ok) return false;
    mbedtls_pk_context pk;
    mbedtls_pk_init(&pk);
    int rc = mbedtls_pk_setup(&pk, mbedtls_pk_info_from_type(MBEDTLS_PK_RSA));
    if (rc == 0) rc = mbedtls_rsa_import_raw(mbedtls_pk_rsa(pk), n.data(), n.size(), nullptr, 0,
                                              nullptr, 0, nullptr, 0, e.data(), e.size());
    if (rc == 0) rc = mbedtls_rsa_complete(mbedtls_pk_rsa(pk));
    unsigned char digest[32]{};
    const std::string signed_data = token.substr(0, second);
    if (rc == 0) rc = mbedtls_sha256(reinterpret_cast<const unsigned char*>(signed_data.data()), signed_data.size(), digest, 0);
    if (rc == 0) rc = mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, digest, sizeof(digest), signature.data(), signature.size());
    mbedtls_pk_free(&pk);
    if (rc != 0) return false;
    if (string_field(claims,"iss") != "https://auth.openai.com" || string_field(claims,"nonce") != nonce ||
        string_field(claims,"sub").empty()) return false;
    const auto* exp = claims.get("exp");
    if (!exp || !exp->is_num() || exp->as_i64() <= now_epoch) return false;
    const auto* aud = claims.get("aud");
    bool audience_ok = aud && aud->is_str() && aud->as_str() == client_id;
    if (aud && aud->is_arr()) {
        for (const auto& a : aud->arr_val) if (a.is_str() && a.as_str() == client_id) audience_ok = true;
        if (aud->arr_val.size() > 1 && string_field(claims,"azp") != client_id) return false;
    }
    if (!audience_ok) return false;
    identity.subject = string_field(claims,"sub");
    identity.email = string_field(claims,"email");
    return true;
}
}
