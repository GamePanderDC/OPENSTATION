#pragma once
#include <string>

namespace openstation {
// `pm path` prints the base APK first and may also list split APKs.
inline std::string base_apk_path_from_pm(const std::string& output) {
    std::string first;
    size_t start = 0;
    while (start < output.size()) {
        size_t end = output.find('\n', start);
        if (end == std::string::npos) end = output.size();
        std::string line = output.substr(start, end - start);
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.rfind("package:/", 0) == 0) {
            std::string path = line.substr(8);
            if (first.empty()) first = path;
            if (path.size() >= 9 && path.compare(path.size() - 9, 9, "/base.apk") == 0)
                return path;
        }
        start = end + 1;
    }
    return first;
}
}
