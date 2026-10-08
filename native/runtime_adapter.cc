// Initialization adapted from LiteRT-LM runtime/engine/litert_lm_main.cc
// at 0b98b80e1d846af27d10b8ab395645119cb231e4 (Apache-2.0, ODML Authors).
#include "runtime_api.h"

#include <android/log.h>
#include <fcntl.h>
#include <link.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <condition_variable>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <utility>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "nlohmann/json.hpp"
#include "runtime/conversation/conversation.h"
#include "runtime/engine/engine_factory.h"
#include "runtime/engine/engine_settings.h"
#include "runtime/executor/executor_settings_base.h"
#include "runtime/executor/llm_executor_settings.h"
#include "runtime/util/logging.h"
#include "runtime/util/scoped_file.h"

namespace {
constexpr size_t kLogLimit = 128 * 1024;
std::mutex inference_mutex;

// The pinned LiteRT Android logger writes to logcat AND stderr. Capture stderr
// and stdout during this one request so diagnostics also survive in the Java UI.
// Native logcat remains available if an upstream fatal error terminates the app.
class LogCapture {
 public:
  LogCapture() {
    int descriptors[2];
    if (pipe2(descriptors, O_CLOEXEC) != 0) return;
    saved_out_ = dup(STDOUT_FILENO);
    saved_err_ = dup(STDERR_FILENO);
    if (saved_out_ < 0 || saved_err_ < 0) {
      if (saved_out_ >= 0) close(saved_out_);
      if (saved_err_ >= 0) close(saved_err_);
      saved_out_ = saved_err_ = -1;
      close(descriptors[0]);
      close(descriptors[1]);
      return;
    }
    fflush(nullptr);
    read_fd_ = descriptors[0];
    reader_ = std::thread([this] {
      char buffer[4096];
      std::string pending;
      ssize_t size;
      while ((size = read(read_fd_, buffer, sizeof(buffer))) != 0) {
        if (size < 0) {
          if (errno == EINTR) continue;
          break;
        }
        pending.append(buffer, size);
        size_t end;
        while ((end = pending.find('\n')) != std::string::npos) {
          Append(pending.substr(0, end));
          pending.erase(0, end + 1);
        }
        if (pending.size() > 8192) {
          Append(pending);
          pending.clear();
        }
      }
      if (!pending.empty()) Append(pending);
    });
    dup2(descriptors[1], STDOUT_FILENO);
    dup2(descriptors[1], STDERR_FILENO);
    close(descriptors[1]);
  }

  ~LogCapture() { Stop(); }

  void Stop() {
    if (saved_out_ < 0) return;
    fflush(nullptr);
    dup2(saved_out_, STDOUT_FILENO);
    dup2(saved_err_, STDERR_FILENO);
    close(saved_out_);
    close(saved_err_);
    saved_out_ = saved_err_ = -1;
    reader_.join();
    close(read_fd_);
  }

  const std::string& Text() const { return text_; }  // Only read after Stop().

