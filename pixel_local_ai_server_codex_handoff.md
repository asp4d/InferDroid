# Pixel Local AI Server — Codex / Android Studio Handoff

**Date:** 2026-09-24  
**Target device:** Google Pixel 10 (base model, 12 GB RAM), latest GrapheneOS  
**Development environment:** Android Studio on Linux Mint 22  
**Preferred app language:** **Java** (not Flutter)  
**Native layer:** C/C++ via JNI where required  
**Primary goal:** A fully local, privacy-preserving Android AI server that exposes an OpenAI-compatible localhost API and uses the Pixel 10 Tensor G5 neural accelerator where practical.

---

## 1. What has already been proven on the actual phone

Tensor G5 NPU/TPU inference has already been successfully demonstrated on this exact Pixel 10 + GrapheneOS device using LiteRT-LM.

### Working model

`gemma-4-E2B-it_Google_Tensor_G5.litertlm`

Approximate size: 3.11 GB.

Source repository/model:

- Hugging Face repository: `litert-community/gemma-4-E2B-it-litert-lm`
- Model file: `gemma-4-E2B-it_Google_Tensor_G5.litertlm`
- URL: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it_Google_Tensor_G5.litertlm

### Working Tensor G5 runtime project

Repository:

https://github.com/bejoyfuuul/litert-lm-tensor-g5-npu

Important dispatch library:

`libLiteRtDispatch_GoogleTensor.so`

This ultimately interfaces with the Pixel vendor library:

`/vendor/lib64/libedgetpu_litert.so`

### Command that successfully ran the model

The original test environment was under `/data/local/tmp/litert-npu` and this command worked:

```bash
adb shell 'cd /data/local/tmp/litert-npu && \
LD_LIBRARY_PATH=/data/local/tmp/litert-npu \
./litert_lm_main \
--backend=npu \
--model_path=/data/local/tmp/litert-npu/gemma-4-E2B-it_Google_Tensor_G5.litertlm \
--input_prompt="Hello, briefly introduce yourself."'
```

### Evidence of genuine Tensor G5 execution

Successful logs included lines equivalent to:

```text
Loading shared library: .../libLiteRtDispatch_GoogleTensor.so
Found GoogleTensorOptions
Replacing ... with delegate (DispatchDelegate)
SouthBound symbols resolved by 'libedgetpu_litert.so'
```

Inference completed normally and generated Gemma output.

Observed benchmark from the successful run:

```text
Init total: ~4599 ms
TTFT:       ~0.36 s
Prefill:    15 tokens / 289.96 ms = 51.73 tok/s
Decode:     44 tokens / 3.008 s = 14.63 tok/s
```

This is essentially the same decode performance reported by the Tensor-G5 runtime project (~14.7 tok/s for this Google-quantized E2B model), and strongly confirms actual NPU execution rather than CPU fallback.

Some supporting graph operations may still use CPU/XNNPACK. That is compatible with heterogeneous inference and does **not** invalidate the successful G5 dispatch.

Warnings seen during testing, including contradictory MediaTek dispatch buffer warnings and some failed generic NPU accelerator registrations, did not prevent the Google Tensor DispatchDelegate path from successfully using `libedgetpu_litert.so`. Do not treat those warnings alone as evidence of failure.

---

## 2. Current location of the test environment

The complete test directory was moved out of `/data/local/tmp` and into user-visible storage:

```text
/storage/emulated/0/AIModels/litert-npu/
```

It contains the model plus the native runtime/test libraries, including approximately:

```text
litert_lm_main
libGemmaModelConstraintProvider.so
libLiteRtDispatch_GoogleTensor.so
libLiteRtGpuAccelerator.so
libLiteRtOpenClAccelerator.so
libLiteRtTopKOpenClSampler.so
libLiteRtTopKWebGpuSampler.so
libLiteRtWebGpuAccelerator.so
libwebgpu_dawn.so
gemma-4-E2B-it_Google_Tensor_G5.litertlm
```

`/data/local/tmp` is now empty.

**Important Android constraint:** shared storage (`/storage/emulated/0`) is suitable as the user-visible master location for large model files, but should **not** be assumed suitable for executing native binaries or dynamically loading executable `.so` files. In the Android app, native libraries should live in the APK/app-native library directory. The model can remain user-manageable in shared storage if Android storage access permits it.

---

## 3. Gemma 4 E2B facts relevant to implementation

Gemma 4 E2B means approximately **Effective 2 Billion**, not MoE.

