#!/usr/bin/env python3
"""Select Supertonic TTS at the pinned sherpa tag without unused phonemizers.

Applied only to an extracted, ignored build tree. ASR sources are untouched.
Keep every model config for the public C ABI; only Supertonic is instantiated.
"""
from pathlib import Path
import re
import shutil
import sys

root = Path(sys.argv[1])
# This export's vector estimator has 1000 positional entries. Check predicted
# duration before allocation/Run, and translate exceptions inside the same DSO.
impl = root / 'sherpa-onnx/csrc/offline-tts-supertonic-impl.cc'
guard_marker = '// InferDroid: enforce the pinned export positional capacity.'
text = impl.read_text()
if guard_marker not in text:
    assert 'constexpr int32_t kMaxLatentLen = 10000;' in text
    text = text.replace('#include <sstream>', '#include <sstream>\n#include <stdexcept>')
    text = text.replace('constexpr int32_t kMaxLatentLen = 10000;',
                        guard_marker + '\nconstexpr int32_t kMaxLatentLen = 1000;')
    needle = '  int32_t chunk_size = cfg.ae.base_chunk_size * cfg.ttl.chunk_compress_factor;'
    assert text.count(needle) == 1
    text = text.replace(needle, needle + '''
  if (!std::isfinite(wav_len_max) || wav_len_max < 1 ||
      wav_len_max > static_cast<float>(kMaxLatentLen * chunk_size)) {
    throw std::length_error("TTS chunk exceeds the model positional capacity.");
  }''')
    # Error diagnostics must not contain the caller's text.
    text = text.replace('text.c_str()', '"[redacted]"')
    impl.write_text(text)

api = root / 'sherpa-onnx/c-api'
shutil.copyfile(Path(__file__).resolve().parent.parent / 'native/tts_safe_c_api.cc',
                api / 'inferdroid-tts-safe-api.cc')
cmake = api / 'CMakeLists.txt'
text = cmake.read_text()
if 'inferdroid-tts-safe-api.cc' not in text:
    needle = 'add_library(sherpa-onnx-c-api c-api.cc)'
    assert text.count(needle) == 1
    cmake.write_text(text.replace(needle,
        'add_library(sherpa-onnx-c-api c-api.cc inferdroid-tts-safe-api.cc)'))

top = root / 'CMakeLists.txt'
core = root / 'sherpa-onnx/csrc/CMakeLists.txt'
factory = root / 'sherpa-onnx/csrc/offline-tts-impl.cc'
marker = '# InferDroid: Supertonic-only TTS; no phonemizer dependency.'
if marker in top.read_text():
    sys.exit(0)

text = top.read_text()
text, count = re.subn(r'if\(SHERPA_ONNX_ENABLE_TTS\)\n  include\(espeak-ng-for-piper\).*?\nendif\(\)', marker, text, flags=re.S)
assert count == 1, 'Pinned sherpa dependency recipe changed'
top.write_text(text)

text = core.read_text()
start = text.index('if(SHERPA_ONNX_ENABLE_TTS)\n  list(APPEND sources')
end = text.index('\nendif()', start) + len('\nendif()')
configs = ['vits', 'matcha', 'kokoro', 'kitten', 'zipvoice', 'pocket', 'supertonic']
sources = ['offline-tts.cc', 'offline-tts-impl.cc', 'offline-tts-model-config.cc',
           'offline-tts-supertonic-impl.cc', 'offline-tts-supertonic-model.cc',
           'offline-tts-supertonic-unicode-processor.cc']
sources += [f'offline-tts-{name}-model-config.cc' for name in configs]
replacement = 'if(SHERPA_ONNX_ENABLE_TTS)\n  list(APPEND sources\n' + ''.join(f'    {s}\n' for s in sources) + '  )\nendif()'
text = text[:start] + replacement + text[end:]
text, count = re.subn(r'if\(SHERPA_ONNX_ENABLE_TTS\)\n  target_link_libraries\(sherpa-onnx-core\n    piper_phonemize\)\nendif\(\)', marker, text)
assert count == 1, 'Pinned sherpa phonemizer linkage changed'
core.write_text(text)

text = factory.read_text()
text = re.sub(r'#include "sherpa-onnx/csrc/offline-tts-(kitten|kokoro|matcha|pocket|vits|zipvoice)-impl.h"\n', '', text)
text, count = re.subn(r'  if \(!config.model.vits.model.empty\(\)\) \{.*?  \} else if \(!config.model.supertonic.tts_json.empty\(\)\) \{',
                      '  if (!config.model.supertonic.tts_json.empty()) {', text, flags=re.S)
assert count == 2, 'Pinned sherpa TTS factory changed'
factory.write_text(text)