 private:
  void Append(const std::string& line) {
    // Preserve the earliest initialization evidence even if later logs are large.
    if (text_.size() < kLogLimit) {
      text_.append(line.substr(0, kLogLimit - text_.size()));
      text_ += '\n';
    }
    __android_log_write(ANDROID_LOG_INFO, "InferDroidRuntime", line.c_str());
  }
  int saved_out_ = -1;
  int saved_err_ = -1;
  int read_fd_ = -1;
  std::thread reader_;
  std::string text_;
};

struct LoadedLibraries {
  std::string dispatch;
  std::string southbound;
};

int FindLibraries(dl_phdr_info* info, size_t, void* state) {
  auto& libraries = *static_cast<LoadedLibraries*>(state);
  const std::string path = info->dlpi_name ? info->dlpi_name : "";
  if (path.ends_with("/libLiteRtDispatch_GoogleTensor.so")) libraries.dispatch = path;
  if (path == "libedgetpu_litert.so" || path.ends_with("/libedgetpu_litert.so")) {
    libraries.southbound = path;
  }
  return 0;
}

struct RuntimeEngine {
  std::unique_ptr<litert::lm::Engine> engine;
  std::string dispatch_path;
  std::mutex request_mutex;
  litert::lm::Conversation* active_conversation = nullptr;
  uint64_t active_request = 0;
  uint64_t cancelled_request = 0;
};

// JNI receives opaque IDs, never dereferenceable pointers. A lookup retains the
// engine during cancellation, so unload cannot race a stale Java handle.
std::mutex engines_mutex;
std::map<uint64_t, std::shared_ptr<RuntimeEngine>> engines;
uint64_t next_engine_handle = 1;

std::shared_ptr<RuntimeEngine> FindEngine(uint64_t handle) {
  std::lock_guard<std::mutex> lock(engines_mutex);
  auto found = engines.find(handle);
  return found == engines.end() ? nullptr : found->second;
}

absl::Status VerifyLibraries(const RuntimeEngine& runtime,
                             std::ostringstream& diagnostics) {
  LoadedLibraries libraries;
  dl_iterate_phdr(FindLibraries, &libraries);
  diagnostics << "Loaded Google Tensor dispatch: " << libraries.dispatch << '\n'
              << "Loaded EdgeTPU southbound: " << libraries.southbound << '\n';
  if (libraries.dispatch != runtime.dispatch_path || libraries.southbound.empty()) {
    return absl::FailedPreconditionError(
        "The expected APK Google Tensor dispatch and libedgetpu_litert.so were not both observed. No CPU retry is attempted.");
  }
  return absl::OkStatus();
}

absl::StatusOr<std::shared_ptr<RuntimeEngine>> Load(int fd, const char* path,
    const char* library_dir, const char* cache_dir, bool verbose,
    std::ostringstream& diagnostics) {
  using namespace litert::lm;
  SetMinLogSeverity(verbose ? LogSeverity::kInfo : LogSeverity::kWarning);
  diagnostics << "LiteRT-LM v0.14.0-alpha.0 / 0b98b80e1d846af27d10b8ab395645119cb231e4\n"
              << "LiteRT / 43d8b4f20ef743a7c5beb69c365538e726cc20d9\n"
              << "Requested backend: NPU; CPU retry is disabled\n"
              << "Dispatch directory: " << library_dir << '\n';

  const std::string dispatch_path = std::string(library_dir) + "/libLiteRtDispatch_GoogleTensor.so";
  if (access(dispatch_path.c_str(), R_OK) != 0) {
    return absl::FailedPreconditionError("Packaged Google Tensor dispatch library is missing: " + dispatch_path);
  }

  absl::StatusOr<ModelAssets> assets = absl::InvalidArgumentError("No model was selected.");
  if (fd >= 0) {
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0 || lseek(fd, 0, SEEK_CUR) < 0) {
      return absl::InvalidArgumentError("Model descriptor must be a non-empty seekable local file. Select the .litertlm in device storage, or use an app-private path.");
    }
    int owned_fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (owned_fd < 0) return absl::InternalError("Failed to duplicate the model descriptor.");
    assets = ModelAssets::Create(std::make_shared<ScopedFile>(owned_fd));
    diagnostics << "Model: document descriptor; " << st.st_size << " bytes (no copy)\n";
  } else if (path && path[0] == '/') {
    assets = ModelAssets::Create(path);
    diagnostics << "Model path: " << path << '\n';
  }
  if (!assets.ok()) return assets.status();
  auto settings = EngineSettings::CreateDefault(std::move(*assets), Backend::NPU);
  if (!settings.ok()) return settings.status();
  auto& executor_settings = settings->GetMutableMainExecutorSettings();
  executor_settings.SetLitertDispatchLibDir(library_dir);
  executor_settings.SetCacheDir(cache_dir);
  settings->GetMutableBenchmarkParams() = proto::BenchmarkParams();

  diagnostics << "Creating NPU engine...\n";
  __android_log_write(ANDROID_LOG_INFO, "InferDroid", "Creating LiteRT-LM NPU engine");
  const auto init_start = std::chrono::steady_clock::now();
  auto engine = EngineFactory::CreateDefault(std::move(*settings));
  if (!engine.ok()) return engine.status();
  diagnostics << "Engine init: " << std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now() - init_start).count() << " ms\n";

  auto runtime = std::make_shared<RuntimeEngine>();
  runtime->engine = std::move(*engine);
  runtime->dispatch_path = dispatch_path;
  auto verified = VerifyLibraries(*runtime, diagnostics);
  if (!verified.ok()) return verified;
  diagnostics << "NPU engine initialized and retained. Execution is verified after a successful request.\n";
  return runtime;
}