It is a dense model using **Per-Layer Embeddings (PLE)**. Rough published figures are around 2.3B effective parameters and ~5.1B total parameters including the large embedding tables.

Gemma 4 E2B is multimodal for **input**:

- text
- images
- audio

Its output is **text**. It does not contain an image-generation decoder and therefore cannot generate images merely because it is running on an NPU.

Image understanding and audio understanding are potential later features.

---

## 4. Main application objective

Build a native Android app provisionally called **Pixel Local AI Server**.

It should run a local AI service on the Pixel and expose it to other Android apps through localhost while keeping data on-device.

Target clients include apps such as Agora, FitBuddy, RPClient, and any application that supports a configurable OpenAI-compatible endpoint.

High-level architecture:

```text
Agora / FitBuddy / RPClient / other Android apps
                    |
          OpenAI-compatible HTTP
                    |
         http://127.0.0.1:8080/v1
                    |
          Pixel Local AI Server
       Java foreground Android service
                    |
              JNI / C++ bridge
                    |
                 LiteRT-LM
                    |
      libLiteRtDispatch_GoogleTensor.so
                    |
       /vendor/lib64/libedgetpu_litert.so
                    |
              Tensor G5 NPU/TPU
```

The HTTP/API layer must remain independent of the inference engine so that other engines can be added later.

---

## 5. Development order — IMPORTANT

Do **not** start by implementing every planned feature.

### Milestone 1 — direct Java → JNI → Gemma inference

First reproduce the already-known-good G5 inference from inside an Android app.

Minimum success condition:

```text
Launch app
  -> choose/configure existing Gemma model
  -> press a test button
  -> Java calls JNI/native LiteRT-LM
  -> prompt: "Hello"
  -> Gemma response returned to Java UI
  -> logs prove Google Tensor DispatchDelegate / libedgetpu_litert.so was used
```

Do not proceed to the HTTP server until this works reliably.

This isolates NPU/runtime problems from networking/API problems.

### Milestone 2 — persistent inference service

Move the working inference engine into an Android foreground service.

Requirements:

- model stays loaded between requests when practical
- foreground notification
- clean load/unload lifecycle
- one inference request at a time initially
- cancellation support if feasible
- status/log view
- explicit indication of selected backend
- do not silently claim NPU use if initialization falls back

### Milestone 3 — OpenAI-compatible localhost API

Bind by default to:

```text
127.0.0.1:8080
```

Initial endpoints:

```text
GET  /v1/models
POST /v1/chat/completions
```

Support:

- normal non-streaming completion
- SSE streaming for `stream: true`
- OpenAI-like JSON request/response schema
- useful OpenAI-like JSON errors
- configurable port
- optional/generated local API key
- CORS where needed for WebView/browser-style clients
- queue requests or return a clear Busy/429 response when inference is already running

Bind to localhost only by default. LAN exposure should require an explicit future opt-in.

Do **not** assume localhost is a security boundary on Android: other apps on the phone can potentially connect to a listening localhost socket. Therefore support an API token even though everything remains local.

Some third-party Android clients may reject cleartext `http://127.0.0.1` because of their own network-security policy. That is a client limitation and must be tested per app.

### Milestone 4 — local speech recognition / audio input

Add an audio endpoint, probably:

```text
POST /v1/audio/transcriptions
```

For conventional speech-to-text, investigate **sherpa-onnx** rather than forcing Gemma to do all transcription. sherpa-onnx has Android/native support and multiple offline ASR model families.

Keep this engine independent from Gemma.

Later, Gemma 4's own multimodal audio input may be exposed for semantic audio understanding (summarization, questions about recordings, sound interpretation), which is distinct from straightforward ASR.

### Milestone 5 — local TTS

Add something equivalent to:

```text
POST /v1/audio/speech
```

Preferred first investigation: **sherpa-onnx TTS**, using its native C/C++ interface behind JNI.

The endpoint should accept OpenAI-like TTS parameters where practical and return standard audio (WAV/PCM or another deliberately chosen supported format).

Potential sherpa model families include Kokoro, VITS, Matcha and other supported offline engines. Choose models later based on quality, memory use, Android performance, licensing, and available voices.

### Milestone 6 — Gemma image understanding

Extend `/v1/chat/completions` to understand OpenAI-style multimodal message content such as text plus `image_url`/base64 image input.

Internally decode/store the image safely and pass it as a LiteRT-LM attachment/vision input together with the prompt.

