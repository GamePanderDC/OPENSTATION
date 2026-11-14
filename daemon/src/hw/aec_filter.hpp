#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

namespace openstation {

/**
 * 2-stage 95 Hz 1st-order cascade High-Pass Filter (@ 16 kHz).
 * Used identically on beamformed microphone signal and speaker loopback reference
 * so strong <95 Hz hardware hum on Amlogic PDM mics never destabilizes NLMS AEC.
 */
struct Hpf95State {
    float x1 = 0.0f;
    float y1 = 0.0f;
    float x2 = 0.0f;
    float y2 = 0.0f;

    void reset() {
        x1 = y1 = x2 = y2 = 0.0f;
    }

    float process(float x) {
        const float out1 = 0.964f * (y1 + x - x1);
        x1 = x;
        y1 = out1;
        const float out2 = 0.964f * (y2 + out1 - x2);
        x2 = out1;
        y2 = out2;
        return out2;
    }
};

/**
 * 16 kHz Delay-Aligned NLMS Acoustic Echo Canceller with Double-Talk Freeze.
 *
 * Measured hardware loopback-to-mic lag on Yandex Station Max (pcmC0D1c) is
 * 39..51 samples @ 16 kHz (2.44..3.19 ms). Pre-delaying the reference by
 * kPreDelay = 16 samples (1.0 ms) centers the direct path inside the 512-tap
 * (32 ms) FIR window while keeping contiguous cache-friendly ring storage for
 * NEON auto-vectorization.
 */
class Aec16kFilter {
public:
    static constexpr size_t kTaps = 512;
    static constexpr size_t kPreDelay = 16;

    Aec16kFilter() {
        w_.assign(kTaps, 0.0f);
        ref_hist_.assign(kTaps * 2, 0.0f);
        delay_buf_.assign(kPreDelay, 0.0f);
    }

    void reset(bool reset_weights = true) {
        if (reset_weights) {
            std::fill(w_.begin(), w_.end(), 0.0f);
            coupling_est_ = 0.12f;
        }
        reset_history();
    }

    void reset_history() {
        std::fill(ref_hist_.begin(), ref_hist_.end(), 0.0f);
        std::fill(delay_buf_.begin(), delay_buf_.end(), 0.0f);
        pos_ = kTaps - 1;
        delay_pos_ = 0;
        ref_pwr_ = 0.0f;
        pwr_sync_cnt_ = 0;
        ref_env_ = 0.0f;
        mic_env_ = 0.0f;
        yhat_env_ = 0.0f;
        dt_hold_ = 0;
    }

    /**
     * Process a single 16 kHz frame in place (`mic` overwritten by echo-cancelled output).
     * Both `mic` and `ref` should already be high-pass filtered (>95 Hz).
     */
    void process_block(float* mic, const float* ref, size_t count) {
        if (!mic || !ref || count == 0) return;

        for (size_t i = 0; i < count; ++i) {
            const float raw_ref = ref[i];
            const float x_delayed = delay_buf_[delay_pos_];
            delay_buf_[delay_pos_] = raw_ref;
            delay_pos_ = (delay_pos_ + 1) % kPreDelay;

            // Update sliding reference energy in O(1), recomputing periodically to prevent drift.
            const float oldest = ref_hist_[pos_ + kTaps - 1];
            pos_ = (pos_ == 0) ? (kTaps - 1) : (pos_ - 1);
            ref_hist_[pos_] = x_delayed;
            ref_hist_[pos_ + kTaps] = x_delayed;

            if ((++pwr_sync_cnt_ & 255U) == 0U) {
                float p = 0.0f;
                const float* xp = &ref_hist_[pos_];
                for (size_t k = 0; k < kTaps; ++k) {
                    p += xp[k] * xp[k];
                }
                ref_pwr_ = p;
            } else {
                ref_pwr_ = std::max(0.0f, ref_pwr_ + x_delayed * x_delayed - oldest * oldest);
            }

            const float* xp = &ref_hist_[pos_];
            float y_hat = 0.0f;
            for (size_t k = 0; k < kTaps; ++k) {
                y_hat += w_[k] * xp[k];
            }

            const float m = mic[i];
            const float err = m - y_hat;

            // Track short-term signal envelopes (~25 ms EMA) for coupling-invariant DTD.
            const float abs_ref = std::abs(x_delayed);
            const float abs_mic = std::abs(m);
            const float abs_yhat = std::abs(y_hat);
            ref_env_ = ref_env_ * 0.996f + abs_ref * 0.004f;
            mic_env_ = mic_env_ * 0.990f + abs_mic * 0.010f;
            yhat_env_ = yhat_env_ * 0.990f + abs_yhat * 0.010f;

            // Update acoustic coupling estimate only when error cleanly reduces mic amplitude.
            if (ref_env_ > 15.0f && std::abs(err) < abs_mic * 0.85f) {
                const float inst_c = std::clamp(mic_env_ / ref_env_, 0.02f, 2.0f);
                coupling_est_ = coupling_est_ * 0.9992f + inst_c * 0.0008f;
            }

            // Double-Talk Detection: freeze fast NLMS updates if near-end energy exceeds echo bound.
            const float expected_echo = std::max(yhat_env_, ref_env_ * std::max(coupling_est_, 0.08f));
            const bool inst_dt = (mic_env_ > expected_echo * 1.90f + 60.0f) ||
                                 (abs_mic > expected_echo * 2.80f + 120.0f);
            if (inst_dt) {
                dt_hold_ = 640; // ~40 ms hold across phoneme peaks
            } else if (dt_hold_ > 0) {
                --dt_hold_;
            }

            const bool double_talk = (dt_hold_ > 0);
            const float norm_mu = double_talk ? 0.01f : 0.36f;
            const float denom = ref_pwr_ + 512.0f * 2500.0f;
            const float step = (norm_mu * err) / denom;

            for (size_t k = 0; k < kTaps; ++k) {
                w_[k] = std::clamp(w_[k] + step * xp[k], -2.5f, 2.5f);
            }

            // Gentle residual echo attenuation strictly during confirmed single-talk playback tail.
            float out = err;
            if (!double_talk && ref_env_ > 25.0f && std::abs(err) < abs_yhat * 0.85f) {
                out *= 0.65f;
            }
            mic[i] = out;
        }
    }

private:
    std::vector<float> w_;
    std::vector<float> ref_hist_;
    std::vector<float> delay_buf_;
    size_t pos_ = kTaps - 1;
    size_t delay_pos_ = 0;
    float ref_pwr_ = 0.0f;
    uint32_t pwr_sync_cnt_ = 0;
    float ref_env_ = 0.0f;
    float mic_env_ = 0.0f;
    float yhat_env_ = 0.0f;
    float coupling_est_ = 0.12f;
    int dt_hold_ = 0;
};

} // namespace openstation