absl::StatusOr<std::string> Generate(const std::shared_ptr<RuntimeEngine>& runtime,
    uint64_t request_id, const char* prompt, bool verbose,
    std::ostringstream& diagnostics) {
  using namespace litert::lm;
  SetMinLogSeverity(verbose ? LogSeverity::kInfo : LogSeverity::kWarning);
  diagnostics << "Reusing loaded NPU engine; fresh conversation for this request.\n"
              << "CPU retry is disabled.\n";
  auto session_config = SessionConfig::CreateDefault();
  session_config.SetMaxOutputTokens(256);
  auto config = ConversationConfig::Builder().SetSessionConfig(session_config).Build(*runtime->engine);
  if (!config.ok()) return config.status();
  auto conversation = Conversation::Create(*runtime->engine, *config);
  if (!conversation.ok()) return conversation.status();

  // Matches the pinned CLI's async call plus Engine::WaitUntilDone. Keep the
  // conversation alive until tasks/callbacks finish, and discard it after every
  // request (especially cancellation); retain only the reusable engine.
  const nlohmann::json message = {
      {"role", "user"},
      {"content", nlohmann::json::array({{{"type", "text"}, {"text", prompt}}})}};
  struct CallbackState {
    std::mutex mutex;
    std::condition_variable completed;
    bool done = false;
    absl::Status status;
  };
  auto callback = std::make_shared<CallbackState>();
  absl::Status started;
  {
    std::lock_guard<std::mutex> lock(runtime->request_mutex);
    if (runtime->cancelled_request == request_id) {
      return absl::CancelledError("Request cancelled before generation started.");
    }
    runtime->active_request = request_id;
    runtime->active_conversation = conversation->get();
    started = (*conversation)->SendMessageAsync(message,
        [callback](absl::StatusOr<Message> chunk) {
          if (!chunk.ok() || chunk->is_null()) {
            std::lock_guard<std::mutex> lock(callback->mutex);
            if (!chunk.ok()) callback->status = chunk.status();
            callback->done = true;
            callback->completed.notify_all();
          }
        });
  }
  auto finished = started.ok()
      ? runtime->engine->WaitUntilDone(Engine::kDefaultTimeout) : started;
  if (!finished.ok()) {
    (*conversation)->CancelProcess();
    // The upstream timeout is ten minutes. Drain cancellation before releasing
    // the conversation whose callbacks are owned by the execution manager.
    auto drained = runtime->engine->WaitUntilDone(Engine::kDefaultTimeout);
    if (!drained.ok()) {
      __android_log_write(ANDROID_LOG_FATAL, "InferDroid",
          "NPU cancellation did not drain. Terminating before releasing live callback state.");
      std::abort();
    }
  }
  if (started.ok()) {
    // As in upstream SendMessage, task draining is followed by the terminal
    // callback. It appends the complete response before emitting a null chunk.
    std::unique_lock<std::mutex> lock(callback->mutex);
    if (!callback->completed.wait_for(lock, std::chrono::minutes(10),
                                     [&] { return callback->done; })) {
      __android_log_write(ANDROID_LOG_FATAL, "InferDroid", "NPU terminal callback timed out.");
      std::abort();
    }
  }
  {
    std::lock_guard<std::mutex> lock(runtime->request_mutex);
    runtime->active_conversation = nullptr;
    runtime->active_request = 0;
    if (runtime->cancelled_request == request_id) {
      return absl::CancelledError("Generation cancelled; the model remains loaded.");
    }
  }
  if (!finished.ok()) return finished;
  {
    std::lock_guard<std::mutex> lock(callback->mutex);
    if (!callback->status.ok()) return callback->status;
  }
  auto verified = VerifyLibraries(*runtime, diagnostics);
  if (!verified.ok()) return verified;
  auto history = (*conversation)->GetHistory();
  if (history.empty()) return absl::InternalError("No response in conversation history.");
  const auto& response = history.back();
  if (!response.contains("role") || response["role"] == "user") {
    return absl::InternalError("No completed model response in conversation history.");
  }

  std::string text;
  if (response.contains("content") && response["content"].is_array()) {
    for (const auto& content : response["content"]) {
      if (content.contains("text") && content["text"].is_string()) {
        text += content["text"].get<std::string>();
      }
    }
  }
  if (text.empty()) return absl::InternalError("LiteRT-LM returned a message with no generated text.");
  auto benchmark = (*conversation)->GetBenchmarkInfo();
  if (benchmark.ok()) diagnostics << *benchmark << '\n';
  diagnostics << "Generation completed with NPU requested and Google Tensor/EdgeTPU libraries loaded.\n"
              << "Confirm dispatch_delegate_kernel and SouthBound evidence in the load logs or logcat.\n";
  return text;
}

