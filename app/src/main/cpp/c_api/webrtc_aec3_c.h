/*
 *  Copyright (c) 2025 The WebRTC AEC3 Java project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree.
 */

#ifndef WEBRTC_AEC3_C_H_
#define WEBRTC_AEC3_C_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* =========================================================================
 * Opaque handle types - actual C++ objects are hidden behind these pointers
 * ========================================================================= */
typedef struct webrtc_aec3_config_t          webrtc_aec3_config_t;
typedef struct webrtc_aec3_config_ex_t       webrtc_aec3_config_ex_t;
typedef struct webrtc_aec3_environment_t     webrtc_aec3_environment_t;
typedef struct webrtc_aec3_factory_t         webrtc_aec3_factory_t;
typedef struct webrtc_aec3_echo_control_t    webrtc_aec3_echo_control_t;
typedef struct webrtc_aec3_audio_buffer_t    webrtc_aec3_audio_buffer_t;

/* =========================================================================
 * Metrics
 * ========================================================================= */
typedef struct {
    double echo_return_loss;
    double echo_return_loss_enhancement;
    int    delay_ms;
} webrtc_aec3_metrics_t;

/* =========================================================================
 * Version / Info
 * ========================================================================= */
const char* webrtc_aec3_version(void);

/* =========================================================================
 * EchoCanceller3Config
 * ========================================================================= */
webrtc_aec3_config_t* webrtc_aec3_config_create_default(void);
webrtc_aec3_config_t* webrtc_aec3_config_create_default_multichannel(void);
webrtc_aec3_config_t* webrtc_aec3_config_clone(const webrtc_aec3_config_t* config);
void webrtc_aec3_config_destroy(webrtc_aec3_config_t* config);
bool webrtc_aec3_config_validate(webrtc_aec3_config_t* config);

/* JSON serialization - caller must free returned string with webrtc_aec3_free_string */
webrtc_aec3_config_t* webrtc_aec3_config_from_json(const char* json_string, bool* parsing_successful);
char* webrtc_aec3_config_to_json(const webrtc_aec3_config_t* config);

/* Convenience setters for commonly used config fields */
void webrtc_aec3_config_set_delay_default_delay(webrtc_aec3_config_t* config, size_t value);
void webrtc_aec3_config_set_filter_initial_state_seconds(webrtc_aec3_config_t* config, float value);
void webrtc_aec3_config_set_filter_conservative_initial_phase(webrtc_aec3_config_t* config, bool value);

/* =========================================================================
 * Environment
 * ========================================================================= */
webrtc_aec3_environment_t* webrtc_aec3_environment_create(void);
void webrtc_aec3_environment_destroy(webrtc_aec3_environment_t* env);

/* =========================================================================
 * AudioBuffer
 *
 * Two creation modes:
 *   1) Create with sample rate and channels (internal allocation).
 *      Write data via webrtc_aec3_audio_buffer_get_channel_data().
 *   2) Create from external float data.
 *      The data is copied internally.
 * ========================================================================= */
webrtc_aec3_audio_buffer_t* webrtc_aec3_audio_buffer_create(
    int sample_rate_hz, int num_channels);

webrtc_aec3_audio_buffer_t* webrtc_aec3_audio_buffer_create_from_data(
    const float* const* channel_data, int num_channels, int samples_per_channel);

void webrtc_aec3_audio_buffer_destroy(webrtc_aec3_audio_buffer_t* buffer);

/* Get writable pointer to channel data (for filling data before processing).
 * Returns a pointer to float[num_channels] for reading/writing samples.
 * Use channel_data[ch][sample] to access individual samples. */
float* webrtc_aec3_audio_buffer_get_channel_data(
    webrtc_aec3_audio_buffer_t* buffer, int channel);

/* Get read-only channel data (after processing). */
const float* webrtc_aec3_audio_buffer_get_channel_data_const(
    const webrtc_aec3_audio_buffer_t* buffer, int channel);

int webrtc_aec3_audio_buffer_num_channels(const webrtc_aec3_audio_buffer_t* buffer);
int webrtc_aec3_audio_buffer_samples_per_channel(const webrtc_aec3_audio_buffer_t* buffer);

/* =========================================================================
 * EchoCanceller3Factory
 * ========================================================================= */
webrtc_aec3_factory_t* webrtc_aec3_factory_create(void);
webrtc_aec3_factory_t* webrtc_aec3_factory_create_with_config(
    const webrtc_aec3_config_t* config);
webrtc_aec3_factory_t* webrtc_aec3_factory_create_with_multichannel(
    const webrtc_aec3_config_t* config,
    const webrtc_aec3_config_t* multichannel_config);
void webrtc_aec3_factory_destroy(webrtc_aec3_factory_t* factory);

/* =========================================================================
 * EchoControl
 * ========================================================================= */
webrtc_aec3_echo_control_t* webrtc_aec3_echo_control_create(
    webrtc_aec3_factory_t* factory,
    webrtc_aec3_environment_t* env,
    int sample_rate_hz,
    int num_render_channels,
    int num_capture_channels);

void webrtc_aec3_echo_control_destroy(webrtc_aec3_echo_control_t* ec);

void webrtc_aec3_echo_control_analyze_render(
    webrtc_aec3_echo_control_t* ec, webrtc_aec3_audio_buffer_t* render);

void webrtc_aec3_echo_control_analyze_capture(
    webrtc_aec3_echo_control_t* ec, webrtc_aec3_audio_buffer_t* capture);

void webrtc_aec3_echo_control_process_capture(
    webrtc_aec3_echo_control_t* ec,
    webrtc_aec3_audio_buffer_t* capture,
    bool level_change);

void webrtc_aec3_echo_control_process_capture_with_output(
    webrtc_aec3_echo_control_t* ec,
    webrtc_aec3_audio_buffer_t* capture,
    webrtc_aec3_audio_buffer_t* linear_output,
    bool level_change);

webrtc_aec3_metrics_t webrtc_aec3_echo_control_get_metrics(
    webrtc_aec3_echo_control_t* ec);

void webrtc_aec3_echo_control_set_audio_buffer_delay(
    webrtc_aec3_echo_control_t* ec, int delay_ms);

bool webrtc_aec3_echo_control_active_processing(
    webrtc_aec3_echo_control_t* ec);

/* Free a string allocated by the library */
void webrtc_aec3_free_string(char* str);

#ifdef __cplusplus
}
#endif

#endif /* WEBRTC_AEC3_C_H_ */