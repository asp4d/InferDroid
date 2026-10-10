package dev.inferdroid.speech;

import java.nio.charset.StandardCharsets;

public final class TranscriptionResult {
    public enum Failure { NONE, CANCELLED, INVALID_AUDIO, AUDIO_TOO_LARGE, MODEL_UNAVAILABLE, ENGINE }
    public final boolean success;
    public final Failure failure;
    public final String text;
    public final String language;
    public final double durationSeconds;
    public final String diagnostics;

    public TranscriptionResult(String text, String language, double durationSeconds, String diagnostics) {
        this(true, Failure.NONE, text, language, durationSeconds, diagnostics);
    }
    private TranscriptionResult(boolean success, Failure failure, String text, String language,
                                double durationSeconds, String diagnostics) {
        this.success = success; this.failure = failure; this.text = text;
        this.language = language; this.durationSeconds = durationSeconds; this.diagnostics = diagnostics;
    }
    // Native text is ordinary UTF-8, not JNI modified UTF-8.
    public TranscriptionResult(byte[] text, byte[] language, double durationSeconds) {
        this(new String(text, StandardCharsets.UTF_8), new String(language, StandardCharsets.UTF_8),
                durationSeconds, "sherpa-onnx 1.13.8 · Whisper tiny int8 · ONNX Runtime CPU (2 threads)");
    }
    public static TranscriptionResult failure(Failure failure, String diagnostics) {
        return new TranscriptionResult(false, failure, "", "", 0, diagnostics);
    }
}
