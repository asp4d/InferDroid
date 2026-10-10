// Keep C++ exceptions inside sherpa's shared library (each DSO uses c++_static).
#include <stdexcept>
#include "sherpa-onnx/c-api/c-api.h"

extern "C" SHERPA_ONNX_API const SherpaOnnxOfflineTts*
SherpaOnnxInferDroidCreateOfflineTts(const SherpaOnnxOfflineTtsConfig* config) {
    try { return SherpaOnnxCreateOfflineTts(config); }
    catch (...) { return nullptr; }
}

// status: 0 = normal, 1 = text chunk exceeds positional capacity, 2 = engine error.
extern "C" SHERPA_ONNX_API const SherpaOnnxGeneratedAudio*
SherpaOnnxInferDroidGenerateOfflineTts(const SherpaOnnxOfflineTts* tts,
        const char* text, const SherpaOnnxGenerationConfig* config, int32_t* status) {
    *status = 0;
    try {
        return SherpaOnnxOfflineTtsGenerateWithConfig(tts, text, config, nullptr, nullptr);
    } catch (const std::length_error&) { *status = 1; }
    catch (...) { *status = 2; }
    return nullptr;
}
