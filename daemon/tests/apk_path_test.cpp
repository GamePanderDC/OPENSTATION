#include "ota/apk_path.hpp"
#include <cassert>
int main() {
    using openstation::base_apk_path_from_pm;
    assert(base_apk_path_from_pm("package:/data/app/a/base.apk\r\npackage:/data/app/a/split.apk\n") == "/data/app/a/base.apk");
    assert(base_apk_path_from_pm("package:/data/app/a/split.apk\npackage:/data/app/a/base.apk\n") == "/data/app/a/base.apk");
    assert(base_apk_path_from_pm("package:/system/priv-app/A/A.apk\n") == "/system/priv-app/A/A.apk");
    assert(base_apk_path_from_pm("Error: unknown package\n").empty());
}
