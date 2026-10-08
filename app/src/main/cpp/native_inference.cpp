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

using Result = std::unique_ptr<InferDroidResult, decltype(&inferdroid_result_free)>;

bool CheckResult(JNIEnv* env, const Result& result) {
  if (result) return true;
  jclass error = env->FindClass("java/lang/OutOfMemoryError");
  if (error) env->ThrowNew(error, "Native runtime could not allocate a result.");
  return false;
}
}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_dev_inferdroid_engine_NativeInference_loadNative(
    JNIEnv* env, jclass, jint fd, jstring model_path, jstring library_dir,
    jstring cache_dir, jboolean verbose) {
  JavaString path(env, model_path), libraries(env, library_dir), cache(env, cache_dir);
  if (env->ExceptionCheck()) return nullptr;
  Result result(inferdroid_engine_load(fd, path.Get(), libraries.Get(), cache.Get(), verbose),
                inferdroid_result_free);
  if (!CheckResult(env, result)) return nullptr;
  struct HandleGuard {
    uint64_t handle;
    ~HandleGuard() { if (handle) inferdroid_engine_unload(handle); }
  } guard{result->engine_handle};
  jclass type = env->FindClass("dev/inferdroid/engine/NativeInference$LoadResult");
  if (!type) return nullptr;
  jmethodID constructor = env->GetMethodID(type, "<init>", "(J[B)V");
  if (!constructor) return nullptr;
  auto diagnostics = Bytes(env, result->diagnostics, result->diagnostics_size);
  if (env->ExceptionCheck()) return nullptr;
  auto loaded = env->NewObject(type, constructor,
      static_cast<jlong>(result->engine_handle), diagnostics);
  if (!env->ExceptionCheck()) guard.handle = 0;
  return loaded;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_inferdroid_engine_NativeInference_generateNative(
    JNIEnv* env, jclass, jlong handle, jlong request_id,
    jbyteArray prompt_bytes, jboolean verbose) {
  const jsize size = env->GetArrayLength(prompt_bytes);
  std::string prompt(static_cast<size_t>(size), '\0');
  env->GetByteArrayRegion(prompt_bytes, 0, size, reinterpret_cast<jbyte*>(prompt.data()));
  if (env->ExceptionCheck()) return nullptr;
  __android_log_print(ANDROID_LOG_INFO, "InferDroid", "JNI start; backend=NPU; prompt bytes=%d", size);
  Result result(
      inferdroid_engine_generate(handle, request_id, prompt.c_str(), verbose),
      inferdroid_result_free);
  if (!CheckResult(env, result)) return nullptr;
  jclass result_class = env->FindClass("dev/inferdroid/engine/GenerationResult");
  if (!result_class) return nullptr;
  jmethodID constructor = env->GetMethodID(result_class, "<init>", "(ZZ[B[B)V");
  if (!constructor) return nullptr;
  auto text = Bytes(env, result->text, result->text_size);
  if (env->ExceptionCheck()) return nullptr;
  auto diagnostics = Bytes(env, result->diagnostics, result->diagnostics_size);
  if (env->ExceptionCheck()) return nullptr;
  return env->NewObject(result_class, constructor, static_cast<jboolean>(result->success),
                       static_cast<jboolean>(result->cancelled), text, diagnostics);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_inferdroid_engine_NativeInference_cancelNative(
    JNIEnv*, jclass, jlong handle, jlong request_id) {
  inferdroid_engine_cancel(handle, request_id);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_inferdroid_engine_NativeInference_unloadNative(JNIEnv*, jclass, jlong handle) {
  inferdroid_engine_unload(handle);
}
