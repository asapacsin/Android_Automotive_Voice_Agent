#include "nova_aec.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

#include "resampler/push_sinc_resampler.h"
#include "webrtc_aec3_c.h"

namespace {

constexpr int kAecSampleRateHz = 16000;
constexpr int kFrameSamples = kAecSampleRateHz / 100;  // 10 ms
constexpr int kFrameBytes = kFrameSamples * static_cast<int>(sizeof(int16_t));

void WritePcm16ToBuffer(webrtc_aec3_audio_buffer_t* buffer, const int16_t* pcm, int samples) {
    float* channel = webrtc_aec3_audio_buffer_get_channel_data(buffer, 0);
    for (int i = 0; i < samples; ++i) {
        channel[i] = static_cast<float>(pcm[i]);
    }
}

void ReadPcm16FromBuffer(const webrtc_aec3_audio_buffer_t* buffer, int16_t* pcm, int samples) {
    const float* channel = webrtc_aec3_audio_buffer_get_channel_data_const(buffer, 0);
    for (int i = 0; i < samples; ++i) {
        const float clamped = std::max(-32768.0f, std::min(32767.0f, channel[i]));
        pcm[i] = static_cast<int16_t>(clamped);
    }
}

double RmsPcm16(const int16_t* pcm, size_t samples) {
    if (samples == 0) {
        return 0.0;
    }
    double sum = 0.0;
    for (size_t i = 0; i < samples; ++i) {
        const double sample = static_cast<double>(pcm[i]);
        sum += sample * sample;
    }
    return std::sqrt(sum / static_cast<double>(samples));
}

class RenderResampler {
public:
    explicit RenderResampler(int source_rate_hz)
        : source_rate_hz_(source_rate_hz),
          source_block_samples_(source_rate_hz / 100),
          resampler_(std::make_unique<webrtc::PushSincResampler>(source_block_samples_, kFrameSamples)) {}

    bool ResampleBlock(const int16_t* source, int16_t* destination) {
        return resampler_->Resample(source, source_block_samples_, destination, kFrameSamples) == kFrameSamples;
    }

    int sourceBlockSamples() const { return source_block_samples_; }

private:
    int source_rate_hz_;
    int source_block_samples_;
    std::unique_ptr<webrtc::PushSincResampler> resampler_;
};

}  // namespace

struct NovaAec::Impl {
    webrtc_aec3_environment_t* environment = nullptr;
    webrtc_aec3_factory_t* factory = nullptr;
    webrtc_aec3_echo_control_t* echo_control = nullptr;
    webrtc_aec3_audio_buffer_t* render_buffer = nullptr;
    webrtc_aec3_audio_buffer_t* capture_buffer = nullptr;

    std::vector<int16_t> render_source_leftover;
    std::vector<int16_t> render_aec_leftover;
    std::vector<int16_t> capture_leftover;

    std::unique_ptr<RenderResampler> render_resampler;
    int render_source_rate_hz = kAecSampleRateHz;

    int stream_delay_ms = 0;
    NovaAec::Stats stats{};
};

NovaAec::NovaAec() : impl_(std::make_unique<Impl>()) {
    impl_->environment = webrtc_aec3_environment_create();
    impl_->factory = webrtc_aec3_factory_create();
    impl_->echo_control = webrtc_aec3_echo_control_create(
        impl_->factory, impl_->environment, kAecSampleRateHz, 1, 1);
    impl_->render_buffer = webrtc_aec3_audio_buffer_create(kAecSampleRateHz, 1);
    impl_->capture_buffer = webrtc_aec3_audio_buffer_create(kAecSampleRateHz, 1);
}

NovaAec::~NovaAec() {
    if (impl_->render_buffer != nullptr) {
        webrtc_aec3_audio_buffer_destroy(impl_->render_buffer);
    }
    if (impl_->capture_buffer != nullptr) {
        webrtc_aec3_audio_buffer_destroy(impl_->capture_buffer);
    }
    if (impl_->echo_control != nullptr) {
        webrtc_aec3_echo_control_destroy(impl_->echo_control);
    }
    if (impl_->factory != nullptr) {
        webrtc_aec3_factory_destroy(impl_->factory);
    }
    if (impl_->environment != nullptr) {
        webrtc_aec3_environment_destroy(impl_->environment);
    }
}

