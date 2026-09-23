#ifndef NOVA_AEC_H_
#define NOVA_AEC_H_

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

class NovaAec {
public:
    struct Stats {
        double last_raw_rms = 0.0;
        double last_post_aec_rms = 0.0;
        double last_render_rms = 0.0;
        uint64_t render_frames_processed = 0;
        uint64_t capture_frames_processed = 0;
        uint64_t render_leftover_drops = 0;
    };

    NovaAec();
    ~NovaAec();

    void SetStreamDelayMs(int delay_ms);
    void ProcessRender(const int16_t* pcm, size_t sample_count, int sample_rate_hz);
    std::vector<int16_t> ProcessCapture(const int16_t* pcm, size_t sample_count);
    Stats GetStats() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;

    void EnsureRenderResampler(int sample_rate_hz);
    void FeedRenderFrame(const int16_t* frame);
};

#endif  // NOVA_AEC_H_