The exact accelerator arrangement must be measured. The Gemma language model can use Tensor G5 while the vision encoder may potentially use a different backend depending on what the G5 package/runtime supports.

### Later research — image generation/editing

Gemma itself cannot generate images. Image generation requires a separate generative image model.

Tensor G5 is technically capable of generative image workloads; Google's own Pixel imaging pipeline demonstrates on-device diffusion-style computation. However, at the time of this handoff we have **not identified a ready-to-use open general-purpose text-to-image model already packaged for Tensor G5 NPU in the same convenient form as the Gemma G5 model**.

Possible future work: investigate LiteRT/Tensor SDK compilation of a suitably small diffusion/image model for Tensor G5.

Do not make image generation part of the initial app scope.

---

## 6. Suggested Android Studio structure

Native Android project, Java UI/service layer and C++ JNI inference layer:

```text
PixelLocalAIServer/
├── app/
│   └── src/main/
│       ├── java/<package>/
│       │   ├── MainActivity.java
│       │   ├── InferenceService.java
│       │   ├── EngineManager.java
│       │   ├── NativeInference.java
│       │   ├── OpenAiServer.java
│       │   └── ...
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   ├── native_inference.cpp
│       │   └── ...
│       ├── jniLibs/
│       │   └── arm64-v8a/
│       │       └── required native LiteRT libraries
│       └── AndroidManifest.xml
├── build.gradle / build.gradle.kts
└── settings.gradle / settings.gradle.kts
```

Target **arm64-v8a only** initially. This is specifically a Pixel 10 / Tensor G5 project before attempting broader Android compatibility.

Java should own:

- Activity/UI
- configuration
- foreground service lifecycle
- localhost HTTP server
- API authentication
- OpenAI request/response translation
- request queueing
- model/engine lifecycle policy

C++/JNI should own only what is needed to interface efficiently with LiteRT-LM/native inference.

Avoid moving general application logic into C++ unnecessarily.

---

## 7. Native runtime/version compatibility is critical

Do **not** arbitrarily replace the known-good LiteRT stack with whatever Maven/NDK/LiteRT version happens to be newest.

The Tensor-G5 model, LiteRT-LM runtime and `libLiteRtDispatch_GoogleTensor.so` need compatible versions.

The known-good standalone Tensor G5 project has used a newer/matching LiteRT-LM stack (around the v0.14.0-alpha.0 generation during our investigation) with matching LiteRT nightly/dispatch components.

Before integrating native dependencies, inspect the **current exact source/build/release definitions in the working `litert-lm-tensor-g5-npu` repository** and reproduce those versions first.

Once the Android app reproduces successful G5 inference, dependencies can be upgraded deliberately and individually with regression tests.

---

## 8. `lib_litert_lm` — use primarily as a design/reference source

Repository:

https://github.com/gsmlg-app/lib_litert_lm

This project is useful because it demonstrates concepts directly relevant to this app:

- LiteRT-LM wrapper
- CPU/GPU/NPU backend selection
- NPU dispatch library directory configuration (`litertDispatchLibDir`)
- local OpenAI-style HTTP server
- `/v1/models`
- `/v1/completions`
- `/v1/chat/completions`
- SSE streaming
- localhost binding

However it is Flutter/FFI oriented, whereas this project should be **native Java + JNI**.

Use it as an architectural/source reference rather than forcing Flutter into the project.

Also verify its current LiteRT-LM version before copying native integration code; during investigation it appeared to be based on an older LiteRT-LM generation than the known-good Tensor-G5 project.

Preserve the versions that already work with Tensor G5 first.

---

## 9. Other reference projects

Potential architecture references (not mandatory dependencies):

### OlliteRT

https://github.com/NightMean/OlliteRT

Useful for Android/OpenAI-compatible/LiteRT-LM concepts. Its documented acceleration may differ from our G5-specific requirement.

### HostAI

https://github.com/wannaphong/android-hostai

Useful for Android foreground-service + LiteRT-LM + OpenAI-compatible endpoint/SSE architecture.

Again, do not assume its native backend is equivalent to the proven Tensor G5 dispatch path.

---

## 10. Model/storage design

Current model master copy:

```text
/storage/emulated/0/AIModels/litert-npu/gemma-4-E2B-it_Google_Tensor_G5.litertlm
```

Modern Android scoped storage means the app cannot simply assume arbitrary raw shared-storage paths are readable forever.

