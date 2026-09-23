/*
 *  Copyright (c) 2025 The WebRTC AEC3 Java project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree.
 */

#include "webrtc_aec3_c.h"

#include <new>

#include "api/echo_canceller3_config.h"
#include "api/echo_canceller3_config_json.h"
#include "api/echo_canceller3_factory.h"
#include "api/echo_control.h"
#include "api/environment.h"
#include "api/field_trials_view.h"
#include "api/neural_residual_echo_estimator.h"
#include "audio_processing/audio_buffer.h"

// ---------------------------------------------------------------------------
// Opaque struct definitions
// ---------------------------------------------------------------------------
struct webrtc_aec3_config_t { webrtc::EchoCanceller3Config impl; };
struct webrtc_aec3_config_ex_t { int dummy; };
struct webrtc_aec3_environment_t { webrtc::Environment impl; };
struct webrtc_aec3_factory_t {
    webrtc::EchoCanceller3Factory impl;
    webrtc_aec3_factory_t() : impl() {}
    explicit webrtc_aec3_factory_t(const webrtc::EchoCanceller3Config& config) : impl(config) {}
    webrtc_aec3_factory_t(const webrtc::EchoCanceller3Config& config,
                          const webrtc::EchoCanceller3Config& multichannel_config)
        : impl(config, multichannel_config) {}
};
struct webrtc_aec3_echo_control_t { std::unique_ptr<webrtc::EchoControl> impl; };
struct webrtc_aec3_audio_buffer_t { webrtc::AudioBuffer impl; };

// ---------------------------------------------------------------------------
// Version / Info
// ---------------------------------------------------------------------------
const char* webrtc_aec3_version(void) {
  return "WebRTC AEC3 1.0.0";
}

// ---------------------------------------------------------------------------
// EchoCanceller3Config
// ---------------------------------------------------------------------------
webrtc_aec3_config_t* webrtc_aec3_config_create_default(void) {
  return new webrtc_aec3_config_t{{}};
}

webrtc_aec3_config_t* webrtc_aec3_config_create_default_multichannel(void) {
  auto* config = new webrtc_aec3_config_t{{}};
  config->impl = webrtc::EchoCanceller3Config::CreateDefaultMultichannelConfig();
  return config;
}

webrtc_aec3_config_t* webrtc_aec3_config_clone(
    const webrtc_aec3_config_t* config) {
  return new webrtc_aec3_config_t{config->impl};
}

void webrtc_aec3_config_destroy(webrtc_aec3_config_t* config) {
  delete config;
}

bool webrtc_aec3_config_validate(webrtc_aec3_config_t* config) {
  return webrtc::EchoCanceller3Config::Validate(&config->impl);
}

// ---------------------------------------------------------------------------
// JSON serialization
// ---------------------------------------------------------------------------
webrtc_aec3_config_t* webrtc_aec3_config_from_json(
    const char* json_string, bool* parsing_successful) {
  auto* config = new webrtc_aec3_config_t{{}};
  webrtc::Aec3ConfigFromJsonString(json_string, &config->impl,
                                   parsing_successful);
  return config;
}

char* webrtc_aec3_config_to_json(const webrtc_aec3_config_t* config) {
  auto json = webrtc::Aec3ConfigToJsonString(config->impl);
  auto* cstr = static_cast<char*>(operator new(json.size() + 1));
  std::copy(json.begin(), json.end(), cstr);
  cstr[json.size()] = '\0';
  return cstr;
}

// ---------------------------------------------------------------------------
// Config setters
// ---------------------------------------------------------------------------
void webrtc_aec3_config_set_delay_default_delay(
    webrtc_aec3_config_t* config, size_t value) {
  config->impl.delay.default_delay = value;
}

void webrtc_aec3_config_set_filter_initial_state_seconds(
    webrtc_aec3_config_t* config, float value) {
  config->impl.filter.initial_state_seconds = value;
}

void webrtc_aec3_config_set_filter_conservative_initial_phase(
    webrtc_aec3_config_t* config, bool value) {
  config->impl.filter.conservative_initial_phase = value;
}

// ---------------------------------------------------------------------------
// Environment
// ---------------------------------------------------------------------------
webrtc_aec3_environment_t* webrtc_aec3_environment_create(void) {
  return new webrtc_aec3_environment_t{{}};
}

void webrtc_aec3_environment_destroy(webrtc_aec3_environment_t* env) {
  delete env;
}

// ---------------------------------------------------------------------------
// AudioBuffer
// ---------------------------------------------------------------------------
webrtc_aec3_audio_buffer_t* webrtc_aec3_audio_buffer_create(
    int sample_rate_hz, int num_channels) {
  void* mem = operator new(sizeof(webrtc_aec3_audio_buffer_t));
  auto* buffer = static_cast<webrtc_aec3_audio_buffer_t*>(mem);
  ::new (&buffer->impl)
      webrtc::AudioBuffer(sample_rate_hz, num_channels, sample_rate_hz,
                          num_channels, sample_rate_hz, num_channels);
  return buffer;
}

webrtc_aec3_audio_buffer_t* webrtc_aec3_audio_buffer_create_from_data(
    const float* const* channel_data, int num_channels,
    int samples_per_channel) {
  void* mem = operator new(sizeof(webrtc_aec3_audio_buffer_t));
  auto* buffer = static_cast<webrtc_aec3_audio_buffer_t*>(mem);
  ::new (&buffer->impl)
      webrtc::AudioBuffer(samples_per_channel, num_channels,
                          samples_per_channel, num_channels,
                          samples_per_channel);

  // Copy data into the buffer.
  float* const* channels = buffer->impl.channels();
  for (int ch = 0; ch < num_channels; ++ch) {
    std::copy(channel_data[ch], channel_data[ch] + samples_per_channel,
              channels[ch]);
  }
  return buffer;
}

