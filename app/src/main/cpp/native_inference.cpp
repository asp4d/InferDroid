#include <jni.h>
#include <android/log.h>

#include <memory>
#include <string>

#include "runtime_api.h"

namespace {
class JavaString {
 public:
  JavaString(JNIEnv* env, jstring value) : env_(env), value_(value),
      text_(value ? env->GetStringUTFChars(value, nullptr) : nullptr) {}
  ~JavaString() { if (text_) env_->ReleaseStringUTFChars(value_, text_); }
  const char* Get() const { return text_ ? text_ : ""; }
 private:
  JNIEnv* env_;
  jstring value_;
  const char* text_;
};

jbyteArray Bytes(JNIEnv* env, const char* text, size_t size) {
  auto result = env->NewByteArray(static_cast<jsize>(size));
  if (result && size) {
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
                           reinterpret_cast<const jbyte*>(text));
  }
  return result;
}
}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_dev_inferdroid_engine_NativeInference_generateNative(
    JNIEnv* env, jclass, jint fd, jstring model_path, jstring library_dir,
    jstring cache_dir, jbyteArray prompt_bytes, jboolean verbose) {
  JavaString path(env, model_path), libraries(env, library_dir), cache(env, cache_dir);
  if (env->ExceptionCheck()) return nullptr;
  const jsize size = env->GetArrayLength(prompt_bytes);
  std::string prompt(static_cast<size_t>(size), '\0');
  env->GetByteArrayRegion(prompt_bytes, 0, size, reinterpret_cast<jbyte*>(prompt.data()));
  if (env->ExceptionCheck()) return nullptr;
  __android_log_print(ANDROID_LOG_INFO, "InferDroid", "JNI start; backend=NPU; prompt bytes=%d", size);
  std::unique_ptr<InferDroidResult, decltype(&inferdroid_result_free)> result(
      inferdroid_generate(fd, path.Get(), libraries.Get(), cache.Get(), prompt.c_str(), verbose),
      inferdroid_result_free);
  if (!result) {
    jclass error = env->FindClass("java/lang/OutOfMemoryError");
    if (error) env->ThrowNew(error, "Native runtime could not allocate a generation result.");
    return nullptr;
  }
  jclass result_class = env->FindClass("dev/inferdroid/engine/GenerationResult");
  if (!result_class) return nullptr;
  jmethodID constructor = env->GetMethodID(result_class, "<init>", "(Z[B[B)V");
  if (!constructor) return nullptr;
  auto text = Bytes(env, result->text, result->text_size);
  if (env->ExceptionCheck()) return nullptr;
  auto diagnostics = Bytes(env, result->diagnostics, result->diagnostics_size);
  if (env->ExceptionCheck()) return nullptr;
  return env->NewObject(result_class, constructor, static_cast<jboolean>(result->success), text, diagnostics);
}