void NovaAec::SetStreamDelayMs(int delay_ms) {
    const int clamped = std::max(0, std::min(delay_ms, 500));
    impl_->stream_delay_ms = clamped;
    webrtc_aec3_echo_control_set_audio_buffer_delay(impl_->echo_control, clamped);
}

void NovaAec::EnsureRenderResampler(int sample_rate_hz) {
    if (impl_->render_source_rate_hz == sample_rate_hz && impl_->render_resampler != nullptr) {
        return;
    }
    impl_->render_source_rate_hz = sample_rate_hz;
    impl_->render_source_leftover.clear();
    impl_->render_aec_leftover.clear();
    if (sample_rate_hz == kAecSampleRateHz) {
        impl_->render_resampler.reset();
        return;
    }
    impl_->render_resampler = std::make_unique<RenderResampler>(sample_rate_hz);
}

void NovaAec::FeedRenderFrame(const int16_t* frame) {
    WritePcm16ToBuffer(impl_->render_buffer, frame, kFrameSamples);
    webrtc_aec3_echo_control_analyze_render(impl_->echo_control, impl_->render_buffer);
    impl_->stats.render_frames_processed += 1;
}

void NovaAec::ProcessRender(const int16_t* pcm, size_t sample_count, int sample_rate_hz) {
    if (pcm == nullptr || sample_count == 0) {
        return;
    }
    impl_->stats.last_render_rms = RmsPcm16(pcm, sample_count);
    EnsureRenderResampler(sample_rate_hz);

    if (sample_rate_hz == kAecSampleRateHz) {
        impl_->render_source_leftover.insert(
            impl_->render_source_leftover.end(), pcm, pcm + sample_count);
        while (impl_->render_source_leftover.size() >= kFrameSamples) {
            FeedRenderFrame(impl_->render_source_leftover.data());
            impl_->render_source_leftover.erase(
                impl_->render_source_leftover.begin(),
                impl_->render_source_leftover.begin() + kFrameSamples);
        }
        return;
    }

    impl_->render_source_leftover.insert(
        impl_->render_source_leftover.end(), pcm, pcm + sample_count);
    const int block_samples = impl_->render_resampler->sourceBlockSamples();
    std::vector<int16_t> resampled(kFrameSamples);
    while (impl_->render_source_leftover.size() >= static_cast<size_t>(block_samples)) {
        if (!impl_->render_resampler->ResampleBlock(impl_->render_source_leftover.data(), resampled.data())) {
            impl_->stats.render_leftover_drops += 1;
            break;
        }
        FeedRenderFrame(resampled.data());
        impl_->render_source_leftover.erase(
            impl_->render_source_leftover.begin(),
            impl_->render_source_leftover.begin() + block_samples);
    }
}

std::vector<int16_t> NovaAec::ProcessCapture(const int16_t* pcm, size_t sample_count) {
    std::vector<int16_t> output;
    if (pcm == nullptr || sample_count == 0) {
        return output;
    }

    impl_->stats.last_raw_rms = RmsPcm16(pcm, sample_count);
    webrtc_aec3_echo_control_set_audio_buffer_delay(impl_->echo_control, impl_->stream_delay_ms);

    impl_->capture_leftover.insert(impl_->capture_leftover.end(), pcm, pcm + sample_count);
    output.reserve(impl_->capture_leftover.size());

    std::vector<int16_t> frame(kFrameSamples);
    while (impl_->capture_leftover.size() >= kFrameSamples) {
        std::copy_n(impl_->capture_leftover.begin(), kFrameSamples, frame.begin());
        WritePcm16ToBuffer(impl_->capture_buffer, frame.data(), kFrameSamples);
        webrtc_aec3_echo_control_analyze_capture(impl_->echo_control, impl_->capture_buffer);
        webrtc_aec3_echo_control_process_capture(impl_->echo_control, impl_->capture_buffer, false);
        ReadPcm16FromBuffer(impl_->capture_buffer, frame.data(), kFrameSamples);
        output.insert(output.end(), frame.begin(), frame.end());
        impl_->capture_leftover.erase(
            impl_->capture_leftover.begin(),
            impl_->capture_leftover.begin() + kFrameSamples);
        impl_->stats.capture_frames_processed += 1;
    }

    impl_->stats.last_post_aec_rms = output.empty() ? 0.0 : RmsPcm16(output.data(), output.size());
    return output;
}

NovaAec::Stats NovaAec::GetStats() const {
    return impl_->stats;
}
