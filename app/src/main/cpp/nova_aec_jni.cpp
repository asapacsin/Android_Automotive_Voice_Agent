#include <jni.h>

#include <vector>

#include "nova_aec.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_novadrive_app_voice_WebRtcAcousticEcho_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new NovaAec());
}

extern "C" JNIEXPORT void JNICALL
Java_com_novadrive_app_voice_WebRtcAcousticEcho_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    delete reinterpret_cast<NovaAec*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_novadrive_app_voice_WebRtcAcousticEcho_nativeSetStreamDelayMs(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jint delay_ms) {
    reinterpret_cast<NovaAec*>(handle)->SetStreamDelayMs(delay_ms);
}

extern "C" JNIEXPORT void JNICALL
Java_com_novadrive_app_voice_WebRtcAcousticEcho_nativeProcessRender(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jbyteArray pcm,
    jint sample_rate_hz) {
    if (handle == 0 || pcm == nullptr) {
        return;
    }
    const jsize byte_len = env->GetArrayLength(pcm);
    if (byte_len <= 0 || (byte_len % 2) != 0) {
        return;
    }
    jbyte* bytes = env->GetByteArrayElements(pcm, nullptr);
    if (bytes == nullptr) {
        return;
    }
    const auto* samples = reinterpret_cast<const int16_t*>(bytes);
    const size_t sample_count = static_cast<size_t>(byte_len / 2);
    reinterpret_cast<NovaAec*>(handle)->ProcessRender(samples, sample_count, sample_rate_hz);
    env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_novadrive_app_voice_WebRtcAcousticEcho_nativeProcessCapture(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jbyteArray pcm) {
    if (handle == 0 || pcm == nullptr) {
        return pcm;
    }
    const jsize byte_len = env->GetArrayLength(pcm);
    if (byte_len <= 0 || (byte_len % 2) != 0) {
        return pcm;
    }
    jbyte* bytes = env->GetByteArrayElements(pcm, nullptr);
    if (bytes == nullptr) {
        return pcm;
    }
    const auto* samples = reinterpret_cast<const int16_t*>(bytes);
    const size_t sample_count = static_cast<size_t>(byte_len / 2);
    const std::vector<int16_t> processed =
        reinterpret_cast<NovaAec*>(handle)->ProcessCapture(samples, sample_count);
    env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);

    if (processed.empty()) {
        return env->NewByteArray(0);
    }
    const jsize out_bytes = static_cast<jsize>(processed.size() * sizeof(int16_t));
    jbyteArray out = env->NewByteArray(out_bytes);
    if (out == nullptr) {
        return env->NewByteArray(0);
    }
    env->SetByteArrayRegion(out, 0, out_bytes, reinterpret_cast<const jbyte*>(processed.data()));
    return out;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_novadrive_app_voice_WebRtcAcousticEcho_nativeGetStats(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    jdoubleArray result = env->NewDoubleArray(7);
    if (result == nullptr || handle == 0) {
        return result;
    }
    const NovaAec::Stats stats = reinterpret_cast<NovaAec*>(handle)->GetStats();
    const jdouble values[7] = {
        stats.last_raw_rms,
        stats.last_post_aec_rms,
        stats.last_render_rms,
        static_cast<jdouble>(stats.render_frames_processed),
        static_cast<jdouble>(stats.capture_frames_processed),
        static_cast<jdouble>(stats.render_leftover_drops),
        0.0,
    };
    env->SetDoubleArrayRegion(result, 0, 7, values);
    return result;
}
