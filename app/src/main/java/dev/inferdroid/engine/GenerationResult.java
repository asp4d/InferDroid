package dev.inferdroid.engine;

import java.nio.charset.StandardCharsets;

public final class GenerationResult {
    public final boolean success;
    public final boolean cancelled;
    public final String text;
    public final String diagnostics;

    // Called by JNI; byte arrays preserve ordinary UTF-8 including supplementary characters.
    public GenerationResult(boolean success, boolean cancelled, byte[] text, byte[] diagnostics) {
        this.success = success;
        this.cancelled = cancelled;
        this.text = new String(text, StandardCharsets.UTF_8);
        this.diagnostics = new String(diagnostics, StandardCharsets.UTF_8);
    }

    public GenerationResult(boolean success, String text, String diagnostics) {
        this(success, false, text, diagnostics);
    }

    public GenerationResult(boolean success, boolean cancelled, String text, String diagnostics) {
        this.success = success;
        this.cancelled = cancelled;
        this.text = text;
        this.diagnostics = diagnostics;
    }
}
