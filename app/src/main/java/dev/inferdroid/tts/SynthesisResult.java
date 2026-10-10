package dev.inferdroid.tts;

public final class SynthesisResult {
    public enum Failure { NONE, CANCELLED, AUDIO_TOO_LARGE, MODEL_UNAVAILABLE, ENGINE }
    public final boolean success;
    public final PcmAudio audio;
    public final Failure failure;
    public final String diagnostics;
    public SynthesisResult(PcmAudio audio, String diagnostics) {
        success = true; this.audio = audio; failure = Failure.NONE; this.diagnostics = diagnostics;
    }
    private SynthesisResult(Failure failure, String diagnostics) {
        success = false; audio = null; this.failure = failure; this.diagnostics = diagnostics;
    }
    public static SynthesisResult failure(Failure failure, String diagnostics) { return new SynthesisResult(failure, diagnostics); }
}
