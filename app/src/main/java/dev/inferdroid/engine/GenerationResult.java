package dev.inferdroid.engine;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class GenerationResult {
    public final boolean success;
    public final boolean cancelled;
    public final String text;
    public final String reasoning;
    public final List<ToolCall> toolCalls;
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
        this(success, cancelled, text, new byte[0], diagnostics, promptTokens, completionTokens, limitReached);
    }

    public GenerationResult(boolean success, boolean cancelled, byte[] text, byte[] reasoning, byte[] diagnostics,
                            int promptTokens, int completionTokens, boolean limitReached) {
        this(success, cancelled, new String(text, StandardCharsets.UTF_8), new String(reasoning, StandardCharsets.UTF_8),
                new String(diagnostics, StandardCharsets.UTF_8), promptTokens, completionTokens, limitReached);
    }

    // JNI supplies only complete, validated calls; HTTP serialization assigns their wire shape.
    public GenerationResult(boolean success, boolean cancelled, byte[] text, byte[] reasoning,
                            byte[] toolCalls, byte[] diagnostics,
                            int promptTokens, int completionTokens, boolean limitReached) {
        this(success, cancelled, new String(text, StandardCharsets.UTF_8), new String(reasoning, StandardCharsets.UTF_8),
                ToolCall.fromNative(toolCalls), new String(diagnostics, StandardCharsets.UTF_8),
                promptTokens, completionTokens, limitReached);
    }

    public GenerationResult(boolean success, String text, String diagnostics) {
        this(success, false, text, diagnostics);
    }

    public GenerationResult(boolean success, boolean cancelled, String text, String diagnostics) {
        this(success, cancelled, text, diagnostics, -1, -1, false);
    }

    public GenerationResult(boolean success, boolean cancelled, String text, String diagnostics,
                            int promptTokens, int completionTokens, boolean limitReached) {
        this(success, cancelled, text, "", diagnostics, promptTokens, completionTokens, limitReached);
    }

    public GenerationResult(boolean success, boolean cancelled, String text, String reasoning, String diagnostics,
                            int promptTokens, int completionTokens, boolean limitReached) {
        this(success, cancelled, text, reasoning, List.of(), diagnostics,
                promptTokens, completionTokens, limitReached);
    }

    public GenerationResult(boolean success, boolean cancelled, String text, String reasoning,
                            List<ToolCall> toolCalls, String diagnostics,
                            int promptTokens, int completionTokens, boolean limitReached) {
        this.success = success;
        this.cancelled = cancelled;
        this.text = text;
        this.reasoning = reasoning;
        this.toolCalls = List.copyOf(toolCalls);
        this.diagnostics = diagnostics;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.limitReached = limitReached;
    }

    public GenerationResult withDiagnostics(String value) {
        return new GenerationResult(success, cancelled, text, reasoning, toolCalls, value,
                promptTokens, completionTokens, limitReached);
    }
}
