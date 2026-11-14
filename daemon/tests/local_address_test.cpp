#include "net/local_address.hpp"
#include <cassert>

int main() {
    using openstation::LocalAddressChoice;
    LocalAddressChoice choice;
    choice.consider("192.168.1.5", 0xc0a80105, true, false, false, true); // unplugged Ethernet
    choice.consider("169.254.1.2", 0xa9fe0102, true, true, false, true);
    choice.consider("192.168.1.9", 0xc0a80109, true, true, false, true);
    assert(choice.ip == "192.168.1.9");
    LocalAddressChoice fallback;
    fallback.consider("10.0.0.1", 0x0a000001, true, true, false, false);
    fallback.consider("192.168.0.20", 0xc0a80014, true, true, false, true);
    assert(fallback.ip == "192.168.0.20");
    LocalAddressChoice offline;
    offline.consider("127.0.0.1", 0x7f000001, true, true, true, false);
    offline.consider("0.0.0.0", 0, true, true, false, false);
    offline.consider("224.0.0.1", 0xe0000001, true, true, false, false);
    assert(offline.ip.empty());
}