void webrtc_aec3_audio_buffer_destroy(webrtc_aec3_audio_buffer_t* buffer) {
  if (buffer) {
    buffer->impl.~AudioBuffer();
    operator delete(buffer);
  }
}

float* webrtc_aec3_audio_buffer_get_channel_data(
    webrtc_aec3_audio_buffer_t* buffer, int channel) {
  return buffer->impl.channels()[channel];
}

const float* webrtc_aec3_audio_buffer_get_channel_data_const(
    const webrtc_aec3_audio_buffer_t* buffer, int channel) {
  return buffer->impl.channels_const()[channel];
}

int webrtc_aec3_audio_buffer_num_channels(
    const webrtc_aec3_audio_buffer_t* buffer) {
  return static_cast<int>(buffer->impl.num_channels());
}

int webrtc_aec3_audio_buffer_samples_per_channel(
    const webrtc_aec3_audio_buffer_t* buffer) {
  return static_cast<int>(buffer->impl.num_frames());
}

// ---------------------------------------------------------------------------
// EchoCanceller3Factory
// ---------------------------------------------------------------------------
webrtc_aec3_factory_t* webrtc_aec3_factory_create(void) {
  return new webrtc_aec3_factory_t{{}};
}

webrtc_aec3_factory_t* webrtc_aec3_factory_create_with_config(
    const webrtc_aec3_config_t* config) {
  return new webrtc_aec3_factory_t{config->impl};
}

webrtc_aec3_factory_t* webrtc_aec3_factory_create_with_multichannel(
    const webrtc_aec3_config_t* config,
    const webrtc_aec3_config_t* multichannel_config) {
  return new webrtc_aec3_factory_t{config->impl,
                                   multichannel_config->impl};
}

void webrtc_aec3_factory_destroy(webrtc_aec3_factory_t* factory) {
  delete factory;
}

// ---------------------------------------------------------------------------
// EchoControl
// ---------------------------------------------------------------------------
webrtc_aec3_echo_control_t* webrtc_aec3_echo_control_create(
    webrtc_aec3_factory_t* factory,
    webrtc_aec3_environment_t* env,
    int sample_rate_hz,
    int num_render_channels,
    int num_capture_channels) {
  auto* ec = new webrtc_aec3_echo_control_t;
  ec->impl = factory->impl.Create(env->impl, sample_rate_hz,
                                  num_render_channels, num_capture_channels);
  return ec;
}

void webrtc_aec3_echo_control_destroy(webrtc_aec3_echo_control_t* ec) {
  delete ec;
}

// AEC3 reads and writes the split-band representation of an AudioBuffer, which
// is only populated by SplitIntoFrequencyBands(). At 16 kHz the buffer has a
// single band, so the split view *is* the full-band view and the conversion is
// a no-op; at 32/48 kHz it is not, and without it the echo canceller sees an
// empty render signal and never converges.
void webrtc_aec3_echo_control_analyze_render(
    webrtc_aec3_echo_control_t* ec,
    webrtc_aec3_audio_buffer_t* render) {
  render->impl.SplitIntoFrequencyBands();
  ec->impl->AnalyzeRender(&render->impl);
}

void webrtc_aec3_echo_control_analyze_capture(
    webrtc_aec3_echo_control_t* ec,
    webrtc_aec3_audio_buffer_t* capture) {
  capture->impl.SplitIntoFrequencyBands();
  ec->impl->AnalyzeCapture(&capture->impl);
}

void webrtc_aec3_echo_control_process_capture(
    webrtc_aec3_echo_control_t* ec,
    webrtc_aec3_audio_buffer_t* capture,
    bool level_change) {
  capture->impl.SplitIntoFrequencyBands();
  ec->impl->ProcessCapture(&capture->impl, level_change);
  // The processed signal lands in the split bands; the caller reads the
  // full-band data back.
  capture->impl.MergeFrequencyBands();
}

void webrtc_aec3_echo_control_process_capture_with_output(
    webrtc_aec3_echo_control_t* ec,
    webrtc_aec3_audio_buffer_t* capture,
    webrtc_aec3_audio_buffer_t* linear_output,
    bool level_change) {
  capture->impl.SplitIntoFrequencyBands();
  ec->impl->ProcessCapture(&capture->impl, &linear_output->impl,
                            level_change);
  capture->impl.MergeFrequencyBands();
  linear_output->impl.MergeFrequencyBands();
}

webrtc_aec3_metrics_t webrtc_aec3_echo_control_get_metrics(
    webrtc_aec3_echo_control_t* ec) {
  auto metrics = ec->impl->GetMetrics();
  webrtc_aec3_metrics_t result;
  result.echo_return_loss = metrics.echo_return_loss;
  result.echo_return_loss_enhancement = metrics.echo_return_loss_enhancement;
  result.delay_ms = metrics.delay_ms;
  return result;
}

void webrtc_aec3_echo_control_set_audio_buffer_delay(
    webrtc_aec3_echo_control_t* ec, int delay_ms) {
  ec->impl->SetAudioBufferDelay(delay_ms);
}

bool webrtc_aec3_echo_control_active_processing(
    webrtc_aec3_echo_control_t* ec) {
  return ec->impl->ActiveProcessing();
}

// ---------------------------------------------------------------------------
// Free string
// ---------------------------------------------------------------------------
void webrtc_aec3_free_string(char* str) {
  operator delete(str);
}