#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "sherpa-onnx/c-api/c-api.h"

namespace {
struct Recognizer {
    std::string encoder;
    std::string decoder;
    std::string tokens;
    std::string language;
    SherpaOnnxOfflineRecognizerConfig config{};
    const SherpaOnnxOfflineRecognizer* native = nullptr;
    ~Recognizer() { if (native) SherpaOnnxDestroyOfflineRecognizer(native); }
};
std::string String(JNIEnv* env, jstring value) {
    if (!value) throw std::invalid_argument("Missing speech argument.");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read speech argument.");
    std::string text(chars);
    env->ReleaseStringUTFChars(value, chars);
    return text;
}
void Error(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}
jbyteArray Bytes(JNIEnv* env, const char* text) {
    const size_t size = text ? std::strlen(text) : 0;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(size));
    if (bytes && size) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(text));
    return bytes;
}
// Upstream exits on an invalid Whisper language. Validate before SetConfig.
bool Language(const std::string& value) {
    static const std::string codes = " en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su ";
    return value.empty() || codes.find(" " + value + " ") != std::string::npos;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_inferdroid_speech_NativeSpeech_load(JNIEnv* env, jclass, jstring directory) {
    try {
        std::string path = String(env, directory);
        auto engine = std::make_unique<Recognizer>();
        engine->encoder = path + "/tiny-encoder.int8.onnx";
        engine->decoder = path + "/tiny-decoder.int8.onnx";
        engine->tokens = path + "/tiny-tokens.txt";
        auto& config = engine->config;
        config.feat_config.sample_rate = 16000;
        config.feat_config.feature_dim = 80;
        config.model_config.whisper.encoder = engine->encoder.c_str();
        config.model_config.whisper.decoder = engine->decoder.c_str();
        config.model_config.whisper.language = "";
        config.model_config.whisper.task = "transcribe";
        config.model_config.whisper.tail_paddings = 300;
        config.model_config.tokens = engine->tokens.c_str();
        config.model_config.num_threads = 2;
        config.model_config.provider = "cpu";
        config.model_config.model_type = "whisper";
        config.decoding_method = "greedy_search";
        engine->native = SherpaOnnxCreateOfflineRecognizer(&config);
        if (!engine->native) throw std::runtime_error("Cannot initialize the local Whisper CPU recognizer.");
        __android_log_print(ANDROID_LOG_INFO, "InferDroidASR", "sherpa-onnx %s · ONNX Runtime CPU · Whisper tiny int8 initialized", SherpaOnnxGetVersionStr());
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception&) { Error(env, "Cannot initialize the local Whisper CPU recognizer."); return 0; }
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_inferdroid_speech_NativeSpeech_transcribe(JNIEnv* env, jclass, jlong handle,
                                                 jfloatArray samples, jstring language) {
    try {
        auto* engine = reinterpret_cast<Recognizer*>(handle);
        if (!engine || !samples) throw std::invalid_argument("Speech model is unloaded.");
        jsize count = env->GetArrayLength(samples);
        if (count < 1 || count > 25 * 16000) throw std::invalid_argument("Invalid speech chunk size.");
        engine->language = String(env, language);
        if (!Language(engine->language)) throw std::invalid_argument("Unsupported Whisper language.");
        engine->config.model_config.whisper.language = engine->language.c_str();
        // SetConfig updates decoding options while retaining the encoder/decoder sessions.
        SherpaOnnxOfflineRecognizerSetConfig(engine->native, &engine->config);
        std::vector<float> audio(count);
        env->GetFloatArrayRegion(samples, 0, count, audio.data());
        if (env->ExceptionCheck()) return nullptr;
        for (float value : audio) if (!std::isfinite(value)) throw std::invalid_argument("Invalid PCM sample.");
        std::unique_ptr<const SherpaOnnxOfflineStream, decltype(&SherpaOnnxDestroyOfflineStream)>
                stream(SherpaOnnxCreateOfflineStream(engine->native), SherpaOnnxDestroyOfflineStream);
        if (!stream) throw std::runtime_error("Cannot create a speech stream.");
        SherpaOnnxAcceptWaveformOffline(stream.get(), 16000, audio.data(), count);
        SherpaOnnxDecodeOfflineStream(engine->native, stream.get());
        std::unique_ptr<const SherpaOnnxOfflineRecognizerResult, decltype(&SherpaOnnxDestroyOfflineRecognizerResult)>
                result(SherpaOnnxGetOfflineStreamResult(stream.get()), SherpaOnnxDestroyOfflineRecognizerResult);
        if (!result) throw std::runtime_error("No speech result was returned.");
        jclass type = env->FindClass("dev/inferdroid/speech/TranscriptionResult");
        if (!type) return nullptr;
        jmethodID constructor = env->GetMethodID(type, "<init>", "([B[BD)V");
        if (!constructor) return nullptr;
        jbyteArray text = Bytes(env, result->text);
        jbyteArray lang = Bytes(env, result->lang);
        if (!text || !lang) return nullptr;
        return env->NewObject(type, constructor, text, lang, count / 16000.0);
    } catch (const std::exception&) { Error(env, "Local Whisper CPU decoding failed."); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_inferdroid_speech_NativeSpeech_unload(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Recognizer*>(handle);
}
