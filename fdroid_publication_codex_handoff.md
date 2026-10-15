# InferDroid — F-Droid assessment and remediation handoff

Assessment date: **2026-10-15**. Repository snapshot: `baed9f0`, with app
version **0.9.5**, version code **9**. Recheck the current checkout and upstream
policies before acting; dependency availability and F-Droid requirements change.

**Assessment:** InferDroid is a plausible candidate for the official F-Droid
repository, but its current native dependency pipeline is not ready for inclusion.
The largest issue is a required Gemma library with no implementation source at
the pinned version. The ONNX Runtime binary source also needs changing. Model
licensing, the hardware dependency, and build infrastructure need further review.

This is an engineering assessment, not an official acceptance or rejection.
Source, build scripts, native library dependencies, and an existing release APK
were inspected. A complete F-Droid build was **not** run, and no submission or
maintainer contact was made. This handoff adds documentation; it does not resolve
the issues below.

## Context to read first

- [Original architecture and hardware handoff](pixel_local_ai_server_codex_handoff.md).
- [Current README and build instructions](README.md).
- [Native integration, source limitations, and vendor library access](docs/native-integration.md).
- [Speech recognition setup](docs/speech-recognition.md) and [TTS licensing/setup](docs/tts.md).
- [Image understanding](docs/image-understanding.md) and [local API](docs/local-api.md).
- [Reasoning update review](docs/reasoning-update-review.md),
  [Agora compatibility](docs/agora-compatibility.md), and
  [FitBuddy compatibility](docs/fitbuddy-compatibility.md).

The original handoff describes an earlier development stage. Use current code,
the README, and dated verification records to establish implemented features.
Start with `git status --short` and preserve unrelated work.

## Current implementation and build baseline

