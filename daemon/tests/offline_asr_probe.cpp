#include "assistant/offline_asr.hpp"
#include <cstdio>
#include <vector>

int main(int argc, char** argv) {
    if (argc != 2) return 1;
    FILE* f = fopen(argv[1], "rb");
    if (!f) return 2;
    std::vector<int16_t> pcm;
    int16_t buffer[1600];
    size_t n;
    while ((n = fread(buffer, sizeof(int16_t), 1600, f)) > 0) pcm.insert(pcm.end(), buffer, buffer + n);
    fclose(f);
    openstation::OfflineAsr asr;
    std::string text, error;
    if (!asr.initialize(error) || !asr.recognize(pcm, text, error)) {
        fprintf(stderr, "%s\n", error.c_str());
        return 3;
    }
    printf("%s\n", text.c_str());
    return text.empty() ? 4 : 0;
}
