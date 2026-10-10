package dev.inferdroid.speech;

import android.util.Log;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** CPU ASR only; never loads or calls the Gemma/LiteRT engine. */
public final class SherpaSpeechEngine implements SpeechEngine {
    private final SpeechModelStore models;
    private volatile long handle;
    public SherpaSpeechEngine(SpeechModelStore models) { this.models = models; }
    @Override public boolean isLoaded() { return handle != 0; }
    @Override public void load() throws Exception {
        if (isLoaded()) return;
        models.requireAvailable();
        handle = NativeSpeech.load(models.directory().getAbsolutePath());
        if (handle == 0) throw new IllegalStateException("Whisper initialization failed.");
        Log.i("InferDroid", "Whisper tiny initialized · sherpa-onnx 1.13.8 · ONNX Runtime CPU");
    }
    @Override public TranscriptionResult transcribe(TranscriptionRequest request, AtomicBoolean cancelled) throws Exception {
        AudioDecoder.Audio audio;
        try (InputStream input = request.audio.open()) { audio = AudioDecoder.decode(input, cancelled); }
        StringBuilder text = new StringBuilder();
        String language = request.language;
        // Upstream truncates audio near 30 seconds. Never silently discard a long file.
        // Fixed 25-second pieces keep every sample and provide cancellation boundaries.
        int chunkSamples = 25 * AudioDecoder.SAMPLE_RATE;
        for (int start = 0; start < audio.samples.length; start += chunkSamples) {
            if (cancelled.get()) return TranscriptionResult.failure(TranscriptionResult.Failure.CANCELLED, "Speech request cancelled.");
            float[] chunk = Arrays.copyOfRange(audio.samples, start, Math.min(start + chunkSamples, audio.samples.length));
            TranscriptionResult result = NativeSpeech.transcribe(handle, chunk, language);
            if (cancelled.get()) return TranscriptionResult.failure(TranscriptionResult.Failure.CANCELLED, "Speech request cancelled.");
            if (text.length() > 0 && !result.text.trim().isEmpty()) text.append(' ');
            text.append(result.text.trim());
            if (language.isEmpty()) language = result.language;
        }
        return new TranscriptionResult(text.toString(), language, audio.durationSeconds(),
                "sherpa-onnx 1.13.8 · Whisper tiny int8 · ONNX Runtime CPU (2 threads) · 25-second chunks");
    }
    @Override public void unload() {
        long previous = handle; handle = 0;
        if (previous != 0) { NativeSpeech.unload(previous); Log.i("InferDroid", "Speech engine unloaded"); }
    }
}
