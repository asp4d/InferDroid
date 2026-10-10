#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "sherpa-onnx/c-api/c-api.h"

// Exception-safe extensions are compiled into the same sherpa C API DSO.
extern "C" const SherpaOnnxOfflineTts* SherpaOnnxInferDroidCreateOfflineTts(
        const SherpaOnnxOfflineTtsConfig* config);
extern "C" const SherpaOnnxGeneratedAudio* SherpaOnnxInferDroidGenerateOfflineTts(
        const SherpaOnnxOfflineTts* tts, const char* text,
        const SherpaOnnxGenerationConfig* config, int32_t* status);

namespace {
void Error(JNIEnv* env) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, "Local Supertonic CPU synthesis failed.");
}
std::string String(JNIEnv* env, jstring value) {
    if (!value) throw std::invalid_argument("Missing TTS argument.");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read TTS argument.");
    std::string result(chars); env->ReleaseStringUTFChars(value, chars); return result;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_dev_inferdroid_tts_NativeTts_load(JNIEnv* env, jclass, jstring directory) {
    try {
        std::string path = String(env, directory);
        std::string duration = path + "/duration_predictor.int8.onnx";
        std::string encoder = path + "/text_encoder.int8.onnx";
        std::string estimator = path + "/vector_estimator.int8.onnx";
        std::string vocoder = path + "/vocoder.int8.onnx";
        std::string json = path + "/tts.json";
        std::string indexer = path + "/unicode_indexer.bin";
        std::string voices = path + "/voice.bin";
        SherpaOnnxOfflineTtsConfig config{};
        auto& model = config.model.supertonic;
        model.duration_predictor = duration.c_str(); model.text_encoder = encoder.c_str();
        model.vector_estimator = estimator.c_str(); model.vocoder = vocoder.c_str();
        model.tts_json = json.c_str(); model.unicode_indexer = indexer.c_str(); model.voice_style = voices.c_str();
        config.model.num_threads = 2; config.model.provider = "cpu";
        config.max_num_sentences = 1; config.silence_scale = 1;
        const auto* tts = SherpaOnnxInferDroidCreateOfflineTts(&config);
        if (!tts) throw std::runtime_error("TTS initialization failed.");
        if (SherpaOnnxOfflineTtsSampleRate(tts) != 44100 || SherpaOnnxOfflineTtsNumSpeakers(tts) != 10) {
            SherpaOnnxDestroyOfflineTts(tts); throw std::runtime_error("Unsupported TTS bundle.");
        }
        __android_log_print(ANDROID_LOG_INFO, "InferDroidTTS", "sherpa-onnx %s · Supertonic 3 int8 · ONNX Runtime CPU · 10 voices · native 44.1 kHz, output 24 kHz initialized", SherpaOnnxGetVersionStr());
        return reinterpret_cast<jlong>(tts);
    } catch (const std::exception&) { Error(env); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_inferdroid_tts_NativeTts_generate(JNIEnv* env, jclass, jlong handle, jbyteArray text,
                                        jstring language, jint speaker, jfloat speed) {
    try {
        const auto* tts = reinterpret_cast<const SherpaOnnxOfflineTts*>(handle);
        if (!tts || !text || speaker < 0 || speaker > 9 || !std::isfinite(speed) || speed < 0.25f || speed > 2)
            throw std::invalid_argument("Invalid TTS request.");
        jsize length = env->GetArrayLength(text);
        if (length < 1 || length > 240 * 4) throw std::invalid_argument("Invalid TTS chunk length.");
        std::string utf8(length, '\0');
        env->GetByteArrayRegion(text, 0, length, reinterpret_cast<jbyte*>(utf8.data()));
        if (env->ExceptionCheck()) return nullptr;
        if (utf8.find('\0') != std::string::npos) throw std::invalid_argument("Invalid TTS text.");
        std::string lang = String(env, language);
        const std::string languages = " en ko ja ar bg cs da de el es et fi fr hi hr hu id it lt lv nl pl pt ro ru sk sl sv tr uk vi ";
        if (lang.empty() || languages.find(" " + lang + " ") == std::string::npos) throw std::invalid_argument("Invalid TTS language.");
        std::string extra = "{\"lang\":\"" + lang + "\",\"seed\":42,\"max_len\":240}";
        SherpaOnnxGenerationConfig config{};
        config.sid = speaker; config.speed = speed; config.silence_scale = 1;
        config.num_steps = 5; config.extra = extra.c_str();
        int32_t status = 0;
        std::unique_ptr<const SherpaOnnxGeneratedAudio, decltype(&SherpaOnnxDestroyOfflineTtsGeneratedAudio)>
            audio(SherpaOnnxInferDroidGenerateOfflineTts(tts, utf8.c_str(), &config, &status),
                  SherpaOnnxDestroyOfflineTtsGeneratedAudio);
        if (status == 1) {
            jclass limit = env->FindClass("dev/inferdroid/tts/SherpaTtsEngine$ChunkLimitException");
            if (limit) env->ThrowNew(limit, "TTS chunk exceeds the model positional capacity.");
            return nullptr;
        }
        if (status != 0) throw std::runtime_error("TTS engine failed.");
        if (audio && audio->n > 120 * 44100) {
            jclass limit = env->FindClass("dev/inferdroid/tts/SherpaTtsEngine$OutputLimitException");
            if (limit) env->ThrowNew(limit, "Generated audio exceeds 120 seconds. Use shorter text or a faster speed.");
            return nullptr;
        }
        if (!audio || audio->sample_rate != 44100 || audio->n < 1)
            throw std::runtime_error("Invalid TTS output.");
        // OpenAI-compatible raw PCM is 24 kHz. The pinned model's tts.json
        // specifies 44.1 kHz; resample both WAV and PCM to the same output rate.
        for (int32_t i = 0; i < audio->n; ++i) {
            if (!std::isfinite(audio->samples[i])) throw std::runtime_error("Invalid TTS samples.");
        }
        int32_t count = static_cast<int32_t>((static_cast<int64_t>(audio->n) * 24000 + 44099) / 44100);
        std::vector<jbyte> pcm(count * 2);
        for (int32_t i = 0; i < count; ++i) {
            double position = i * (44100.0 / 24000);
            int32_t first = std::min(static_cast<int32_t>(position), audio->n - 1);
            int32_t next = std::min(first + 1, audio->n - 1);
            float sample = static_cast<float>(audio->samples[first] +
                    (audio->samples[next] - audio->samples[first]) * (position - first));
            int16_t value = static_cast<int16_t>(std::lround(std::max(-1.0f, std::min(1.0f, sample)) * 32767.0f));
            pcm[2 * i] = static_cast<jbyte>(value & 255);
            pcm[2 * i + 1] = static_cast<jbyte>((static_cast<uint16_t>(value) >> 8) & 255);
        }
        if (std::all_of(pcm.begin(), pcm.end(), [](jbyte value) { return value == 0; }))
            throw std::runtime_error("TTS produced only silence.");
        jbyteArray result = env->NewByteArray(static_cast<jsize>(pcm.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(pcm.size()), pcm.data());
        return result;
    } catch (const std::exception&) { Error(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_inferdroid_tts_NativeTts_unload(JNIEnv*, jclass, jlong handle) {
    if (handle) SherpaOnnxDestroyOfflineTts(reinterpret_cast<const SherpaOnnxOfflineTts*>(handle));
}