Investigate the least-privileged robust design. Possibilities include:

1. Storage Access Framework/document picker with persistent URI permission.
2. User selects/imports a model and the app copies it into app-private or app-specific external storage.
3. If LiteRT requires a real filesystem path rather than a `content://` descriptor, determine whether a file descriptor can be used or whether copying is necessary.

Avoid `MANAGE_EXTERNAL_STORAGE` unless there is a strong technical reason. This is a local/FOSS-oriented app and broad storage access should not be the default solution.

Native `.so` files should be packaged in app-native storage (`jniLibs/arm64-v8a` / APK native libs or an equivalent controlled mechanism), **not executed from `/storage/emulated/0`**.

The Google Tensor dispatch directory passed to LiteRT should point to the app's actual native library directory.

---

## 11. API architecture should be protocol-neutral internally

OpenAI compatibility is the primary external protocol because it is widely supported by existing apps.

Do not couple the inference engine directly to OpenAI JSON classes.

Use an internal request representation, conceptually:

```text
HTTP OpenAI request
       ↓
OpenAI adapter
       ↓
Internal GenerateRequest
       ↓
EngineManager
       ↓
GemmaEngine / future ASR / TTS / Vision engines
       ↓
Internal result/events
       ↓
OpenAI adapter
       ↓
HTTP response / SSE
```

This permits adding another protocol such as a Gemini-style API later without modifying the JNI/NPU engine.

Version 1 should implement OpenAI compatibility only.

---

## 12. Future multi-engine design

The long-term app should be a local AI gateway rather than a Gemma-only wrapper:

```text
                    Pixel Local AI Server
                             |
          +------------------+------------------+
          |                  |                  |
        Chat               Audio              Vision
          |                /   \                 |
          |              ASR   TTS               |
          |               |     |                |
          v               v     v                v
      Gemma 4 E2B      sherpa  sherpa         Gemma 4
          |                                      |
          v                                      v
     Tensor G5 NPU                       best supported backend
```

Potential later image generation would be another independent engine.

Create an `EngineManager` abstraction early enough that additional engines do not require rewriting the HTTP service.

Memory matters on the 12 GB device. Do not assume every future model can remain loaded simultaneously. The engine manager should eventually support explicit load/unload and memory-aware switching.

---

## 13. Anti-Vocale investigation — useful negative evidence

Anti-Vocale 1.13.0 was tested on this phone.

Results:

- Generic `gemma-4-E2B-it.litertlm` (~2.59 GB): works.
- `gemma-4-E2B-it_Google_Tensor_G5.litertlm` (~3.11 GB): Anti-Vocale hard-crashes.
- GPU-specific `gemma-4-E2B-it-gpu.litertlm` (~2.01 GB): does not hard-crash but backend initialization fails with an XNNPACK/CPU mismatch.
- An unrelated/wrong Gemma 3 model produced `Unsupported model signature`.

Because the exact G5 model runs successfully with the standalone Tensor-G5 runtime on the same phone/ROM, this proves that the Anti-Vocale crash does **not** mean GrapheneOS blocks the NPU or that the base 12 GB Pixel 10 cannot run the model.

The likely issue is Anti-Vocale's packaged LiteRT-LM/dispatch/backend integration or version mismatch.

Do not reproduce Anti-Vocale's runtime assumptions without verifying them.

---

## 14. Hardware terminology

Pixel 10 / Tensor G5 has separate compute resources:

```text
ARM CPU cores     -> general-purpose computation
PowerVR GPU       -> graphics/general GPU compute
Tensor TPU/NPU    -> dedicated neural-network acceleration
```

The successful Gemma experiment targeted the **Tensor G5 neural accelerator**, not merely the PowerVR GPU.

LiteRT may still use heterogeneous execution, so CPU/GPU operations can coexist with NPU-dispatched graph partitions.

When reporting backend status in the app, distinguish these clearly.

---

## 15. Privacy/security principles

Primary design objective: inference remains on-device.

Defaults:

- listen only on `127.0.0.1`
- no cloud dependency for inference
- no telemetry by default
- no model/input/output uploads
- API token support
- do not log complete prompts/audio/images by default
- make verbose/debug inference logging explicitly controllable
- show users which engine/backend is active

If LAN serving is ever added, it must be an explicit opt-in with appropriate authentication/security.

---

## 16. Definition of success for the FIRST coding session

Do not try to implement the complete roadmap immediately.

The first Android Studio development target is only:

