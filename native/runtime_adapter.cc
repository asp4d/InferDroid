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
#include "litert/c/litert_model.h"
#include "runtime/components/model_resources.h"
#include "runtime/conversation/conversation.h"
#include "runtime/engine/engine_factory.h"
#include "runtime/engine/engine_settings.h"
#include "runtime/executor/executor_settings_base.h"
#include "runtime/executor/llm_executor_settings.h"
#include "runtime/executor/litert_compiled_model_executor_utils.h"
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

// A CPU option does not turn an AOT DISPATCH_OP graph into CPU inference.
// Verify the selected package's actual graphs before labeling its backend.
absl::Status VerifyVisionGraphs(const litert::lm::ModelAssets& assets,
                               std::ostringstream& diagnostics) {
  using namespace litert::lm;
  auto resources = BuildLiteRtCompiledModelResources(assets);
  if (!resources.ok()) return resources.status();
  for (const auto type : {ModelType::kTfLiteVisionEncoder, ModelType::kTfLiteVisionAdapter}) {
    const bool encoder = type == ModelType::kTfLiteVisionEncoder;
    auto model = (*resources)->GetTFLiteModel(type);
    if (!model.ok()) return model.status();
    if (!*model) return absl::FailedPreconditionError("Selected package has no vision model.");
    LiteRtParamIndex graphs = 0;
    if (LiteRtGetNumModelSubgraphs((*model)->Get(), &graphs) != kLiteRtStatusOk || graphs < 1 || graphs > 32) {
      return absl::FailedPreconditionError("Cannot inspect the selected vision graphs.");
    }
    for (LiteRtParamIndex i = 0; i < graphs; ++i) {
      LiteRtSubgraph graph = nullptr;
      LiteRtParamIndex count = 0;
      const char* name = nullptr;
      if (LiteRtGetModelSubgraph((*model)->Get(), i, &graph) != kLiteRtStatusOk ||
          LiteRtGetNumSubgraphOps(graph, &count) != kLiteRtStatusOk || count < 1 || count > 8192 ||
          LiteRtGetSubgraphName(graph, &name) != kLiteRtStatusOk) {
        return absl::FailedPreconditionError("Cannot inspect the selected vision subgraph.");
      }
      for (LiteRtParamIndex j = 0; j < count; ++j) {
        LiteRtOp op = nullptr;
        LiteRtOpCode code;
        const char* custom = nullptr;
        if (LiteRtGetSubgraphOp(graph, j, &op) != kLiteRtStatusOk || LiteRtGetOpCode(op, &code) != kLiteRtStatusOk) {
          return absl::FailedPreconditionError("Cannot inspect a vision operator.");
        }
        if (code == kLiteRtOpCodeTflCustom && LiteRtGetCustomCode(op, &custom) != kLiteRtStatusOk) {
          return absl::FailedPreconditionError("Cannot inspect a custom vision operator.");
        }
        if ((encoder && (count != 1 || !custom || std::strcmp(custom, "DISPATCH_OP") != 0)) ||
            (!encoder && code == kLiteRtOpCodeTflCustom)) {
          return absl::FailedPreconditionError(
              "Expected the Tensor G5 package's AOT NPU vision encoder and builtin CPU adapter. Select gemma-4-E2B-it_Google_Tensor_G5.litertlm.");
        }
      }
      diagnostics << (encoder ? "Vision encoder graph: " : "Vision adapter graph: ")
                  << (name ? name : "") << "; " << count << " operators; "
                  << (encoder ? "AOT NPU DISPATCH_OP" : "CPU builtins") << '\n';
    }
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
  // The pinned CPU vision option handles builtin ops. The G5 encoder already
  // contains AOT NPU dispatch graphs, which LiteRT delegates independently of
  // this option. It does not run the compiled encoder on CPU.
  auto settings = EngineSettings::CreateDefault(std::move(*assets), Backend::NPU, Backend::CPU);
  if (!settings.ok()) return settings.status();
  auto& executor_settings = settings->GetMutableMainExecutorSettings();
  executor_settings.SetLitertDispatchLibDir(library_dir);
  executor_settings.SetCacheDir(cache_dir);
  settings->GetMutableVisionExecutorSettings()->SetCacheDir(cache_dir);
  diagnostics << "Vision runtime option: CPU; AOT encoder uses NPU dispatch; adapter uses CPU.\n";
  auto vision_graphs = VerifyVisionGraphs(settings->GetVisionExecutorSettings()->GetModelAssets(), diagnostics);
  if (!vision_graphs.ok()) return vision_graphs;
  settings->GetMutableBenchmarkParams() = proto::BenchmarkParams();

  diagnostics << "Creating NPU engine...\n";
  __android_log_write(ANDROID_LOG_INFO, "InferDroid", "Creating LiteRT-LM NPU engine");
  const auto init_start = std::chrono::steady_clock::now();
  auto engine = EngineFactory::CreateDefault(std::move(*settings));
  if (!engine.ok()) return engine.status();
  auto runtime = std::make_shared<RuntimeEngine>();
  runtime->engine = std::move(*engine);
  runtime->dispatch_path = dispatch_path;
  auto vision = runtime->engine->GetVisionExecutorProperties();
  if (!vision.ok()) return vision.status();
  diagnostics << "Engine initialization (language and vision): "
              << std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now() - init_start).count() << " ms\n"
              << "AOT NPU vision encoder and CPU adapter initialized from the selected package.\n"
              << "Vision properties: " << *vision << '\n';
  auto verified = VerifyLibraries(*runtime, diagnostics);
  if (!verified.ok()) return verified;
  diagnostics << "NPU engine initialized and retained. Execution is verified after a successful request.\n";
  return runtime;
}

