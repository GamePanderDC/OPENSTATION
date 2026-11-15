#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace openstation {

struct ScrubVisualSnapshot {
    bool active = false;
    float velocity = 0.0f;
    float platter_deg = 0.0f;
    int seek_display_ms = 0;
    std::chrono::steady_clock::time_point seek_badge_until{};
    bool has_live_wave = false;
    bool scrubbing_audio = false;
};

struct ScrubTickOutput {
    bool active = false;
    bool duck_changed = false;
    bool duck_active = false;
    bool commit_seek = false;
    int commit_seek_ms = 0;
    ScrubVisualSnapshot visual;
};

// Real-audio vinyl scrub ("вжвжвж") engine powered exclusively by continuous
// 48 kHz waveform capture from Android AudioOut_D. Zero synthetic oscillators,
// zero noise generators, zero mode chimes.
class TrackScrubEngine {
public:
    static constexpr int kSampleRate = 48000;
    // 6.0 seconds of mono 48 kHz float rolling song history.
    static constexpr size_t kWaveCapacity = 288000;
    static constexpr size_t kSeamCrossfadeSamples = 24;

    TrackScrubEngine() {
        wave_ring_.assign(kWaveCapacity, 0.0f);
        ingest_scratch_.reserve(2048);
    }

    // Ingests a normalized uint8 block from AudioOut_D Visualizer at 48 kHz.
    // true_peak_lin / true_rms_lin come from Visualizer.MEASUREMENT_MODE_PEAK_RMS
    // (< 0 means measurement unavailable -> fallback to normalized block RMS).
    void ingest_wave_u8_block(const uint8_t* bytes,
                              size_t count,
                              float true_peak_lin = -1.0f,
                              float true_rms_lin = -1.0f,
                              std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now()) {
        if (!bytes || count < 16) return;
        std::lock_guard<std::mutex> lk(mu_);
        // Never overwrite song history with our own scratch output or post-scrub tail.
        if (duck_active_ || now < ingest_cooldown_until_) return;

        const size_t n = std::min<size_t>(count, 2048);
        ingest_scratch_.resize(n);

        double sum = 0.0;
        for (size_t i = 0; i < n; ++i) {
            float v = (static_cast<float>(bytes[i]) - 128.0f) / 127.0f;
            ingest_scratch_[i] = v;
            sum += v;
        }
        const float mean = static_cast<float>(sum / static_cast<double>(n));
        double sq = 0.0;
        for (size_t i = 0; i < n; ++i) {
            ingest_scratch_[i] -= mean;
            sq += static_cast<double>(ingest_scratch_[i]) * static_cast<double>(ingest_scratch_[i]);
        }
        const float wb_rms = static_cast<float>(std::sqrt(sq / static_cast<double>(n)));

        const bool has_meas = (true_peak_lin >= 0.0f && true_rms_lin >= 0.0f);
        if (wb_rms < 1e-4f || (has_meas && true_peak_lin < 0.00015f)) {
            // Digital silence block: fade tail instead of amplifying floor noise.
            append_block_locked(nullptr, n, 0.0f);
            return;
        }

        // Update slow 2.5s song RMS baseline (cancels Visualizer's power-of-2
        // block bitshift while keeping natural musical loudness swells).
        float target_rms = 0.28f;
        if (has_meas && true_rms_lin > 0.0001f) {
            if (ref_song_rms_ <= 0.0f) {
                ref_song_rms_ = true_rms_lin;
            } else {
                const double block_sec = static_cast<double>(n) / static_cast<double>(kSampleRate);
                const float alpha = static_cast<float>(1.0 - std::exp(-block_sec / 2.2));
                ref_song_rms_ += alpha * (true_rms_lin - ref_song_rms_);
            }
            const float rel = std::clamp(true_rms_lin / std::max(1e-5f, ref_song_rms_), 0.28f, 2.10f);
            target_rms = 0.28f * rel;
        }

        // Gain cancels Visualizer SCALING_MODE_NORMALIZED per-block bitshift.
        float block_gain = target_rms / std::max(0.045f, wb_rms);
        block_gain = std::clamp(block_gain, 0.05f, 4.5f);
        if (last_ingest_gain_ > 0.0f) {
            // Prevent hard step gain jumps across a single 21 ms seam.
            float max_step = last_ingest_gain_ * 1.45f;
            float min_step = last_ingest_gain_ * 0.68f;
            block_gain = std::clamp(block_gain, min_step, max_step);
        }
        last_ingest_gain_ = block_gain;

        for (size_t i = 0; i < n; ++i) {
            ingest_scratch_[i] = std::clamp(ingest_scratch_[i] * block_gain, -0.96f, 0.96f);
        }

        // Match overlap seam against recent tail when polling jitter returns a
        // partially overlapping Visualizer capture window.
        size_t overlap = find_overlap_locked(ingest_scratch_.data(), n);
        if (overlap >= n) return; // Identical duplicate block.

        append_block_locked(ingest_scratch_.data() + overlap, n - overlap, 1.0f);
        last_wave_tp_ = now;
        wave_has_live_ = (total_ingested_ >= 2048);
    }

