#include "assistant/chatgpt_oidc.hpp"
#include <cassert>
#include <fstream>
#include <iterator>
#include <string>

static std::string read_file(const char* path) {
    std::ifstream input(path);
    return std::string(std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>());
}
int main(int argc, char** argv) {
    if (argc != 3) return 1;
    const std::string token = read_file(argv[1]), jwks = read_file(argv[2]);
    openstation::ChatGptIdentity id;
    assert(openstation::verify_chatgpt_id_token(token, jwks, "client-test", "nonce-test", 1700000000, id));
    assert(id.subject == "subject-test" && id.email == "test@example.invalid");
    assert(!openstation::verify_chatgpt_id_token(token, jwks, "bad-client", "nonce-test", 1700000000, id));
    assert(!openstation::verify_chatgpt_id_token(token, jwks, "client-test", "bad-nonce", 1700000000, id));
    assert(!openstation::verify_chatgpt_id_token(token, jwks, "client-test", "nonce-test", 2000000001, id));
    std::string changed = token;
    changed[changed.size() - 5] = changed[changed.size() - 5] == 'A' ? 'B' : 'A';
    assert(!openstation::verify_chatgpt_id_token(changed, jwks, "client-test", "nonce-test", 1700000000, id));
    assert(!openstation::verify_chatgpt_id_token(token, "{}", "client-test", "nonce-test", 1700000000, id));
    puts("PASS");
}
