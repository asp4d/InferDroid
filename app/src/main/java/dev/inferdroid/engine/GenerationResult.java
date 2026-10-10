package dev.inferdroid.engine;

import java.nio.charset.StandardCharsets;

public final class GenerationResult {
    public final boolean success;
    public final boolean cancelled;
    public final String text;
    public final String diagnostics;
    public final int promptTokens;
    public final int completionTokens;
    public final boolean limitReached;

    // Called by JNI; byte arrays preserve ordinary UTF-8 including supplementary characters.
    public GenerationResult(boolean success, boolean cancelled, byte[] text, byte[] diagnostics) {
        this(success, cancelled, text, diagnostics, -1, -1, false);
    }

    public GenerationResult(boolean success, boolean cancelled, byte[] text, byte[] diagnostics,
                            int promptTokens, int completionTokens, boolean limitReached) {
        this.success = success;
        this.cancelled = cancelled;
        this.text = new String(text, StandardCharsets.UTF_8);
        this.diagnostics = new String(diagnostics, StandardCharsets.UTF_8);
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.limitReached = limitReached;
    }

    public GenerationResult(boolean success, String text, String diagnostics) {
        this(success, false, text, diagnostics);
    }

    public GenerationResult(boolean success, boolean cancelled, String text, String diagnostics) {
        this(success, cancelled, text, diagnostics, -1, -1, false);
    }

    public GenerationResult(boolean success, boolean cancelled, String text, String diagnostics,
                            int promptTokens, int completionTokens, boolean limitReached) {
        this.success = success;
        this.cancelled = cancelled;
        this.text = text;
        this.diagnostics = diagnostics;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.limitReached = limitReached;
    }

    public GenerationResult withDiagnostics(String value) {
        return new GenerationResult(success, cancelled, text, value,
                promptTokens, completionTokens, limitReached);
    }
}
