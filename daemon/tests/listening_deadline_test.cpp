#include "assistant/listening_deadline.hpp"
#include <cassert>
using namespace std::chrono_literals;
int main() {
    using openstation::listening_deadline_expired;
    assert(!listening_deadline_expired(7s, 7s, 8, true));
    assert(listening_deadline_expired(8s, 8s, 8, true));
    assert(!listening_deadline_expired(0s, 9s, 8, true));
    assert(listening_deadline_expired(0s, 10s, 8, true));
    assert(!listening_deadline_expired(0s, 0s, 8, true));
    assert(!listening_deadline_expired(7s, 7s, 2, false));
    assert(listening_deadline_expired(8s, 8s, 2, false));
    assert(!listening_deadline_expired(3s, 3s, 4, true));
    assert(listening_deadline_expired(4s, 4s, 4, true));
    assert(!listening_deadline_expired(0s, 5s, 4, true));
    assert(listening_deadline_expired(0s, 6s, 4, true));
    assert(!listening_deadline_expired(0s, 31s, 30, true));
    assert(listening_deadline_expired(0s, 32s, 30, true));
}
