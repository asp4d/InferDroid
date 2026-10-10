package dev.inferdroid.tts;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent CPU TTS. Retains four ONNX sessions; never calls Gemma or ASR. */
public final class SherpaTtsEngine implements TtsEngine {
    public static final class OutputLimitException extends RuntimeException {
        public OutputLimitException(String message) { super(message); }
    }
    public static final class ChunkLimitException extends RuntimeException {
        public ChunkLimitException(String message) { super(message); }
    }
    private final TtsModelStore models;
    private volatile long handle;
    public SherpaTtsEngine(TtsModelStore models) { this.models = models; }
    @Override public boolean isLoaded() { return handle != 0; }
    @Override public void load() throws Exception {
        if (isLoaded()) return;
        models.requireAvailable();
        handle = NativeTts.load(models.directory().getAbsolutePath());
        if (handle == 0) throw new IllegalStateException("Supertonic initialization failed.");
        Log.i("InferDroid", "Supertonic 3 initialized · ONNX Runtime CPU");
    }
    @Override public SynthesisResult synthesize(SynthesisRequest request, AtomicBoolean cancelled) {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        // Bound each synchronous native call and check cancellation between chunks.
        // Preserve every character; prefer punctuation/whitespace boundaries.
        for (int start = 0; start < request.text.length();) {
            if (cancelled.get()) return cancelled();
            int end = Math.min(start + 240, request.text.length());
            if (end < request.text.length()) {
                if (Character.isHighSurrogate(request.text.charAt(end - 1))) end--;
                int boundary = end;
                while (boundary > start + 120 && !Character.isWhitespace(request.text.charAt(boundary - 1))
                        && ".!?;。！？".indexOf(request.text.charAt(boundary - 1)) < 0) boundary--;
                if (boundary > start + 120) end = boundary;
            }
            // Native text normalization trims whitespace. Do not ask it to
            // synthesize an empty chunk after a long run of spaces/newlines.
            if (request.text.substring(start, end).isBlank()) { start = end; continue; }
            byte[] chunk;
            for (;;) {
                if (cancelled.get()) return cancelled();
                try {
                    chunk = NativeTts.generate(handle, request.text.substring(start, end).getBytes(StandardCharsets.UTF_8),
                            request.language, request.speaker, request.speed);
                    break;
                } catch (ChunkLimitException limit) {
                    // The duration predictor can exceed the export's fixed
                    // positional table at slow speeds. Retry a smaller prefix.
                    if (end - start <= 1) throw new OutputLimitException("Text exceeds the TTS model's duration capacity. Use shorter text or a faster speed.");
                    end = start + (end - start) / 2;
                    if (Character.isHighSurrogate(request.text.charAt(end - 1))) end--;
                    if (end == start) throw new OutputLimitException("Text exceeds the TTS model's duration capacity. Use shorter text or a faster speed.");
                }
            }
            if (cancelled.get()) return cancelled();
            int pause = start > 0 ? PcmAudio.SAMPLE_RATE * 2 / 5 : 0;
            if (audio.size() + (long) chunk.length + pause > PcmAudio.MAX_BYTES)
                return SynthesisResult.failure(SynthesisResult.Failure.AUDIO_TOO_LARGE, "Generated audio exceeds 120 seconds. Use shorter text or a faster speed.");
            if (pause > 0) audio.write(new byte[pause], 0, pause);
            audio.write(chunk, 0, chunk.length);
            start = end;
        }
        return new SynthesisResult(new PcmAudio(audio.toByteArray()), "Supertonic 3 int8 · ONNX Runtime CPU (2 threads) · 24 kHz mono PCM16");
    }
    private static SynthesisResult cancelled() { return SynthesisResult.failure(SynthesisResult.Failure.CANCELLED, "TTS request cancelled."); }
    @Override public void unload() {
        long previous = handle; handle = 0;
        if (previous != 0) { NativeTts.unload(previous); Log.i("InferDroid", "TTS engine unloaded"); }
    }
}