    // Backwards-compatible helper when peak/RMS measurement metadata is absent.
    void ingest_wave_u8(const uint8_t* bytes,
                        size_t count,
                        std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now()) {
        ingest_wave_u8_block(bytes, count, -1.0f, -1.0f, now);
    }

    // Engages or releases hold-to-scrub mode (e.g. holding Alice button).
    // Returns optional commit seek delta (ms) when releasing.
    ScrubTickOutput set_hold_active(bool held,
                                    std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now()) {
        std::lock_guard<std::mutex> lk(mu_);
        ScrubTickOutput out;
        if (held == hold_active_) {
            out.active = hold_active_;
            out.duck_active = duck_active_;
            out.visual = make_visual_locked(now);
            return out;
        }
        hold_active_ = held;
        if (held) {
            reset_motion_locked();
            scrub_ever_moved_ = false;
            seek_accum_ms_ = 0;
            seek_display_ms_ = 0;
            seek_badge_until_ = {};
            anchor_cursor_locked();
        } else {
            // Release immediately restores playback and commits any accumulated seek.
            if (duck_active_) {
                duck_active_ = false;
                out.duck_changed = true;
                out.duck_active = false;
                ingest_cooldown_until_ = now + std::chrono::milliseconds(380);
            }
            if (seek_accum_ms_ != 0) {
                out.commit_seek = true;
                out.commit_seek_ms = seek_accum_ms_;
                seek_accum_ms_ = 0;
            }
            reset_motion_locked();
            seek_display_ms_ = 0;
            seek_badge_until_ = {};
        }
        out.active = hold_active_;
        out.visual = make_visual_locked(now);
        return out;
    }

    bool active() const {
        std::lock_guard<std::mutex> lk(mu_);
        return hold_active_;
    }

    bool is_ducking() const {
        std::lock_guard<std::mutex> lk(mu_);
        return duck_active_;
    }

    bool has_live_wave() const {
        std::lock_guard<std::mutex> lk(mu_);
        return wave_has_live_;
    }