```text
1. Create a native Java Android app targeting arm64-v8a.
2. Integrate the exact native LiteRT-LM/Tensor-G5 runtime versions known to work.
3. Package/load libLiteRtDispatch_GoogleTensor.so correctly.
4. Obtain access to the existing G5 `.litertlm` model.
5. Create a minimal JNI bridge.
6. Load Gemma 4 E2B with the NPU backend.
7. Submit "Hello, briefly introduce yourself."
8. Return generated text to Java and display it.
9. Capture logs proving DispatchDelegate -> libedgetpu_litert.so was used.
```

Only after step 9 is reproducibly successful should development move to the foreground service and HTTP/OpenAI layer.

---

## 17. Instructions to Codex before changing code

Before implementing the native integration:

1. Inspect the current `litert-lm-tensor-g5-npu` repository and determine the exact working LiteRT-LM/LiteRT/dispatch versions and build flags.
2. Inspect how `litert_lm_main` constructs the model/runtime and selects `--backend=npu`.
3. Reuse/adapt that known-good native initialization path behind JNI instead of inventing a new LiteRT configuration from scratch.
4. Inspect `lib_litert_lm`, OlliteRT and HostAI only as references where useful.
5. Keep the first implementation minimal and testable.
6. Do not silently substitute CPU inference if NPU initialization fails. Surface the error and preserve diagnostic logs.
7. Avoid broad storage permissions unless proven necessary.
8. Keep Java/API code independent from LiteRT-specific native implementation details.

The most valuable fact from the previous investigation is that **the hardware, GrapheneOS, model and Google Tensor dispatch stack have already been demonstrated to work together**. The Android app's first job is to reproduce that exact successful path inside an APK.


---

## 18. Future tool layer and optional server-side web search

Design the application so that inference is **not tightly coupled directly to the HTTP endpoint**. A future tool/orchestration layer should be possible without refactoring the Tensor G5 inference core.

Web search can exist in two complementary modes:

```text
CLIENT-SIDE SEARCH

Agora / another capable client
  ├── searches the web itself
  ├── collects/extracts results
  └── sends relevant context
          ↓
 Pixel Local AI Server
          ↓
       Gemma 4
```

and:

```text
SERVER-SIDE SEARCH

Any OpenAI-compatible client
          ↓
 Pixel Local AI Server
          ↓
 Gemma decides a search/tool is needed
          ↓
 ToolManager
   ├── WebSearchTool
   └── WebFetchTool
          ↓
 search/page results returned to Gemma
          ↓
 final response returned to client
```

The server should eventually be able to support **both approaches**. If a client such as Agora already implements tools/web search, it can continue doing so. For simpler clients that only know how to call an OpenAI-compatible chat endpoint, the server may eventually execute tools internally.

Plan for a protocol-neutral Java abstraction similar to:

```java
interface AiTool {
    String getName();
    String execute(String arguments) throws Exception;
}
```

with future implementations such as:

```text
ToolManager
├── WebSearchTool
├── WebFetchTool
└── future local/network tools
```

Do **not** implement web search in milestone 1. The immediate milestone remains Java -> JNI -> LiteRT-LM -> Tensor G5 NPU text generation. The architectural requirement is only to avoid coupling `OpenAiServer` directly to low-level Gemma/LiteRT calls. Prefer a separation such as:

```text
OpenAiServer
     ↓
ChatEngine / RequestOrchestrator
     ↓
GemmaInferenceEngine
     ↓
JNI / LiteRT-LM / Tensor G5

and later:

ChatEngine / RequestOrchestrator
     ├── GemmaInferenceEngine
     └── ToolManager
           ├── WebSearchTool
           └── WebFetchTool
```

### Privacy behavior for future network tools

Local inference must remain local even when network tools are enabled. A web-search implementation should send only what is necessary for the search/fetch operation rather than uploading the entire conversation to a remote AI provider.

Plan for an explicit user-controlled network-tools policy, for example:

```text
Network tools:
- Disabled
- Ask before network access
- Enabled
```

`Disabled` should be a first-class/offline mode. Server-side search must never silently activate merely because the model requests it.

### Tool calling versus server orchestration

OpenAI-compatible tool/function calling and autonomous server-side search are separate features. Preserve that distinction. A future request may either expose a model tool call back to a capable client for execution, or allow the server's own `ToolManager` to execute an approved tool and feed the result back into the model. Do not bake either behavior into the native Tensor G5 inference layer.
