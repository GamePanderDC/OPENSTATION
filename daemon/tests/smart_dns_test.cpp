#include "assistant/smart_dns.hpp"
#include <cassert>
#include <vector>

int main() {
    const std::vector<unsigned char> question = {1,'x',0,0,1,0,1};
    std::vector<unsigned char> p = {0x12,0x34,0x81,0x80,0,1,0,1,0,0,0,0};
    p.insert(p.end(), question.begin(), question.end());
    const unsigned char answer[] = {0xc0,0x0c,0,1,0,1,0,0,0,30,0,4,144,31,159,147};
    p.insert(p.end(), answer, answer + sizeof(answer));
    auto valid = openstation::parse_smart_dns_reply(p.data(), p.size(), 0x1234, question);
    assert(valid.size() == 1 && valid[0] == "144.31.159.147");
    assert(openstation::parse_smart_dns_reply(p.data(), p.size(), 0x1235, question).empty());
    assert(openstation::parse_smart_dns_reply(p.data(), p.size() - 1, 0x1234, question).empty());
    p[13] = 'y';
    assert(openstation::parse_smart_dns_reply(p.data(), p.size(), 0x1234, question).empty());
    p[13] = 'x'; p[p.size()-4] = 127;
    assert(openstation::parse_smart_dns_reply(p.data(), p.size(), 0x1234, question).empty());
}