    // Processes a physical rotary encoder click while held.
    void on_rotary_detent(int dir,
                          int steps = 1,
                          std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now()) {
        std::lock_guard<std::mutex> lk(mu_);
        if (!hold_active_) return;
        const int d = (dir >= 0) ? 1 : -1;
        const int st = std::clamp(steps, 1, 5);

        double dt_s = 0.14;
        if (last_detent_tp_.time_since_epoch().count() > 0) {
            dt_s = std::clamp(std::chrono::duration<double>(now - last_detent_tp_).count(), 0.015, 0.45);
        }
        last_detent_tp_ = now;
        last_scrub_input_tp_ = now;
        scrub_ever_moved_ = true;

        if (!duck_active_) {
            duck_active_ = true;
            anchor_cursor_locked();
        }

        // Faster hand twists drive higher platter speed (-3.2x .. +3.2x) and
        // proportionally larger seek travel so both fine tweaks and fast skips feel natural.
        const float cadence_boost = static_cast<float>(std::clamp(0.11 / dt_s, 0.85, 2.45));
        const float target_mag = std::clamp(1.15f * cadence_boost + 0.10f * (st - 1), 1.10f, 3.35f);
        target_omega_ = static_cast<float>(d) * target_mag;

        // Instant direction flip snap when reversing quickly ("вжик-вжик").
        if ((d > 0 && omega_ < -0.1f) || (d < 0 && omega_ > 0.1f)) {
            omega_ *= 0.25f;
        } else if (std::abs(omega_) < 0.35f) {
            omega_ = static_cast<float>(d) * 0.72f;
        }

        const int detent_ms = static_cast<int>(std::lround(d * (750.0 * std::clamp(cadence_boost, 0.9f, 1.85f))));
        seek_accum_ms_ = std::clamp(seek_accum_ms_ + detent_ms, -600000, 600000);
        seek_display_ms_ = std::clamp(seek_display_ms_ + detent_ms, -99000, 99000);
        seek_badge_until_ = now + std::chrono::milliseconds(1400);
        platter_deg_ = std::fmod(platter_deg_ + d * 18.0f + 360.0f, 360.0f);
        last_tick_tp_ = now;
    }

    // Advances physics & generates visual snapshot (~50 Hz main loop cadence).
    ScrubTickOutput tick(std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now()) {
        std::lock_guard<std::mutex> lk(mu_);
        ScrubTickOutput out;
        out.active = hold_active_;

        double dt_s = 0.022;
        if (last_tick_tp_.time_since_epoch().count() > 0) {
            dt_s = std::clamp(std::chrono::duration<double>(now - last_tick_tp_).count(), 0.002, 0.10);
        }
        last_tick_tp_ = now;

        const double since_input_s = (last_scrub_input_tp_.time_since_epoch().count() > 0)
            ? std::chrono::duration<double>(now - last_scrub_input_tp_).count()
            : 999.0;

        if (!hold_active_) {
            if (duck_active_) {
                duck_active_ = false;
                out.duck_changed = true;
                out.duck_active = false;
                ingest_cooldown_until_ = now + std::chrono::milliseconds(380);
            }
            if (seek_accum_ms_ != 0) {
                out.commit_seek = true;
                out.commit_seek_ms = seek_accum_ms_;
                seek_accum_ms_ = 0;
            }
            reset_motion_locked();
            out.visual = make_visual_locked(now);
            return out;
        }

        // Coast platter inertia smoothly between rotary clicks.
        if (since_input_s > 0.055) {
            const float brake = static_cast<float>(std::exp(-dt_s / 0.085));
            target_omega_ *= brake;
            if (std::abs(target_omega_) < 0.04f) target_omega_ = 0.0f;
        }
        platter_deg_ = std::fmod(platter_deg_ + omega_ * static_cast<float>(dt_s * 360.0) + 3600.0f, 360.0f);

        // Expire stale live-wave flag only if buffer itself is empty; if a track paused
        // with valid captured audio, keep its 6s audio usable for scrubbing!
        if (duck_active_ != prev_reported_duck_) {
            prev_reported_duck_ = duck_active_;
            out.duck_changed = true;
            out.duck_active = duck_active_;
        }
        out.duck_active = duck_active_;
        out.visual = make_visual_locked(now);
        return out;
    }

