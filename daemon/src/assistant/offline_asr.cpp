#include "assistant/offline_asr.hpp"
#include "sherpa_onnx/c-api.h"
#include <algorithm>
#include <dlfcn.h>
#include <type_traits>

namespace openstation {
struct OfflineAsr::Impl {
    void* ort = nullptr;
    void* lib = nullptr;
    const SherpaOnnxOnlineRecognizer* recognizer = nullptr;
    decltype(&SherpaOnnxCreateOnlineRecognizer) create = nullptr;
    decltype(&SherpaOnnxDestroyOnlineRecognizer) destroy = nullptr;
    decltype(&SherpaOnnxCreateOnlineStream) create_stream = nullptr;
    decltype(&SherpaOnnxDestroyOnlineStream) destroy_stream = nullptr;
    decltype(&SherpaOnnxOnlineStreamAcceptWaveform) accept = nullptr;
    decltype(&SherpaOnnxOnlineStreamInputFinished) finish = nullptr;
    decltype(&SherpaOnnxIsOnlineStreamReady) ready = nullptr;
    decltype(&SherpaOnnxDecodeOnlineStream) decode = nullptr;
    decltype(&SherpaOnnxGetOnlineStreamResult) result = nullptr;
    decltype(&SherpaOnnxDestroyOnlineRecognizerResult) destroy_result = nullptr;
    ~Impl() {
        if (recognizer && destroy) destroy(recognizer);
        if (lib) dlclose(lib);
        if (ort) dlclose(ort);
    }
};

OfflineAsr::OfflineAsr() : impl_(new Impl) {}
OfflineAsr::~OfflineAsr() = default;

bool OfflineAsr::initialize(std::string& error) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (impl_->recognizer) return true;
    auto next = std::make_unique<Impl>();
    next->ort = dlopen("/data/local/openstation/lib/libonnxruntime.so", RTLD_NOW | RTLD_GLOBAL);
    if (!next->ort) { error = "Не удалось загрузить библиотеку распознавания"; return false; }
    next->lib = dlopen("/data/local/openstation/lib/libsherpa-onnx-c-api.so", RTLD_NOW | RTLD_LOCAL);
    if (!next->lib) { error = "Не удалось загрузить движок распознавания"; return false; }
    auto symbol = [&](auto& dst, const char* name) {
        dst = reinterpret_cast<std::decay_t<decltype(dst)>>(dlsym(next->lib, name));
        return dst != nullptr;
    };
    if (!symbol(next->create,"SherpaOnnxCreateOnlineRecognizer") ||
        !symbol(next->destroy,"SherpaOnnxDestroyOnlineRecognizer") ||
        !symbol(next->create_stream,"SherpaOnnxCreateOnlineStream") ||
        !symbol(next->destroy_stream,"SherpaOnnxDestroyOnlineStream") ||
        !symbol(next->accept,"SherpaOnnxOnlineStreamAcceptWaveform") ||
        !symbol(next->finish,"SherpaOnnxOnlineStreamInputFinished") ||
        !symbol(next->ready,"SherpaOnnxIsOnlineStreamReady") ||
        !symbol(next->decode,"SherpaOnnxDecodeOnlineStream") ||
        !symbol(next->result,"SherpaOnnxGetOnlineStreamResult") ||
        !symbol(next->destroy_result,"SherpaOnnxDestroyOnlineRecognizerResult")) {
        error = "Версия движка не поддерживает распознавание речи"; return false;
    }
    SherpaOnnxOnlineRecognizerConfig c{};
    c.feat_config.sample_rate = 16000;
    c.feat_config.feature_dim = 80;
    c.model_config.transducer.encoder = "/data/local/openstation/models/ru-vosk-small/encoder.int8.onnx";
    c.model_config.transducer.decoder = "/data/local/openstation/models/ru-vosk-small/decoder.onnx";
    c.model_config.transducer.joiner = "/data/local/openstation/models/ru-vosk-small/joiner.int8.onnx";
    c.model_config.tokens = "/data/local/openstation/models/ru-vosk-small/tokens.txt";
    c.model_config.num_threads = 2;
    c.model_config.provider = "cpu";
    c.decoding_method = "greedy_search";
    next->recognizer = next->create(&c);
    if (!next->recognizer) { error = "Не удалось загрузить модель распознавания речи"; return false; }
    impl_ = std::move(next);
    error.clear();
    return true;
}

bool OfflineAsr::recognize(const std::vector<int16_t>& pcm, std::string& text, std::string& error) {
    std::lock_guard<std::mutex> lock(mutex_);
    text.clear();
    if (!impl_->recognizer) { error = "Модель распознавания не загружена"; return false; }
    if (pcm.empty() || pcm.size() > 16000U * 60U) { error = "Недопустимая длина записи"; return false; }
    const auto* stream = impl_->create_stream(impl_->recognizer);
    if (!stream) { error = "Не удалось начать распознавание"; return false; }
    std::vector<float> samples(1600);
    for (size_t off = 0; off < pcm.size(); off += samples.size()) {
        const size_t count = std::min(samples.size(), pcm.size() - off);
        for (size_t i = 0; i < count; ++i) samples[i] = pcm[off + i] / 32768.0f;
        impl_->accept(stream, 16000, samples.data(), static_cast<int32_t>(count));
        while (impl_->ready(impl_->recognizer, stream)) impl_->decode(impl_->recognizer, stream);
    }
    // The streaming encoder needs trailing context to emit the last syllables.
    const std::vector<float> tail(16000, 0.0f);
    impl_->accept(stream, 16000, tail.data(), static_cast<int32_t>(tail.size()));
    impl_->finish(stream);
    while (impl_->ready(impl_->recognizer, stream)) impl_->decode(impl_->recognizer, stream);
    const auto* result = impl_->result(impl_->recognizer, stream);
    if (result && result->text) text = result->text;
    if (result) impl_->destroy_result(result);
    impl_->destroy_stream(stream);
    error.clear();
    return true;
}
}
