#include "assistant/listening_deadline.hpp"
#include <cassert>
using namespace std::chrono_literals;
int main() {
    using openstation::listening_deadline_expired;
    assert(!listening_deadline_expired(7s, 7s, 8, true));
    assert(listening_deadline_expired(8s, 8s, 8, true));
    assert(!listening_deadline_expired(0s, 19s, 8, true));
    assert(listening_deadline_expired(0s, 20s, 8, true));
    assert(!listening_deadline_expired(0s, 0s, 8, true));
    assert(!listening_deadline_expired(7s, 7s, 2, false));
    assert(listening_deadline_expired(8s, 8s, 2, false));
    assert(!listening_deadline_expired(0s, 59s, 30, true));
    assert(listening_deadline_expired(0s, 60s, 30, true));
}