char* Copy(const std::string& text) {
  auto* buffer = static_cast<char*>(malloc(text.size() + 1));
  if (buffer) memcpy(buffer, text.c_str(), text.size() + 1);
  return buffer;
}
InferDroidResult* Result(const absl::Status& status, const std::string& text,
                        const std::ostringstream& diagnostics, uint64_t handle = 0) {
  const std::string log = diagnostics.str();
  auto* result = static_cast<InferDroidResult*>(calloc(1, sizeof(InferDroidResult)));
  if (!result) return nullptr;
  result->success = status.ok();
  result->cancelled = absl::IsCancelled(status);
  result->engine_handle = handle;
  result->text = Copy(text);
  result->text_size = text.size();
  result->diagnostics = Copy(log);
  result->diagnostics_size = log.size();
  if (!result->text || !result->diagnostics) {
    inferdroid_result_free(result);
    return nullptr;
  }
  return result;
}
}  // namespace

InferDroidResult* inferdroid_engine_load(int fd, const char* path,
    const char* lib_dir, const char* cache_dir, int verbose) {
  std::lock_guard<std::mutex> lock(inference_mutex);
  LogCapture capture;
  std::ostringstream diagnostics;
  auto loaded = Load(fd, path, lib_dir, cache_dir, verbose != 0, diagnostics);
  capture.Stop();
  if (!loaded.ok()) diagnostics << "FAILED: " << loaded.status().ToString() << '\n';
  diagnostics << "\nNative load logs:\n" << capture.Text();
  uint64_t handle = 0;
  if (loaded.ok()) {
    std::lock_guard<std::mutex> registry_lock(engines_mutex);
    handle = next_engine_handle++;
    engines.emplace(handle, *loaded);
  }
  auto* result = Result(loaded.status(), "", diagnostics, handle);
  if (!result && handle) {
    std::lock_guard<std::mutex> registry_lock(engines_mutex);
    engines.erase(handle);
  }
  return result;
}

InferDroidResult* inferdroid_engine_generate(uint64_t handle, uint64_t request_id,
    const char* prompt, int verbose) {
  std::lock_guard<std::mutex> lock(inference_mutex);
  LogCapture capture;
  std::ostringstream diagnostics;
  auto runtime = FindEngine(handle);
  absl::StatusOr<std::string> generated = runtime
      ? Generate(runtime, request_id, prompt, verbose != 0, diagnostics)
      : absl::StatusOr<std::string>(absl::FailedPreconditionError("No NPU engine is loaded."));
  capture.Stop();
  if (!generated.ok()) diagnostics << generated.status().ToString() << '\n';
  diagnostics << "\nNative request logs:\n" << capture.Text();
  return Result(generated.status(), generated.ok() ? *generated : "", diagnostics);
}

void inferdroid_engine_cancel(uint64_t handle, uint64_t request_id) {
  auto runtime = FindEngine(handle);
  if (!runtime || !request_id) return;
  std::lock_guard<std::mutex> lock(runtime->request_mutex);
  runtime->cancelled_request = request_id;
  if (runtime->active_request == request_id && runtime->active_conversation) {
    runtime->active_conversation->CancelProcess();
  }
}

void inferdroid_engine_unload(uint64_t handle) {
  std::lock_guard<std::mutex> lock(inference_mutex);
  std::shared_ptr<RuntimeEngine> runtime;
  {
    std::lock_guard<std::mutex> registry_lock(engines_mutex);
    auto found = engines.find(handle);
    if (found == engines.end()) return;
    runtime = std::move(found->second);
    engines.erase(found);
  }
  // Destruction and model unmapping run on Java's worker, never the main thread.
  runtime.reset();
  __android_log_write(ANDROID_LOG_INFO, "InferDroid", "NPU engine unloaded");
}

void inferdroid_result_free(InferDroidResult* result) {
  if (!result) return;
  free(result->text);
  free(result->diagnostics);
  free(result);
}