    // Real-time stereo 48 kHz scrub synthesis exclusively from captured track PCM.
    // Returns false and writes silence when inactive, stopped, or no live track captured.
    bool render_scrub_audio(int16_t* out_stereo, size_t frames) {
        if (!out_stereo || frames == 0) return false;
        std::lock_guard<std::mutex> lk(mu_);
        if (!hold_active_ || !scrub_ever_moved_ || !wave_has_live_ || total_ingested_ < 2048) {
            std::memset(out_stereo, 0, frames * 2 * sizeof(int16_t));
            return false;
        }

        if (std::abs(omega_) < 0.02f && std::abs(target_omega_) < 0.02f && env_ <= 0.003f) {
            omega_ = 0.0f;
            env_ = 0.0f;
            std::memset(out_stereo, 0, frames * 2 * sizeof(int16_t));
            return false;
        }

        const uint64_t avail = std::min<uint64_t>(total_ingested_, kWaveCapacity);
        const double min_cur = static_cast<double>(total_ingested_ - avail) + 4.0;
        const double max_cur = static_cast<double>(total_ingested_) - 4.0;
        if (max_cur - min_cur < 1024.0) {
            std::memset(out_stereo, 0, frames * 2 * sizeof(int16_t));
            return false;
        }
        if (cursor_ < min_cur || cursor_ > max_cur) {
            cursor_ = std::clamp(cursor_, min_cur, max_cur);
        }

        // Loop grain size when hitting either wall during continuous multi-second spin.
        const double edge_span = std::min(21600.0, (max_cur - min_cur) * 0.40);
        const float block_dt = 1.0f / static_cast<float>(kSampleRate);
        const float omega_slew = 1.0f - std::exp(-block_dt / 0.012f);

        bool any_sound = false;
        for (size_t i = 0; i < frames; ++i) {
            // Fast tactile platter follow + smooth exponential braking.
            const float tgt = target_omega_;
            if (std::abs(tgt) > 0.02f) {
                omega_ += (tgt - omega_) * omega_slew;
            } else {
                omega_ *= 0.99974f; // ~80 ms exponential spin-down per sample @ 48k
                if (std::abs(omega_) < 0.025f) omega_ = 0.0f;
            }

            const float abs_v = std::abs(omega_);
            const float target_env = (abs_v > 0.03f)
                ? std::clamp(0.18f + abs_v * 0.65f, 0.0f, 1.0f)
                : 0.0f;
            const float env_k = (target_env > env_) ? 0.030f : 0.006f;
            env_ += (target_env - env_) * env_k;

            double prev_cursor = cursor_;
            cursor_ += static_cast<double>(omega_);

            // Smooth crossfaded edge grain wrap if user spins past the 6s ring bounds
            // so real track groove continues instead of cutting out or buzzing.
            float wrap_xfade = 1.0f;
            if (cursor_ >= max_cur - 64.0) {
                if (edge_span > 512.0 && omega_ > 0.0f) {
                    wrap_fade_target_ = std::max(min_cur, max_cur - edge_span);
                    wrap_fade_pos_ = prev_cursor;
                    wrap_fade_rem_ = 96;
                    cursor_ = wrap_fade_target_;
                } else {
                    cursor_ = max_cur - 64.0;
                }
            } else if (cursor_ <= min_cur + 64.0) {
                if (edge_span > 512.0 && omega_ < 0.0f) {
                    wrap_fade_target_ = std::min(max_cur - 64.0, min_cur + edge_span);
                    wrap_fade_pos_ = prev_cursor;
                    wrap_fade_rem_ = 96;
                    cursor_ = wrap_fade_target_;
                } else {
                    cursor_ = min_cur + 64.0;
                }
            }

            float raw = sample_cubic_locked(cursor_);
            if (wrap_fade_rem_ > 0) {
                wrap_fade_pos_ = std::clamp(wrap_fade_pos_ + static_cast<double>(omega_), min_cur, max_cur);
                const float old_s = sample_cubic_locked(wrap_fade_pos_);
                const float alpha = static_cast<float>(96 - wrap_fade_rem_) / 96.0f;
                raw = old_s * (1.0f - alpha) + raw * alpha;
                --wrap_fade_rem_;
            }
            (void)wrap_xfade;

            // Gentle speed-proportional low-pass ONLY on the real audio:
            // - Warm vinyl friction roll-off when dragging very slowly (<0.3x)
            // - Anti-aliasing ceiling when spinning fast (>1.3x)
            float cutoff_hz = std::clamp(650.0f + abs_v * 9500.0f, 550.0f, 15000.0f);
            if (abs_v > 1.25f) {
                cutoff_hz = std::min(cutoff_hz, 20000.0f / abs_v);
            }
            const float fc = std::clamp(cutoff_hz, 400.0f, 16000.0f);
            const float lp_alpha = 1.0f - std::exp(-2.0f * 3.14159265f * fc / static_cast<float>(kSampleRate));
            lp1_ += lp_alpha * (raw - lp1_);
            lp2_ += lp_alpha * (lp1_ - lp2_);

            const float out_f = std::tanh(lp2_ * env_ * 1.45f) * 23500.0f;
            const int16_t s = static_cast<int16_t>(std::clamp(static_cast<int>(std::lround(out_f)), -32000, 32000));
            if (s != 0) any_sound = true;
            out_stereo[i * 2] = s;
            out_stereo[i * 2 + 1] = s;
        }
        return any_sound || env_ > 0.005f;
    }

