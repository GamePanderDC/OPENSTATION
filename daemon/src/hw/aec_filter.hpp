#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

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
 * 16 kHz Delay-Aligned NLMS Acoustic Echo Canceller with Double-Talk Freeze & AES.
 *
 * Measured hardware+acoustic loopback-to-mic lag on Yandex Station Max (pcmC0D1c)
 * is 39..76 samples @ 16 kHz (2.44..4.75 ms). Pre-delaying the reference by
 * kPreDelay = 32 samples (2.0 ms) places the direct path cleanly around taps 7..44
 * inside the 1024-tap (64 ms) FIR window while keeping contiguous cache-friendly
 * storage accelerated by AArch64 NEON intrinsics.
 */
class Aec16kFilter {
public:
    static constexpr size_t kTaps = 1024;
    static constexpr size_t kPreDelay = 32;

    Aec16kFilter() {
        w_.assign(kTaps, 0.0f);
        ref_hist_.assign(kTaps * 2, 0.0f);
        delay_buf_.assign(kPreDelay, 0.0f);
    }

    void reset(bool reset_weights = true) {
        if (reset_weights) {
            std::fill(w_.begin(), w_.end(), 0.0f);
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
        err_env_ = 0.0f;
        p_mm_ = 0.0f;
        p_yy_ = 0.0f;
        p_my_ = 0.0f;
        dt_hold_ = 0;
        lock_cnt_ = 0;
        dt_consec_ = 0;
        locked_ = false;
    }

    /**
     * Process a single 16 kHz frame in place (`mic` overwritten by echo-cancelled output).
     * Both `mic` and `ref` should already be high-pass filtered (>95 Hz).
     */
    void process_block(float* mic, const float* ref, size_t count) {
        if (!mic || !ref || count == 0) return;

        float* wp = w_.data();
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

            const float* xp = &ref_hist_[pos_];
            if ((++pwr_sync_cnt_ & 255U) == 0U) {
#if defined(__aarch64__)
                float32x4_t p0 = vdupq_n_f32(0.0f);
                float32x4_t p1 = vdupq_n_f32(0.0f);
                for (size_t k = 0; k < kTaps; k += 8) {
                    const float32x4_t x0 = vld1q_f32(xp + k);
                    const float32x4_t x1 = vld1q_f32(xp + k + 4);
                    p0 = vfmaq_f32(p0, x0, x0);
                    p1 = vfmaq_f32(p1, x1, x1);
                }
                ref_pwr_ = vaddvq_f32(vaddq_f32(p0, p1));
#else
                float p0 = 0.0f, p1 = 0.0f, p2 = 0.0f, p3 = 0.0f;
                for (size_t k = 0; k < kTaps; k += 4) {
                    p0 += xp[k] * xp[k];
                    p1 += xp[k + 1] * xp[k + 1];
                    p2 += xp[k + 2] * xp[k + 2];
                    p3 += xp[k + 3] * xp[k + 3];
                }
                ref_pwr_ = p0 + p1 + p2 + p3;
#endif
            } else {
                ref_pwr_ = std::max(0.0f, ref_pwr_ + x_delayed * x_delayed - oldest * oldest);
            }

            float y_hat = 0.0f;
#if defined(__aarch64__)
            {
                float32x4_t a0 = vdupq_n_f32(0.0f);
                float32x4_t a1 = vdupq_n_f32(0.0f);
                for (size_t k = 0; k < kTaps; k += 8) {
                    a0 = vfmaq_f32(a0, vld1q_f32(wp + k), vld1q_f32(xp + k));
                    a1 = vfmaq_f32(a1, vld1q_f32(wp + k + 4), vld1q_f32(xp + k + 4));
                }
                y_hat = vaddvq_f32(vaddq_f32(a0, a1));
            }
#else
            {
                float s0 = 0.0f, s1 = 0.0f, s2 = 0.0f, s3 = 0.0f;
                for (size_t k = 0; k < kTaps; k += 4) {
                    s0 += wp[k] * xp[k];
                    s1 += wp[k + 1] * xp[k + 1];
                    s2 += wp[k + 2] * xp[k + 2];
                    s3 += wp[k + 3] * xp[k + 3];
                }
                y_hat = (s0 + s1) + (s2 + s3);
            }
#endif

            const float m = mic[i];
            const float err = m - y_hat;

            // Track short-term signal envelopes & cross-correlation (~12..25 ms EMAs).
            // Correlation rho^2(mic, y_hat) stays high (~0.85..0.99) even when hardware amp
            // volume steps up (unlike naive coupling thresholds), and drops when near-end
            // uncorrelated speech appears (Double-Talk).
            const float abs_ref = std::abs(x_delayed);
            const float abs_mic = std::abs(m);
            const float abs_yhat = std::abs(y_hat);
            const float abs_err = std::abs(err);
            ref_env_ = ref_env_ * 0.996f + abs_ref * 0.004f;
            mic_env_ = mic_env_ * 0.990f + abs_mic * 0.010f;
            yhat_env_ = yhat_env_ * 0.990f + abs_yhat * 0.010f;
            err_env_ = err_env_ * 0.990f + abs_err * 0.010f;

            p_mm_ = p_mm_ * 0.992f + m * m * 0.008f;
            p_yy_ = p_yy_ * 0.992f + y_hat * y_hat * 0.008f;
            p_my_ = p_my_ * 0.992f + m * y_hat * 0.008f;

            const float rho2 = (p_my_ > 0.0f)
                ? ((p_my_ * p_my_) / (p_mm_ * p_yy_ + 1.0f))
                : 0.0f;

            // Only arm DTD once NLMS has genuinely locked onto the echo path.
            // Prevents initial-convergence deadlock while allowing rapid adaptation on volume steps
            // (where rho2 stays ~0.85..0.99 because m scales proportionally with y_hat).
            if (p_yy_ > 400.0f && rho2 > 0.78f && err_env_ < 0.55f * mic_env_) {
                if (lock_cnt_ < 2000) ++lock_cnt_;
                if (lock_cnt_ >= 320) locked_ = true;
            } else if (p_yy_ <= 100.0f) {
                lock_cnt_ = 0;
            }

            const bool inst_dt = locked_ && (p_yy_ > 400.0f) &&
                                 (err_env_ > yhat_env_ * 0.65f + 40.0f) &&
                                 (rho2 < 0.65f);
            if (inst_dt) {
                dt_hold_ = 640; // ~40 ms hold across phoneme peaks
            } else if (dt_hold_ > 0) {
                --dt_hold_;
            }

            bool double_talk = (dt_hold_ > 0);
            if (double_talk) {
                if (++dt_consec_ > 56000) {
                    // >3.5s continuous freeze usually indicates room/speaker acoustics changed; unlock to re-adapt.
                    locked_ = false;
                    lock_cnt_ = 0;
                    dt_hold_ = 0;
                    dt_consec_ = 0;
                    double_talk = false;
                }
            } else {
                dt_consec_ = 0;
            }

            const float norm_mu = double_talk ? 0.002f : 0.45f;
            const float denom = ref_pwr_ + 1024.0f * 2000.0f;
            const float step = (norm_mu * err) / denom;

#if defined(__aarch64__)
            {
                const float32x4_t vstep = vdupq_n_f32(step);
                const float32x4_t vmax = vdupq_n_f32(2.5f);
                const float32x4_t vmin = vdupq_n_f32(-2.5f);
                for (size_t k = 0; k < kTaps; k += 8) {
                    float32x4_t w0 = vfmaq_f32(vld1q_f32(wp + k), vstep, vld1q_f32(xp + k));
                    float32x4_t w1 = vfmaq_f32(vld1q_f32(wp + k + 4), vstep, vld1q_f32(xp + k + 4));
                    vst1q_f32(wp + k, vmaxq_f32(vmin, vminq_f32(vmax, w0)));
                    vst1q_f32(wp + k + 4, vmaxq_f32(vmin, vminq_f32(vmax, w1)));
                }
            }
#else
            for (size_t k = 0; k < kTaps; ++k) {
                wp[k] = std::clamp(wp[k] + step * xp[k], -2.5f, 2.5f);
            }
#endif

            // Smooth nonlinear residual echo suppression strictly during single-talk playback
            // so speaker cabinet distortion never blocks wake words without harming near-end speech.
            float out = err;
            if (!double_talk && yhat_env_ > 25.0f) {
                const float sup = std::clamp(
                    err_env_ / std::max(err_env_ + 0.50f * yhat_env_, 1.0f),
                    0.30f, 1.0f);
                out *= sup;
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
    float err_env_ = 0.0f;
    float p_mm_ = 0.0f;
    float p_yy_ = 0.0f;
    float p_my_ = 0.0f;
    int dt_hold_ = 0;
    int lock_cnt_ = 0;
    int dt_consec_ = 0;
    bool locked_ = false;
};

} // namespace openstation
