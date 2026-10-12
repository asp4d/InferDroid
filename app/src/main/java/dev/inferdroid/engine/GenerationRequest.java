package dev.inferdroid.engine;

import java.util.List;

/** Protocol-neutral chat request. Every request supplies its own conversation. */
public final class GenerationRequest {
    public final String modelSource;
    public final String prompt;
    public final boolean verbose;
    public final List<ChatMessage> messages;
    public final int maxOutputTokens;
    public final Double temperature;
    public final Double topP;
    public final Integer seed;
    public final boolean enableThinking;
    public final List<ToolDefinition> tools;
    public final boolean allowToolCalls;

    public GenerationRequest(String modelSource, String prompt, boolean verbose) {
        this(modelSource, List.of(new ChatMessage("user", prompt)), verbose, 256, null, null, null);
    }

    public GenerationRequest(String modelSource, List<ChatMessage> messages, boolean verbose,
                             int maxOutputTokens, Double temperature, Double topP, Integer seed) {
        this(modelSource, messages, verbose, maxOutputTokens, temperature, topP, seed, false);
    }

    public GenerationRequest(String modelSource, List<ChatMessage> messages, boolean verbose,
                             int maxOutputTokens, Double temperature, Double topP, Integer seed,
                             boolean enableThinking) {
        this(modelSource, messages, verbose, maxOutputTokens, temperature, topP, seed,
                enableThinking, List.of(), false);
    }

    public GenerationRequest(String modelSource, List<ChatMessage> messages, boolean verbose,
                             int maxOutputTokens, Double temperature, Double topP, Integer seed,
                             boolean enableThinking, List<ToolDefinition> tools, boolean allowToolCalls) {
        this.modelSource = modelSource;
        this.messages = List.copyOf(messages);
        this.prompt = messages.get(messages.size() - 1).text;
        this.verbose = verbose;
        this.maxOutputTokens = maxOutputTokens;
        this.temperature = temperature;
        this.topP = topP;
        this.seed = seed;
        this.enableThinking = enableThinking;
        this.tools = List.copyOf(tools);
        this.allowToolCalls = allowToolCalls;
    }
}