    ScrubVisualSnapshot visual_snapshot(std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now()) const {
        std::lock_guard<std::mutex> lk(mu_);
        return make_visual_locked(now);
    }

private:
    float read_ring_rel(uint64_t linear_idx) const {
        return wave_ring_[static_cast<size_t>(linear_idx % kWaveCapacity)];
    }

    float sample_cubic_locked(double linear_pos) const {
        const uint64_t avail = std::min<uint64_t>(total_ingested_, kWaveCapacity);
        if (avail < 8) return 0.0f;
        const double lo = static_cast<double>(total_ingested_ - avail) + 2.0;
        const double hi = static_cast<double>(total_ingested_) - 3.0;
        const double p = std::clamp(linear_pos, lo, hi);
        const uint64_t i1 = static_cast<uint64_t>(std::floor(p));
        const float t = static_cast<float>(p - static_cast<double>(i1));

        const float y0 = read_ring_rel(i1 - 1);
        const float y1 = read_ring_rel(i1);
        const float y2 = read_ring_rel(i1 + 1);
        const float y3 = read_ring_rel(i1 + 2);

        // 4-point Catmull-Rom cubic Hermite interpolation
        const float c0 = y1;
        const float c1 = 0.5f * (y2 - y0);
        const float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
        const float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        return ((c3 * t + c2) * t + c1) * t + c0;
    }

    // Detects exact sample shift when Visualizer polls faster or slower than AudioOut_D's 1024-frame period.
    size_t find_overlap_locked(const float* cur, size_t cur_len) const {
        if (total_ingested_ < 256 || cur_len < 128) return 0;
        // Exact contiguous check first (most common: 1024-sample block right after previous).
        // If Visualizer window shifted by < cur_len (partial overlap), compare tail vs head prefix.
        const size_t check_len = std::min<size_t>(48, cur_len / 4);
        const size_t max_ov = std::min<size_t>(cur_len - check_len, 768);
        size_t best_ov = 0;
        double best_score = 0.88; // High threshold avoids false shifted matches on tonal waves.

        // Test shifted overlaps (skip 0: zero-overlap is default unless a true shift matches strongly).
        for (size_t ov = 32; ov <= max_ov; ov += 16) {
            double num = 0.0;
            double e_a = 0.0;
            double e_b = 0.0;
            const uint64_t start_lin = total_ingested_ - ov;
            for (size_t k = 0; k < check_len; ++k) {
                const float a = read_ring_rel(start_lin + k);
                const float b = cur[k];
                num += static_cast<double>(a) * static_cast<double>(b);
                e_a += static_cast<double>(a) * static_cast<double>(a);
                e_b += static_cast<double>(b) * static_cast<double>(b);
            }
            const double denom = std::sqrt(e_a * e_b);
            if (denom > 1e-4) {
                const double corr = num / denom;
                if (corr > best_score) {
                    best_score = corr;
                    best_ov = ov;
                }
            }
        }
        return best_ov;
    }

