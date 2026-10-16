// InferDroid's local LiteRT correctness probe; uses the public C API only.
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"

namespace {
struct ApiError : std::runtime_error {
  std::string step;
  int status;
  ApiError(const char* name, LiteRtStatus code)
      : std::runtime_error(name), step(name), status(static_cast<int>(code)) {}
};
void Check(LiteRtStatus status, const char* step) {
  if (status != kLiteRtStatusOk) throw ApiError(step, status);
}
struct State {
  LiteRtEnvironment environment = nullptr;
  LiteRtModel model = nullptr;
  LiteRtOptions options = nullptr;
  LiteRtCompiledModel compiled = nullptr;
  std::vector<LiteRtTensorBuffer> inputs, outputs;
  ~State() {
    for (auto buffer : outputs) LiteRtDestroyTensorBuffer(buffer);
    for (auto buffer : inputs) LiteRtDestroyTensorBuffer(buffer);
    if (compiled) LiteRtDestroyCompiledModel(compiled);
    if (options) LiteRtDestroyOptions(options);
    if (model) LiteRtDestroyModel(model);
    if (environment) LiteRtDestroyEnvironment(environment);
  }
};

void MakeBuffers(State& state, LiteRtSignature signature, bool input) {
  LiteRtParamIndex count = 0;
  Check(input ? LiteRtGetNumSignatureInputs(signature, &count)
              : LiteRtGetNumSignatureOutputs(signature, &count), "count_io");
  auto& buffers = input ? state.inputs : state.outputs;
  for (size_t index = 0; index < count; ++index) {
    LiteRtTensor tensor = nullptr;
    Check(input ? LiteRtGetSignatureInputTensorByIndex(signature, index, &tensor)
                : LiteRtGetSignatureOutputTensorByIndex(signature, index, &tensor), "get_tensor");
    LiteRtRankedTensorType type;
    Check(LiteRtGetRankedTensorType(tensor, &type), "get_type");
    LiteRtTensorBufferRequirements requirements = nullptr;
    Check(input ? LiteRtGetCompiledModelInputBufferRequirements(state.compiled, 0, index, &requirements)
                : LiteRtGetCompiledModelOutputBufferRequirements(state.compiled, 0, index, &requirements), "buffer_requirements");
    LiteRtTensorBuffer buffer = nullptr;
    Check(LiteRtCreateManagedTensorBufferFromRequirements(state.environment, &type, requirements, &buffer), "create_buffer");
    buffers.push_back(buffer);
  }
}

void CopyInput(LiteRtTensorBuffer buffer, const std::string& filename) {
  size_t size = 0;
  Check(LiteRtGetTensorBufferPackedSize(buffer, &size), "input_size");
  std::ifstream file(filename, std::ios::binary | std::ios::ate);
  if (!file || file.tellg() != static_cast<std::streamoff>(size))
    throw std::runtime_error("Input file has an unexpected size");
  std::vector<char> bytes(size);
  file.seekg(0);
  if (!file.read(bytes.data(), size)) throw std::runtime_error("Input read failed");
  void* address = nullptr;
  Check(LiteRtLockTensorBuffer(buffer, &address, kLiteRtTensorBufferLockModeWrite), "lock_input");
  std::memcpy(address, bytes.data(), size);
  Check(LiteRtUnlockTensorBuffer(buffer), "unlock_input");
}

void SaveOutput(LiteRtTensorBuffer buffer, const std::string& filename) {
  size_t size = 0;
  Check(LiteRtGetTensorBufferPackedSize(buffer, &size), "output_size");
  void* address = nullptr;
  Check(LiteRtLockTensorBuffer(buffer, &address, kLiteRtTensorBufferLockModeRead), "lock_output");
  std::ofstream file(filename, std::ios::binary);
  file.write(static_cast<const char*>(address), size);
  const bool saved = static_cast<bool>(file);
  Check(LiteRtUnlockTensorBuffer(buffer), "unlock_output");
  if (!saved) throw std::runtime_error("Output write failed");
}
}  // namespace

int main(int argc, char** argv) {
  if (argc < 4 || argc > 5) return 2;
  const std::string directory = argv[3];
  const bool cpu = argc == 5 && std::string(argv[4]) == "cpu";
  const bool hybrid = argc == 5 && std::string(argv[4]) == "hybrid";
  if (argc == 5 && !cpu && !hybrid) return 2;
  try {
    State state;
    LiteRtEnvOption dispatch = {};
    dispatch.tag = kLiteRtEnvOptionTagDispatchLibraryDir;
    dispatch.value.type = kLiteRtAnyTypeString;
    dispatch.value.str_value = argv[2];
    Check(LiteRtCreateEnvironment(1, &dispatch, &state.environment), "create_environment");
    Check(LiteRtCreateModelFromFile(state.environment, argv[1], &state.model), "load_model");
    Check(LiteRtCreateOptions(&state.options), "create_options");
    const auto accelerators = cpu ? kLiteRtHwAcceleratorCpu :
        (hybrid ? kLiteRtHwAcceleratorCpu | kLiteRtHwAcceleratorNpu : kLiteRtHwAcceleratorNpu);
    Check(LiteRtSetOptionsHardwareAccelerators(state.options, accelerators), "select_accelerator");
    Check(LiteRtCreateCompiledModel(state.environment, state.model, state.options, &state.compiled), "create_compiled_model");
    LiteRtSignature signature = nullptr;
    Check(LiteRtGetModelSignature(state.model, 0, &signature), "get_signature");
    MakeBuffers(state, signature, true);
    MakeBuffers(state, signature, false);
    for (size_t i = 0; i < state.inputs.size(); ++i)
      CopyInput(state.inputs[i], directory + "/input_" + std::to_string(i) + ".bin");
    Check(LiteRtRunCompiledModel(state.compiled, 0, state.inputs.size(), state.inputs.data(),
                                state.outputs.size(), state.outputs.data()), "invoke");
    for (size_t i = 0; i < state.outputs.size(); ++i)
      SaveOutput(state.outputs[i], directory + "/output_" + std::to_string(i) + ".bin");
    bool accelerated = false;
    Check(LiteRtCompiledModelIsNonCpuFullyAccelerated(state.compiled, &accelerated), "query_acceleration");
    std::ofstream status(directory + "/status.json");
    status << "{\"run_succeeded\":true,\"requested_accelerator\":\""
           << (cpu ? "cpu" : (hybrid ? "cpu+npu" : "npu")) << "\",\"fully_non_cpu\":"
           << (accelerated ? "true" : "false") << "}\n";
    return status ? 0 : 1;
  } catch (const ApiError& error) {
    std::ofstream status(directory + "/status.json");
    status << "{\"run_succeeded\":false,\"step\":\"" << error.step
           << "\",\"api_status\":" << error.status << "}\n";
    return 1;
  } catch (const std::exception&) {
    std::ofstream status(directory + "/status.json");
    status << "{\"run_succeeded\":false,\"step\":\"file_io_or_contract\"}\n";
    return 1;
  }
}
