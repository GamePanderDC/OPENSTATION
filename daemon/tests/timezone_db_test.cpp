#include "net/timezone_db.hpp"
#include <cassert>

int main() {
    using openstation::timezone_in_db;
    const char* db = "/system/usr/share/zoneinfo/tzdata";
    assert(timezone_in_db("Europe/Moscow", db));
    assert(timezone_in_db("America/New_York", db));
    assert(!timezone_in_db("Europe/NotARealCity", db));
    assert(!timezone_in_db("Europe/Moscow;reboot", db));
    assert(!timezone_in_db("../Europe/Moscow", db));
    assert(!timezone_in_db("Europe/Moscow", "/does/not/exist"));
}