struct GenerationMetrics {
  int prompt_tokens = -1;
  int completion_tokens = -1;
  bool limit_reached = false;
};

std::string MessageText(const litert::lm::Message& message, bool has_tools = false) {
  std::string text;
  if (message.contains("content") && message["content"].is_array()) {
    for (const auto& part : message["content"]) {
      if (has_tools && part.contains("error")) continue;
      if (part.contains("text") && part["text"].is_string()) text += part["text"].get<std::string>();
    }
  }
  if (has_tools) {
    // The pinned parser leaves an unfinished function block in content when
    // the token cap interrupts it. Apply this to deltas and final text alike.
    auto unfinished_call = text.find("<|tool_call>");
    if (unfinished_call != std::string::npos) text.resize(unfinished_call);
  }
  return text;
}

std::string MessageReasoning(const litert::lm::Message& message) {
  if (message.contains("channels") && message["channels"].is_object()) {
    const auto& channels = message["channels"];
    if (channels.contains("thought") && channels["thought"].is_string()) {
      return channels["thought"].get<std::string>();
    }
  }
  return "";
}

struct ChatOutput {
  std::string text;
  std::string reasoning;
  std::string tool_calls;
};

absl::StatusOr<ChatOutput> Generate(const std::shared_ptr<RuntimeEngine>& runtime,
    uint64_t request_id, const char* request_json, bool verbose,
    int (*on_text)(void*, int, const char*, size_t), void* callback_context,
    GenerationMetrics& metrics, std::ostringstream& diagnostics) {
  using namespace litert::lm;
  SetMinLogSeverity(verbose ? LogSeverity::kInfo : LogSeverity::kWarning);
  diagnostics << "Reusing loaded NPU engine; fresh conversation for this request.\n"
              << "CPU retry is disabled.\n";
  auto session_config = SessionConfig::CreateDefault();
  auto request = nlohmann::json::parse(request_json, nullptr, false);
  if (request.is_discarded() || !request.is_object() || !request.contains("messages") ||
      !request["messages"].is_array() || request["messages"].empty() ||
      !request.contains("max_output_tokens") || !request["max_output_tokens"].is_number_integer()) {
    return absl::InvalidArgumentError("Invalid internal chat request.");
  }
  int images = 0;
  for (const auto& message : request["messages"]) {
    if (message.contains("content") && message["content"].is_array()) {
      for (const auto& part : message["content"]) {
        if (part.value("type", "") == "image") ++images;
      }
    }
  }
  if (images > 4) return absl::InvalidArgumentError("At most four image attachments are supported.");
  session_config.SetVisionModalityEnabled(images > 0);
  if (images) diagnostics << "Image attachments: " << images
                          << "; vision encoder NPU, vision adapter CPU, language generation NPU.\n";
  int max_tokens = request["max_output_tokens"].get<int>();
  if (max_tokens < 1 || max_tokens > 4096) return absl::InvalidArgumentError("Invalid output token limit.");
  session_config.SetMaxOutputTokens(max_tokens);
  auto& sampler = session_config.GetMutableSamplerParams();
  if (request.contains("temperature")) {
    float temperature = request["temperature"].get<float>();
    if (temperature == 0) sampler.set_type(proto::SamplerParameters::GREEDY);
    else sampler.set_temperature(temperature);
  }
  if (request.contains("top_p")) {
    sampler.set_p(request["top_p"].get<float>());
    if (sampler.type() != proto::SamplerParameters::GREEDY) sampler.set_type(proto::SamplerParameters::TOP_P);
  }
  if (request.contains("seed")) sampler.set_seed(request["seed"].get<int>());
  if (request.contains("enable_thinking") && !request["enable_thinking"].is_boolean()) {
    return absl::InvalidArgumentError("Invalid thinking setting.");
  }
  const bool enable_thinking = request.value("enable_thinking", false);
  auto tools = request.value("tools", nlohmann::json::array());
  if (!tools.is_array() || tools.size() > 128) {
    return absl::InvalidArgumentError("Invalid internal function definitions.");
  }
  JsonPreface preface;
  preface.tools = tools;
  diagnostics << "Thinking: " << (enable_thinking ? "enabled" : "disabled") << '\n';
  diagnostics << "Client function definitions: " << tools.size() << "; execution remains with the client.\n";
  // This G5 package predates channel metadata. Declare Gemma's thought channel
  // explicitly so LiteRT parses split delimiters and truncated thought blocks.
  auto config = ConversationConfig::Builder()
      .SetSessionConfig(session_config)
      .SetEnableThinking(enable_thinking)
      .SetPreface(preface)
      .SetChannels({Channel{"thought", "<|channel>thought", "<channel|>"}})
      .Build(*runtime->engine);
  if (!config.ok()) return config.status();
  auto conversation = Conversation::Create(*runtime->engine, *config);
  if (!conversation.ok()) return conversation.status();

  // Matches the pinned CLI's async call plus Engine::WaitUntilDone. Keep the
  // conversation alive until tasks/callbacks finish, and discard it after every
  // request (especially cancellation); retain only the reusable engine.
  const nlohmann::json message = request["messages"];
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
        [callback, on_text, callback_context, has_tools = !tools.empty()](absl::StatusOr<Message> chunk) {
          if (!chunk.ok() || chunk->is_null()) {
            std::lock_guard<std::mutex> lock(callback->mutex);
            if (!chunk.ok()) callback->status = chunk.status();
            callback->done = true;
            callback->completed.notify_all();
          } else if (on_text) {
            std::string reasoning = MessageReasoning(*chunk);
            std::string text = MessageText(*chunk, has_tools);
            if ((!reasoning.empty() && !on_text(callback_context, 1, reasoning.data(), reasoning.size())) ||
                (!text.empty() && !on_text(callback_context, 0, text.data(), text.size()))) {
              std::lock_guard<std::mutex> lock(callback->mutex);
              callback->status = absl::InternalError("Text delivery callback failed.");
            }
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

  std::string text = MessageText(response, !tools.empty());
  if (!tools.empty() && response.contains("content") && response["content"].is_array()) {
    for (const auto& part : response["content"]) {
      if (part.contains("error")) return absl::InternalError("The model produced a malformed function call.");
    }
  }
  auto benchmark = (*conversation)->GetBenchmarkInfo();
  if (benchmark.ok()) {
    diagnostics << *benchmark << '\n';
    if (benchmark->GetTotalPrefillTurns() > 0) {
      auto prefill = benchmark->GetPrefillTurn(benchmark->GetTotalPrefillTurns() - 1);
      if (prefill.ok()) metrics.prompt_tokens = prefill->num_tokens;
    }
    if (benchmark->GetTotalDecodeTurns() > 0) {
      auto decode = benchmark->GetDecodeTurn(benchmark->GetTotalDecodeTurns() - 1);
      if (decode.ok()) metrics.completion_tokens = decode->num_tokens;
    }
  }
  metrics.limit_reached = metrics.completion_tokens >= max_tokens;
  auto tool_calls = nlohmann::ordered_json::array();
  // A length-limited decode may contain partial arguments. Never expose it as an
  // executable call, even when the upstream parser recovered a JSON prefix.
  if (!metrics.limit_reached && response.contains("tool_calls")) {
    const auto& calls = response["tool_calls"];
    if (!calls.is_array() || calls.size() > 128) {
      return absl::InternalError("Invalid native function-call output.");
    }
    for (const auto& call : calls) {
      if (!call.is_object() || !call.contains("function") || !call["function"].is_object()) {
        return absl::InternalError("Invalid native function-call output.");
      }
      const auto& function = call["function"];
      if (!function.contains("name") || !function["name"].is_string() ||
          !function.contains("arguments") || !function["arguments"].is_object()) {
        return absl::InternalError("Incomplete native function-call output.");
      }
      bool declared = false;
      for (const auto& tool : tools) {
        if (tool.at("function").at("name") == function["name"]) declared = true;
      }
      if (!declared) return absl::InternalError("The model requested an undeclared function.");
      tool_calls.push_back(call);
    }
  }
  diagnostics << "Generation completed with NPU requested and Google Tensor/EdgeTPU libraries loaded.\n"
              << "Confirm dispatch_delegate_kernel and SouthBound evidence in the load logs or logcat.\n";
  return ChatOutput{text, MessageReasoning(response), tool_calls.dump()};
}

char* Copy(const std::string& text) {
  auto* buffer = static_cast<char*>(malloc(text.size() + 1));
  if (buffer) memcpy(buffer, text.c_str(), text.size() + 1);
  return buffer;
}
InferDroidResult* Result(const absl::Status& status, const std::string& text,
                        const std::ostringstream& diagnostics, uint64_t handle = 0,
                        GenerationMetrics metrics = {}, const std::string& reasoning = "",
                        const std::string& tool_calls = "[]") {
  const std::string log = diagnostics.str();
  auto* result = static_cast<InferDroidResult*>(calloc(1, sizeof(InferDroidResult)));
  if (!result) return nullptr;
  result->success = status.ok();
  result->cancelled = absl::IsCancelled(status);
  result->engine_handle = handle;
  result->prompt_tokens = metrics.prompt_tokens;
  result->completion_tokens = metrics.completion_tokens;
  result->limit_reached = metrics.limit_reached;
  result->text = Copy(text);
  result->text_size = text.size();
  result->reasoning = Copy(reasoning);
  result->reasoning_size = reasoning.size();
  result->tool_calls = Copy(tool_calls);
  result->tool_calls_size = tool_calls.size();
  result->diagnostics = Copy(log);
  result->diagnostics_size = log.size();
  if (!result->text || !result->reasoning || !result->tool_calls || !result->diagnostics) {
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
    const char* request_json, int verbose,
    int (*on_text)(void*, int, const char*, size_t), void* callback_context) {
  std::lock_guard<std::mutex> lock(inference_mutex);
  LogCapture capture;
  std::ostringstream diagnostics;
  auto runtime = FindEngine(handle);
  GenerationMetrics metrics;
  absl::StatusOr<ChatOutput> generated = runtime
      ? Generate(runtime, request_id, request_json, verbose != 0,
                 on_text, callback_context, metrics, diagnostics)
      : absl::StatusOr<ChatOutput>(absl::FailedPreconditionError("No NPU engine is loaded."));
  capture.Stop();
  if (!generated.ok()) diagnostics << generated.status().ToString() << '\n';
  diagnostics << "\nNative request logs:\n" << capture.Text();
  return Result(generated.status(), generated.ok() ? generated->text : "", diagnostics, 0, metrics,
                generated.ok() ? generated->reasoning : "",
                generated.ok() ? generated->tool_calls : "[]");
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
  free(result->reasoning);
  free(result->tool_calls);
  free(result->diagnostics);
  free(result);
}
