#include "assistant/assistant_manager.hpp"

#include "assistant/chatgpt_client.hpp"
#include "assistant/assistant_intents.hpp"

namespace openstation {
namespace {
using Clock = std::chrono::steady_clock;
constexpr int64_t kCallInactivityMs = 90000;
constexpr int64_t kCallMaximumMs = 30 * 60 * 1000;
constexpr size_t kCallPreRollSamples = 12800;

bool end_call_phrase(std::string text) {
    while (!text.empty() && (text.back() == '.' || text.back() == '!' || text.back() == ' ')) text.pop_back();
    if (text.compare(0, sizeof("алиса ") - 1, "алиса ") == 0) text.erase(0, sizeof("алиса ") - 1);
    return is_assistant_stop_request(text) || text == "положи трубку" ||
           text == "заверши разговор" || text == "завершить разговор" ||
           text == "закончи разговор" || text == "заверши звонок";
}

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch()).count();
}

json::Value message(const std::string& role, const std::string& text) {
    auto result = json::Value::make_obj();
    result["role"] = json::Value::make_str(role);
    result["content"] = json::Value::make_str(text);
    return result;
}

std::string plain_speech(const std::string& text) {
    std::string clean;
    clean.reserve(text.size());
    for (char c : text) {
        if (c != '*' && c != '#' && c != '\x60') clean += c;
    }
    return clean;
}
}

void AssistantManager::warm_chatgpt_recognizer() {
    std::lock_guard<std::mutex> lock(asr_warm_mu_);
    if (!running_ || asr_warm_.joinable()) return;
    asr_warm_ = std::thread([this] {
        std::string error;
        chatgpt_asr_.initialize(error);
    });
}

void AssistantManager::chatgpt_session_worker(TriggerSource source, std::string initial_text,
                                             AssistantConfig cfg, bool announcement, int64_t triggered_ms) {
    const bool call = chatgpt_call_active_;
    const int64_t deadline = triggered_ms + (call ? kCallMaximumMs : cfg.max_session_sec * 1000LL);
    int64_t call_idle_deadline = triggered_ms + kCallInactivityMs;
    const auto active = [this, deadline] {
        return running_ && !session_abort_ && now_ms() < deadline;
    };
    auto conversation = json::Value::make_arr();
    std::string error;
    std::string instructions = personalize_system_prompt(cfg.system_prompt, cfg.name, false);
    instructions += "\nОтвет произносит голосовой ассистент. Отвечай кратко, обычным текстом, на языке вопроса.";
    bool initial_turn = true;
    while (active()) {
        std::string prompt;
        if (initial_turn && source == TriggerSource::Text) {
            prompt = initial_text;
        } else {
            const bool ptt = initial_turn && source == TriggerSource::Remote;
            const int64_t opened = initial_turn ? triggered_ms : now_ms();
            const int64_t no_speech_timeout = initial_turn ? 6000 : cfg.follow_up_sec * 1000;
            int64_t speech_started = 0;
            set_state(AssistantState::Listening);
            std::vector<int16_t> pcm;
            {
                std::unique_lock<std::mutex> lock(tx_mu_);
                while (active()) {
                    const int64_t now = now_ms();
                    const auto said_at = last_speech_ms_.load();
                    if (ptt) {
                        if (remote_end_pending_ || now - opened >= 20000) break;
                    } else if (call) {
                        if (said_at >= opened && !speech_started) speech_started = now;
                        if (speech_started) {
                            if (now - said_at >= 700 || now - speech_started >= 18000) break;
                        } else {
                            if (now >= call_idle_deadline) break;
                            if (tx_pcm_.size() > kCallPreRollSamples) {
                                tx_pcm_.erase(tx_pcm_.begin(), tx_pcm_.end() - kCallPreRollSamples);
                            }
                        }
                    } else if (now - opened >= 18000 ||
                               (said_at >= opened && now - said_at >= 700) ||
                               (said_at < opened && now - opened >= no_speech_timeout)) {
                        break;
                    }
                    tx_cv_.wait_for(lock, std::chrono::milliseconds(50));
                }
                if ((ptt && remote_end_pending_) || (!ptt && last_speech_ms_.load() >= opened)) {
                    set_state(AssistantState::Thinking);
                    pcm.swap(tx_pcm_);
                }
            }
            if (ptt) {
                remote_recording_ = false;
                remote_end_pending_ = false;
                remote_turn_ = false;
                if (cb_.stop_remote_capture) cb_.stop_remote_capture();
            }
            if (!active()) break;
            if (pcm.size() >= 1600 && !chatgpt_asr_.recognize(pcm, prompt, error, !ptt)) break;
            if (prompt.empty()) {
                if (!call || now_ms() >= call_idle_deadline) break;
                initial_turn = false;
                continue;
            }
            if (call && end_call_phrase(prompt)) break;
            if (active()) {
                append_history("user", prompt);
                emit_event("message", "user", prompt);
            }
        }

        if (!active()) break;
        {
            std::lock_guard<std::mutex> lock(hist_mu_);
            last_user_text_ = prompt;
        }
        set_state(AssistantState::Thinking);
        std::string answer;
        if (announcement) {
            answer = prompt;
        } else {
            conversation.arr_val.push_back(message("user", prompt));
            if (!chatgpt_reply(chatgpt_auth_, cfg.chatgpt_model, instructions, conversation,
                               session_abort_, answer, error) || !active()) break;
            conversation.arr_val.push_back(message("assistant", answer));
            if (conversation.arr_val.size() > (call ? 16U : 8U)) {
                conversation.arr_val.erase(conversation.arr_val.begin(), conversation.arr_val.begin() + 2);
            }
        }

        {
            std::lock_guard<std::mutex> lock(hist_mu_);
            last_model_text_ = answer;
        }
        append_history("model", answer);
        emit_event("message", "model", answer);
        set_state(AssistantState::Speaking);
        if (!cb_.speak_locally || !cb_.speak_locally(plain_speech(answer), session_abort_)) {
            if (active()) error = "Не удалось озвучить ответ. Проверьте русский голос в настройках синтеза речи Android.";
            break;
        }
        if (!call && (source == TriggerSource::Text || source == TriggerSource::Remote || cfg.follow_up_sec <= 0)) break;
        call_idle_deadline = now_ms() + kCallInactivityMs;
        // Let audio output and room reverberation decay before reopening the microphone.
        {
            std::unique_lock<std::mutex> lock(tx_mu_);
            tx_cv_.wait_for(lock, std::chrono::milliseconds(600), [&] { return !active(); });
        }
        initial_turn = false;
        {
            std::lock_guard<std::mutex> lock(tx_mu_);
            tx_pcm_.clear();
        }
    }

    if (remote_turn_.exchange(false) && cb_.stop_remote_capture) cb_.stop_remote_capture();
    remote_recording_ = false;
    remote_end_pending_ = false;
    chatgpt_call_active_ = false;
    if (active() && !error.empty()) set_error(error);
    wake_suppress_until_ms_ = now_ms() + 800;
    set_state(AssistantState::Idle);
    wake_word_.set_paused(mic_muted_);
}

}