    void append_block_locked(const float* src, size_t count, float presence) {
        if (count == 0) return;
        const size_t xfade = (total_ingested_ >= kSeamCrossfadeSamples && count >= kSeamCrossfadeSamples && presence > 0.0f)
            ? kSeamCrossfadeSamples : 0;

        for (size_t i = 0; i < count; ++i) {
            float sample = src ? src[i] : 0.0f;
            if (xfade > 0 && i < xfade) {
                // Smooth 0.5 ms Hann edge blend against extrapolated end slope to kill 47 Hz seam clicks.
                const float prev_end = read_ring_rel(total_ingested_ - 1);
                const float fade = static_cast<float>(i + 1) / static_cast<float>(xfade);
                const float w = 0.5f - 0.5f * std::cos(fade * 3.14159265f);
                const float dec = prev_end * (1.0f - fade);
                sample = dec * (1.0f - w) + sample * w;
            }
            const size_t widx = static_cast<size_t>(total_ingested_ % kWaveCapacity);
            wave_ring_[widx] = sample;
            ++total_ingested_;
        }
    }

    void anchor_cursor_locked() {
        const uint64_t avail = std::min<uint64_t>(total_ingested_, kWaveCapacity);
        if (avail >= 4096) {
            // Leave ~0.35s headroom ahead of cursor so immediate clockwise scratches
            // play forward real track audio right away, with ~5.65s behind for rewind!
            const uint64_t headroom = std::min<uint64_t>(16800, avail / 3);
            cursor_ = static_cast<double>(total_ingested_ - headroom);
        } else {
            cursor_ = static_cast<double>(total_ingested_);
        }
    }

    void reset_motion_locked() {
        omega_ = 0.0f;
        target_omega_ = 0.0f;
        env_ = 0.0f;
        lp1_ = 0.0f;
        lp2_ = 0.0f;
        wrap_fade_rem_ = 0;
        last_detent_tp_ = {};
        last_scrub_input_tp_ = {};
        prev_reported_duck_ = false;
    }

    ScrubVisualSnapshot make_visual_locked(std::chrono::steady_clock::time_point now) const {
        ScrubVisualSnapshot v;
        v.active = hold_active_;
        v.velocity = omega_;
        v.platter_deg = platter_deg_;
        v.seek_display_ms = (now < seek_badge_until_) ? seek_display_ms_ : 0;
        v.seek_badge_until = seek_badge_until_;
        v.has_live_wave = wave_has_live_;
        v.scrubbing_audio = hold_active_ && duck_active_ && (std::abs(omega_) > 0.05f || env_ > 0.02f);
        return v;
    }

    mutable std::mutex mu_;
    std::vector<float> wave_ring_;
    std::vector<float> ingest_scratch_;
    uint64_t total_ingested_ = 0;
    bool wave_has_live_ = false;
    float ref_song_rms_ = 0.0f;
    float last_ingest_gain_ = 0.0f;

    bool hold_active_ = false;
    bool duck_active_ = false;
    bool prev_reported_duck_ = false;
    bool scrub_ever_moved_ = false;

    double cursor_ = 0.0;
    float omega_ = 0.0f;
    float target_omega_ = 0.0f;
    float env_ = 0.0f;
    float lp1_ = 0.0f;
    float lp2_ = 0.0f;
    float platter_deg_ = 0.0f;

    double wrap_fade_target_ = 0.0;
    double wrap_fade_pos_ = 0.0;
    int wrap_fade_rem_ = 0;

    int seek_accum_ms_ = 0;
    int seek_display_ms_ = 0;
    std::chrono::steady_clock::time_point seek_badge_until_{};
    std::chrono::steady_clock::time_point last_detent_tp_{};
    std::chrono::steady_clock::time_point last_scrub_input_tp_{};
    std::chrono::steady_clock::time_point last_tick_tp_{};
    std::chrono::steady_clock::time_point last_wave_tp_{};
    std::chrono::steady_clock::time_point ingest_cooldown_until_{};
};

} // namespace openstation
