#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "encodec.h"

namespace {

constexpr char TAG[] = "EnCodecDecoder";
constexpr float RESCALE_PEAK = 0.99f;

struct DecoderHandle {
    explicit DecoderHandle(const char* model_path) : decoder(model_path), info(decoder.info()) {}

    encodec::decoder decoder;
    encodec::model_info info;
    std::mutex mutex;
};

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
    if (const jclass type = env->FindClass(class_name)) env->ThrowNew(type, message.c_str());
}

DecoderHandle* from_handle(jlong handle) {
    if (handle == 0) throw std::runtime_error("Native EnCodec decoder is closed");
    return reinterpret_cast<DecoderHandle*>(handle);
}

std::vector<uint8_t> pack_codes(const jint* codes, std::size_t count) {
    std::vector<uint8_t> packed((count * 10 + 7) / 8, 0);
    std::size_t bit_offset = 0;
    for (std::size_t index = 0; index < count; ++index) {
        const int code = codes[index];
        if (code < 0 || code > 1023) throw std::runtime_error("Invalid EnCodec codebook index");
        const uint32_t value = static_cast<uint32_t>(code);
        for (unsigned bit = 0; bit < 10; ++bit, ++bit_offset) {
            packed[bit_offset / 8] |= static_cast<uint8_t>(((value >> bit) & 1u) << (bit_offset % 8));
        }
    }
    return packed;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_henry_encodec_decoder_CppEncodecDecoder_nativeCreate(
    JNIEnv* env, jobject, jstring model_path, jint sample_rate, jint channels) {
    try {
        const char* path = env->GetStringUTFChars(model_path, nullptr);
        if (!path) return 0;
        std::unique_ptr<DecoderHandle> handle;
        try {
            // Use the conservative phone energy policy: no OpenMP wakeups and
            // no duplicate decoder instances competing for CPU time.
            encodec::set_num_threads(1);
            handle = std::make_unique<DecoderHandle>(path);
        } catch (...) {
            env->ReleaseStringUTFChars(model_path, path);
            throw;
        }
        env->ReleaseStringUTFChars(model_path, path);
        if (handle->info.sample_rate != static_cast<unsigned>(sample_rate) ||
            handle->info.channels != static_cast<unsigned>(channels)) {
            throw std::runtime_error("Decoder model does not match the ECDC variant");
        }
        return reinterpret_cast<jlong>(handle.release());
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_henry_encodec_decoder_CppEncodecDecoder_nativeDecode(
    JNIEnv* env, jobject, jlong native_handle, jintArray codes_array,
    jint codebooks, jint time_steps, jint trim_leading_frames,
    jint output_frames, jfloat frame_scale, jboolean rescale, jboolean diagnostics) {
    try {
        DecoderHandle* handle = from_handle(native_handle);
        if (codebooks < 1 || static_cast<unsigned>(codebooks) > handle->info.max_quantizers ||
            time_steps < 1 || output_frames < 0 || trim_leading_frames < 0) {
            throw std::runtime_error("Invalid EnCodec frame dimensions");
        }
        const jsize code_count = env->GetArrayLength(codes_array);
        if (code_count != codebooks * time_steps) {
            throw std::runtime_error("ECDC code array has the wrong size");
        }
        jint* codes = env->GetIntArrayElements(codes_array, nullptr);
        if (!codes) throw std::runtime_error("Could not access ECDC codes");
        std::vector<uint8_t> packet;
        try {
            packet = pack_codes(codes, static_cast<std::size_t>(code_count));
        } catch (...) {
            env->ReleaseIntArrayElements(codes_array, codes, JNI_ABORT);
            throw;
        }
        env->ReleaseIntArrayElements(codes_array, codes, JNI_ABORT);

        const auto started = std::chrono::steady_clock::now();
        std::lock_guard lock(handle->mutex);
        const auto decoded = handle->decoder.decode(
            packet, static_cast<unsigned>(codebooks), static_cast<std::size_t>(time_steps));
        const std::size_t channels = handle->info.channels;
        const std::size_t available_frames = decoded.size() / channels;
        const std::size_t start = std::min<std::size_t>(trim_leading_frames, available_frames);
        const std::size_t wanted = std::min<std::size_t>(
            static_cast<std::size_t>(output_frames), available_frames - start);
        std::vector<float> output(wanted * channels);
        float peak = 0.0f;
        for (std::size_t frame = 0; frame < wanted; ++frame) {
            for (std::size_t channel = 0; channel < channels; ++channel) {
                const float sample = decoded[(start + frame) * channels + channel] * frame_scale;
                output[frame * channels + channel] = sample;
                peak = std::max(peak, std::abs(sample));
            }
        }
        float gain = 1.0f;
        if (rescale == JNI_TRUE && peak > RESCALE_PEAK) {
            gain = RESCALE_PEAK / peak;
            for (float& sample : output) sample *= gain;
        }

        jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
        if (!result) throw std::runtime_error("Could not allocate decoded PCM");
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
        const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count();
        if (diagnostics == JNI_TRUE) {
            __android_log_print(
                ANDROID_LOG_INFO, TAG,
                "C++ decoded %d steps (%d codebooks) in %lldms, peak=%.4f, gain=%.4f, threads=1",
                time_steps, codebooks, static_cast<long long>(elapsed_ms), peak, gain);
        }
        return result;
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_henry_encodec_decoder_CppEncodecDecoder_nativeDestroy(
    JNIEnv* env, jobject, jlong native_handle) {
    try {
        delete from_handle(native_handle);
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
    }
}