The public repository is [asp4d/InferDroid](https://github.com/asp4d/InferDroid).
InferDroid's own code uses Apache-2.0. Its declared Java dependencies are
AndroidX AppCompat and Material Components. The review found no app integration
with advertising, analytics, Firebase, or Google Play Services; this does not
constitute a complete audit of opaque native dependency internals.

The app exposes an authenticated OpenAI-compatible localhost server. Chat,
images, reasoning, and client-executed tool calls use the Gemma/Tensor G5 path;
Whisper ASR and Supertonic TTS use sherpa-onnx and ONNX Runtime on CPU. Models
are supplied separately through local file/folder import. The Android app does
not download native libraries or model weights in the reviewed implementation.

| Item | Value at assessment |
| --- | --- |
| Application ID / ABI | `dev.inferdroid` / `arm64-v8a` |
| Minimum / target / compile API | `31` / `36` / `37` |
| Gradle / Android Gradle Plugin | `9.8.1` / `9.4.1` |
| Java | JDK 21 toolchain; Java 17 source/bytecode compatibility |
| NDK | `30.0.14904198` / r30-beta1; Gradle declaration includes `-beta1` |
| CMake / Bazel | `3.22.1` / `7.6.1` |
| LiteRT-LM | `v0.14.0-alpha.0`, commit `0b98b80e1d846af27d10b8ab395645119cb231e4` |
| LiteRT | commit `43d8b4f20ef743a7c5beb69c365538e726cc20d9` |
| sherpa-onnx / ONNX Runtime | `1.13.8` / `1.28.2` |
| Physical chat verification | Pixel 10 / Tensor G5 / GrapheneOS |

Native preparation runs through [build-native.sh](scripts/build-native.sh) and
[build-speech.sh](scripts/build-speech.sh), followed by Gradle/CMake for Java,
JNI, and packaging. Generated inputs in `native/artifacts/` and downloaded source
in `.deps/` are ignored by Git. A fresh Gradle invocation alone cannot recreate
them. The README estimates approximately **125 GB free disk** for the first full
native build; measure actual resource needs when preparing CI.

## Findings and required investigation

| Finding | Status | Evidence / next outcome |
| --- | --- | --- |
| Gemma constraint provider has no implementation source at the pinned tag | Main inclusion blocker | Obtain a buildable implementation or remove/replace the dependency with verified equivalent behavior. |
| ONNX Runtime is downloaded as a GitHub release binary | Dependency origin must change | Build from source or verify an acceptable, compatible Maven artifact. |
| Supertonic 3 weights have OpenRAIL-M use restrictions | Maintainer review needed | Resolve acceptability/labeling for the separately imported model. |
| Chat uses an OS-provided proprietary Tensor library | Maintainer review needed | Disclose the dependency and hardware limits; determine any applicable label. |
| Complete F-Droid build and toolchain support | Not verified | Rebuild from a clean checkout in the F-Droid environment. |
| Store/build metadata and release process | Incomplete | Add listing assets, release tags, and a tested packaging recipe. |

F-Droid's [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/) requires
publicly available free software source and restricts accepted binary dependency
origins. A checksum verifies an artifact's identity; it does not establish
source availability or approval of its origin. Verify the current policy and
record the basis for each dependency decision.

### 1. Resolve `libGemmaModelConstraintProvider.so` first

[build-native.sh](scripts/build-native.sh) explicitly downloads the upstream LFS
binary from the pinned LiteRT-LM checkout and packages it. Its SHA-256 is:

```text
985ec5778144730b80666a0f71f1e06038eb07dd1a3e941c6ab7f963eda00a8e
```

The implementation is unavailable at that tag, as documented in
[native integration](docs/native-integration.md#licensing-and-source-limitations).
The upstream Bazel target selects a prebuilt shared library. Inspection of the
existing `libinferdroid_litertlm.so` confirmed a `DT_NEEDED` entry for
`libGemmaModelConstraintProvider.so`; the release APK contains both libraries.
Keeping the blob outside Git does not change this dependency.

Investigate, in order:

1. Whether upstream has since published the implementation and a supported
   source build, including all dependencies and license notices.
2. Whether a compatible LiteRT-LM configuration/version can eliminate the
   dependency while preserving the required features.
3. Whether a source-available replacement is feasible. Document any feature or
   performance tradeoffs before changing the supported engine architecture.

The checkout includes a CMake stub under
`cmake/patches/stubs/gemma_model_constraint_provider.cc`. Its existence is not
proof that it implements the binary's behavior. A build that links with a stub
does not establish a working replacement. Do not silently remove tool calling,
reasoning, vision, or NPU functionality to obtain a successful build.

Do not treat `scanignore`, relocating the blob, or republishing it as a different
artifact as a resolution. Establish the actual source/provenance first. If the
dependency remains unresolved, report it explicitly and continue useful work on
the independent packaging issues without claiming official-repository readiness.

### 2. Resolve the ONNX Runtime binary origin

[build-speech.sh](scripts/build-speech.sh) builds sherpa-onnx from source but
downloads `onnxruntime-android-1.28.2.zip` from
`csukuangfj/onnxruntime-libs` on GitHub, then copies its `libonnxruntime.so` into
the APK. The archive SHA-256 is:

```text
01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d
```

Prefer building the matching ONNX Runtime from pinned source. Alternatively,
investigate a compatible FLOSS artifact from an accepted Maven repository;
verify its exact version, contents, source, and suitability with F-Droid.
Availability and compatibility of such an alternative were not established.

Keep C API headers and the shared library matched. Preserve sherpa patches in
[prepare-tts-runtime.py](scripts/prepare-tts-runtime.py) and
[tts_safe_c_api.cc](native/tts_safe_c_api.cc), including TTS capacity checks and
exception handling. Check the transitive CMake dependencies and generated
notices, not just the top-level runtime archive. Regress both ASR and TTS.

### 3. Review model licensing separately from runtime code

Supertonic 3 weights use **BigScience OpenRAIL-M**, with use restrictions. The
MIT notice in the model bundle covers sample code and does not replace the
weights' license. InferDroid's own Apache license also does not override it.
The current importer accepts the exact tested Supertonic export.

Use the [pinned model license](https://huggingface.co/Supertone/supertonic-3/raw/3cadd1ee6394adea1bd021217a0e650ede09a323/LICENSE)
and [local licensing notes](docs/tts.md#model-and-licensing). The weights are
imported separately and are absent from the APK. Ask for a decision on this
specific arrangement when engaging F-Droid maintainers; do not assume either
automatic rejection or automatic acceptance with a particular anti-feature.
The existing [Supertonic TTS listing](https://f-droid.org/packages/com.brahmadeo.supertonic.tts/)
is useful precedent to investigate, not approval of InferDroid's model version.

Possible outcomes include retaining external model support with an agreed
description/label, supporting a model with an accepted free license, or preparing
a documented build variant. Any replacement needs its own code, weights, and
voice-license assessment plus runtime testing.

The reviewed [Gemma 4 model card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/raw/main/README.md)
identifies Apache-2.0. Verify the exact artifact again; do not transfer licensing
assumptions from older Gemma releases. Whisper's model licensing is documented
in [speech recognition](docs/speech-recognition.md).

### 4. Review the Tensor G5 dependency and device support

Keep these components distinct:

| Component | Current provenance |
| --- | --- |
| `libLiteRtDispatch_GoogleTensor.so` | Built from public LiteRT source by InferDroid's script. |
| `/vendor/lib64/libedgetpu_litert.so` | Proprietary library supplied by the phone's OS; not redistributed in the APK. |
| Tensor SDK compiler | Not bundled or required by the current app build for the existing compiled model. |
| `libGemmaModelConstraintProvider.so` | Bundled upstream binary; addressed in finding 1. |

The manifest declares the vendor library with `android:required="false"`.
That permits installation without it; it does not establish that NPU chat works
on other devices. The runtime intentionally has no automatic CPU fallback.

Disclose the OS library dependency and supported hardware for maintainer review
using the current [anti-feature definitions](https://f-droid.org/docs/Anti-Features/).
Do not infer a universal requirement to remove NPU support. Ensure the listing
describes chat's hardware limits and the app handles unsupported devices clearly.
ASR/TTS are independent CPU engines; distinguish their support from NPU chat.

## Prepare packaging after establishing a viable native path

1. **Recheck toolchain availability.** Validate SDK 37, AGP 9.4.1, Gradle 9.8.1,
   JDK 21, Bazel 7.6.1, CMake, and the exact NDK against F-Droid's current build
   environment. The beta NDK is an unresolved compatibility question, not a
   demonstrated rejection. Change toolchains deliberately and regress hardware
   behavior; preserve the known-good baseline for comparison.
2. **Make the build work from a clean checkout.** Supply dependency sources and
   notices without relying on existing `.deps/`, `native/artifacts/`, workstation
   paths, private credentials, or cached binaries. Pin all inputs. Respect
   F-Droid's source scanning and dependency-fetching stages. In its metadata,
   native compilation belongs in the `build` phase rather than `prebuild`.
   Consult the [build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/).
3. **Audit source and APK contents.** Resolve scanner findings and inventory
   native libraries, dependency origins, and licenses. Review tracked release
   archives and unused upstream prebuilt/test artifacts for removal from the
   packaging input where appropriate. Generated license files are build outputs
   too and must be recreated. Avoid broad scanner exclusions.
4. **Prepare release metadata.** Tag the submitted commit, use increasing version
   codes, and add the listing structure below. Match the changelog filename to
   the submitted version code; the baseline here is 9. Describe model setup,
   storage needs, hardware limits, and the actual feature set.
5. **Write and validate the recipe.** Prepare `metadata/dev.inferdroid.yml` in
   `fdroiddata`, using the public HTTPS Git URL. Include pinned build inputs,
   required tools, native steps, Gradle configuration, and update detection.
   Test in F-Droid's environment. No working recipe was produced in this review.
6. **Choose signing and distribution.** Ordinary F-Droid signing produces a
   different signature from an independently signed APK, affecting in-place
   updates. Keeping the upstream signature requires the appropriate
   [reproducible-build verification](https://f-droid.org/docs/Reproducible_Builds/).
   Source-build eligibility and byte-for-byte APK reproducibility are separate
   milestones. Preserve existing signing keys and installed model data.

Suggested upstream listing files:

```text
fastlane/metadata/android/en-US/
  short_description.txt
  full_description.txt
  images/icon.png
  images/phoneScreenshots/1.png
  images/phoneScreenshots/2.png
  changelogs/<versionCode>.txt
```

Once the recipe is ready, run the applicable metadata checks and build inside
the configured `fdroiddata` environment, for example:

```bash
fdroid readmeta
fdroid lint dev.inferdroid
fdroid build dev.inferdroid
```

The submission routes are a merge request to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) with a working recipe or a
[Request for Packaging](https://gitlab.com/fdroid/rfp/-/issues).
Follow the current [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/).
Prepare concrete changes, logs, and remaining questions before submitting.

## Verification and completion criteria

After dependency/runtime changes, use existing tests and device procedures from
the linked verification documents. At minimum, check:

- Clean native and release builds, Android lint, and F-Droid metadata/scanner
  results. Record exact source revisions, tool versions, commands, and APK hash.
- APK library inventory and ELF `DT_NEEDED` entries. Every retained native
  dependency needs an explained source/build or accepted artifact origin.
- On the physical Pixel: NPU initialization and actual graph-execution evidence,
  text chat, SSE, history, function calls/results, reasoning, and image input.
- Whisper ASR and Supertonic or replacement TTS, including representative audio
  formats, playback/export, cancellation, and reuse after errors.
- Busy responses/shared work serialization, authenticated localhost serving,
  background operation, unload/stop, cold restart, and model import/reuse.
- Actual signing/update behavior and clear behavior on unsupported hardware.

Do not substitute historical device evidence for testing a changed runtime.
Report unavailable device or infrastructure checks explicitly. A compile-only
result is insufficient evidence that the replacement preserves the app.

The remediation deliverable should include implemented changes, a tested recipe,
updated setup/license documentation, build and device evidence, a signing plan,
and the disposition of each finding. Describe the app as ready to submit only
when the build blockers are resolved and any remaining policy questions are
clearly presented for review. Official acceptance remains F-Droid's decision.

If the immediate objective is distribution through F-Droid clients, a
[self-hosted APK repository](https://f-droid.org/docs/Setup_an_F-Droid_App_Repo/)
is another option: users add the repository manually. It does not establish
official inclusion or resolve redistribution rights for any dependency.
